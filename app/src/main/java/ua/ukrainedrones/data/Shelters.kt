package ua.ukrainedrones
import ua.ukrainedrones.engine.distanceFlat

import org.json.JSONObject

/** Classification of protective structures in Ukrainian civil defense. */
enum class ShelterType {
    BASIC,
    MOBILE,
    BUNKER;

    companion object {
        fun infer(name: String, iconStr: String = ""): ShelterType {
            val lower = name.lowercase()
            return when {
                lower.contains("мобільн") || lower.contains("первинн") -> MOBILE
                name.contains("ЗСЦЗ") || lower.contains("метро") || lower.contains("ст. м.") || lower.contains("трамва") || lower.contains("бункер") || iconStr.contains("195861") || iconStr.contains("195862") -> BUNKER
                else -> BASIC
            }
        }
    }
}

/** One protective structure from the shelter layer. */
data class Shelter(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: ShelterType = ShelterType.infer(name)
) {
    /** Straight-line distance to a query point, in meters (Haversine). */
    fun distanceFlat(fromLat: Double, fromLon: Double): Double =
        distanceFlat(fromLat, fromLon, lat, lon)
}

/** Walking speed at a brisk adult pace (~5 km/h). */
private const val ADULT_M_PER_MIN = 83.3334
/** Walking speed with small children (~3 km/h). */
private const val KID_M_PER_MIN = 50.0

/** English display name for a Ukrainian shelter name: glossary translation, then transliteration. */
fun shelterNameEn(ua: String): String {
    var s = ua
    SHELTER_NAME_GLOSSARY.entries
        .sortedByDescending { it.key.length }
        .forEach { (uaPart, enPart) ->
            s = s.replace(uaPart, enPart, ignoreCase = true)
        }
    val capitalized = ua.isNotEmpty() && ua[0].isUpperCase() &&
        s.isNotEmpty() && s[0].isLowerCase()
    val out = Transliteration.transliterate(s)
    return if (capitalized) out.replaceFirstChar { it.uppercase() } else out
}

/** A shelter paired with its straight-line distance to a query point. */
data class NearestShelter(
    val shelter: Shelter,
    val distanceMeters: Double
) {
    /** Walking time at a brisk adult pace (~5 km/h). */
    val walkMinutesAdult: Int get() = ceilMinutes(distanceMeters, ADULT_M_PER_MIN)
    /** Walking time with small children (~3 km/h). */
    val walkMinutesKid: Int get() = ceilMinutes(distanceMeters, KID_M_PER_MIN)

    private fun ceilMinutes(meters: Double, speedMetersPerMin: Double): Int =
        if (meters <= 0) 0 else kotlin.math.ceil(meters / speedMetersPerMin - 1e-9).toInt()
}

/**
 * In-memory spatial index of shelters, parsed from the compact
 * [id, "lat,lon", icon, name, flag] JSON array. Immutable once built, so it is
 * safe to share across the UI. Rows with empty or out-of-bounds coordinates are dropped.
 */
class ShelterIndex private constructor(
    private val shelters: List<Shelter>,
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double
) {
    val size: Int get() = shelters.size

    // Spatial hash grid: 0.1 degree grid cells (~11km lat x ~7-8km lon)
    private val grid: Map<Long, List<Shelter>>

    init {
        val map = HashMap<Long, MutableList<Shelter>>(shelters.size.coerceAtLeast(16))
        for (s in shelters) {
            val key = cellKey(s.lat, s.lon)
            map.getOrPut(key) { ArrayList(8) }.add(s)
        }
        grid = map
    }

    /** Whether a point falls inside the parsed shelter data extent. */
    fun withinRegion(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon

    /** Nearest [limit] shelters to the given point, closest first. */
    fun nearest(fromLat: Double, fromLon: Double, limit: Int = 20): List<NearestShelter> {
        if (shelters.isEmpty()) return emptyList()
        val targetLimit = limit.coerceAtLeast(1)

        val latCell = (fromLat * GRID_SCALE).toInt()
        val lonCell = (fromLon * GRID_SCALE).toInt()

        val candidates = ArrayList<Shelter>(targetLimit * 4)
        var ring = 0
        val maxRing = 15 // up to ~150km search radius in grid
        while (ring <= maxRing) {
            for (dLat in -ring..ring) {
                for (dLon in -ring..ring) {
                    if (ring > 0 && Math.abs(dLat) != ring && Math.abs(dLon) != ring) continue
                    val key = packKey(latCell + dLat, lonCell + dLon)
                    grid[key]?.let { candidates.addAll(it) }
                }
            }
            if (candidates.size >= targetLimit && ring >= 2) break
            ring++
        }

        val sourceList = if (candidates.size >= targetLimit) candidates else shelters
        return sourceList
            .map { NearestShelter(it, it.distanceFlat(fromLat, fromLon)) }
            .sortedBy { it.distanceMeters }
            .take(targetLimit)
    }

    companion object {
        private const val GRID_SCALE = 10.0 // 0.1 degree grid cells

        private fun cellKey(lat: Double, lon: Double): Long =
            packKey((lat * GRID_SCALE).toInt(), (lon * GRID_SCALE).toInt())

        private fun packKey(latCell: Int, lonCell: Int): Long =
            (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)

        /** Parses the JSON payload; null when unreadable. Supports compact [id, "lat,lon", name] and legacy arrays. */
        fun fromJson(json: String): ShelterIndex? = runCatching {
            val data = JSONObject(json).getJSONArray("data")
            val list = ArrayList<Shelter>(data.length())
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                val coordStr = row.optString(1)
                val comma = coordStr.indexOf(',')
                if (comma <= 0) continue
                val lat = coordStr.substring(0, comma).trim().toDoubleOrNull() ?: continue
                val lon = coordStr.substring(comma + 1).trim().toDoubleOrNull() ?: continue
                if (lat < 30.0 || lat > 60.0 || lon < 15.0 || lon > 50.0) continue

                val (name, iconStr) = if (row.length() >= 4) {
                    row.optString(3) to row.optString(2)
                } else {
                    row.optString(2) to ""
                }
                if (name.isBlank()) continue
                val type = ShelterType.infer(name, iconStr)
                list += Shelter(row.optString(0), name, lat, lon, type)
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
            }
            if (list.isEmpty()) return@runCatching null
            ShelterIndex(list, minLat, maxLat, minLon, maxLon)
        }.getOrNull()
    }
}