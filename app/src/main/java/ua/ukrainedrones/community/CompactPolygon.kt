package ua.ukrainedrones.community

/**
 * Normalized geographic coordinate (lat, lon) in WGS-84.
 * Latitude is strictly first; longitude is strictly second.
 * Named [LatLon] to avoid collisions with org.osmdroid.util.GeoPoint or other map engines.
 */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Highly optimized, zero-allocation boundary polygon ring.
 * Stored as a flat primitive array of scaled integers: [lat0, lon0, lat1, lon1, ...]
 *
 * Coordinates are strictly normalized to [LAT, LON] order.
 *
 * Precision scaling:
 * - Default scale = 1000.0 (3 decimal places: ~70m ground resolution, optimal for 1km scale maps).
 * - High-precision scale = 10000.0 (4 decimal places: ~7m resolution, sub-shelter level).
 *
 * Memory comparison:
 * - Old approach: List<DoubleArray> creates N heap objects + 64-bit doubles (~40 bytes per point).
 * - ScaledRing: 1 single flat IntArray for the entire ring (4 bytes per coordinate, zero per-point allocation).
 */
@JvmInline
value class ScaledRing(val data: IntArray) {
    val pointCount: Int get() = data.size / 2

    fun getLat(index: Int, scale: Double = 1000.0): Double = data[index * 2] / scale
    fun getLon(index: Int, scale: Double = 1000.0): Double = data[index * 2 + 1] / scale
    fun getPoint(index: Int, scale: Double = 1000.0): LatLon = LatLon(getLat(index, scale), getLon(index, scale))

    /**
     * Iterates through all points without allocating intermediate objects.
     */
    inline fun forEachPoint(scale: Double = 1000.0, action: (lat: Double, lon: Double) -> Unit) {
        var i = 0
        while (i < data.size) {
            action(data[i] / scale, data[i + 1] / scale)
            i += 2
        }
    }

    /**
     * Converts to a standard List<LatLon> for third-party map layers (Google Maps, MapLibre, Mapbox).
     */
    fun toPoints(scale: Double = 1000.0): List<LatLon> {
        val count = pointCount
        val list = ArrayList<LatLon>(count)
        var i = 0
        while (i < data.size) {
            list.add(LatLon(data[i] / scale, data[i + 1] / scale))
            i += 2
        }
        return list
    }

    /**
     * Fast, zero-allocation Point-in-Polygon test using the Ray-Casting algorithm.
     * Evaluates directly against scaled integer coordinates (no float conversion overhead).
     */
    fun contains(lat: Double, lon: Double, scale: Double = 1000.0): Boolean {
        val targetX = (lon * scale).toInt()
        val targetY = (lat * scale).toInt()
        var inside = false
        val n = pointCount
        if (n < 3) return false

        var j = n - 1
        for (i in 0 until n) {
            val yi = data[i * 2]     // lat
            val xi = data[i * 2 + 1] // lon
            val yj = data[j * 2]     // lat
            val xj = data[j * 2 + 1] // lon

            val intersect = ((yi > targetY) != (yj > targetY)) &&
                (targetX < (xj - xi).toLong() * (targetY - yi) / (yj - yi) + xi)
            if (intersect) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * Single- or multi-polygon boundary for an administrative region or district.
 */
data class CompactPolygon(
    val rings: List<ScaledRing>
) {
    constructor(singleRing: ScaledRing) : this(listOf(singleRing))

    fun contains(lat: Double, lon: Double, scale: Double = 1000.0): Boolean {
        for (ring in rings) {
            if (ring.contains(lat, lon, scale)) return true
        }
        return false
    }

    fun toPoints(scale: Double = 1000.0): List<List<LatLon>> {
        return rings.map { it.toPoints(scale) }
    }
}

/**
 * Raw, language-agnostic boundary entry.
 * Keys are raw stems / IDs (e.g. "odeska", "nikopolskyi") matching raw telemetry or alert feeds.
 * Transliteration and UI formatting are deferred to the presentation layer.
 */
data class RegionBoundary(
    val id: String,
    val polygon: CompactPolygon
)
