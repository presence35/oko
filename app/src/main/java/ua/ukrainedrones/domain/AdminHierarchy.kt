package ua.ukrainedrones

import ua.ukrainedrones.community.CompactOblastBoundaries
import ua.ukrainedrones.community.CompactRaionBoundaries
import ua.ukrainedrones.engine.LatLng

/**
 * Unified administrative mapping component for Ukraine.
 *
 * Bridges [Cities], [CityRaions], [CompactOblastBoundaries], and [CompactRaionBoundaries] into a single,
 * cohesive domain model. Guarantees consistent coordinate ordering ([LatLng] with lat, lon)
 * across both oblast and raion polygon rings.
 */
object AdminHierarchy {

    data class AdminCity(
        val nameUa: String,
        val nameEn: String,
        val lat: Double,
        val lon: Double,
        val tier: CityTier,
        val pop: Int,
        val raionName: String?,
        val oblastStem: String
    ) {
        val location: LatLng get() = LatLng(lat, lon)

        /** Boundary polygon of the enclosing raion (if known), normalized to [LatLng]. */
        fun raionPolygon(): List<List<LatLng>>? =
            raionName?.let { getRaion(it, oblastStem)?.polygon() }

        /** Boundary polygon of the enclosing oblast, normalized to [LatLng]. */
        fun oblastPolygon(): List<List<LatLng>>? =
            getOblast(oblastStem)?.polygon()
    }

    data class AdminRaion(
        val key: String,
        val nameUa: String,
        val nameEn: String,
        val oblastStem: String
    ) {
        /** Boundary polygon rings for this raion in normalized [LatLng] order (lat, lon). */
        fun polygon(): List<List<LatLng>>? {
            val ring = CompactRaionBoundaries.forKey(oblastStem, key)
                ?: fallbackRaionLookup(key)
            return ring?.let { r ->
                listOf(r.toPoints().map { LatLng(lat = it.lat, lon = it.lon) })
            }
        }

        /** All cities cataloged in this raion. */
        fun cities(): List<AdminCity> =
            ALL_CITIES.filter { it.oblastStem == oblastStem && it.raionName?.equals(nameUa, ignoreCase = true) == true }
    }

    data class AdminOblast(
        val stem: String,
        val nameUa: String,
        val nameEn: String
    ) {
        /** Boundary polygon rings for this oblast in normalized [LatLng] order (lat, lon). */
        fun polygon(): List<List<LatLng>>? {
            val ring = CompactOblastBoundaries.get(stem) ?: return null
            return listOf(ring.toPoints().map { LatLng(lat = it.lat, lon = it.lon) })
        }

        /** All raions belonging to this oblast. */
        fun raions(): List<AdminRaion> =
            ALL_RAIONS.filter { it.oblastStem == stem }

        /** All cities cataloged in this oblast. */
        fun cities(): List<AdminCity> =
            ALL_CITIES.filter { it.oblastStem == stem }
    }

    /** All cities with raion and oblast attributes. */
    val ALL_CITIES: List<AdminCity> by lazy {
        Cities.REGIONS.flatMap { region ->
            region.cities.map { city ->
                AdminCity(
                    nameUa = city.nameUa,
                    nameEn = city.nameEn,
                    lat = city.lat,
                    lon = city.lon,
                    tier = city.tier,
                    pop = city.pop,
                    raionName = CityRaions.cityRaion[city.nameUa],
                    oblastStem = region.stem
                )
            }
        }
    }

    /** All 25 oblasts. */
    val ALL_OBLASTS: List<AdminOblast> by lazy {
        OBLAST_NAMES.map { (stem, names) ->
            AdminOblast(
                stem = stem,
                nameUa = names.first,
                nameEn = names.second
            )
        }
    }

    /** All distinct post-2020 raions derived from cities and boundary keys. */
    val ALL_RAIONS: List<AdminRaion> by lazy {
        val list = mutableListOf<AdminRaion>()
        val seen = mutableSetOf<String>()

        for (city in ALL_CITIES) {
            val rName = city.raionName ?: continue
            val key = rName.lowercase()
            val id = "${city.oblastStem}:$key"
            if (seen.add(id)) {
                list.add(
                    AdminRaion(
                        key = key,
                        nameUa = rName,
                        nameEn = Transliteration.transliterate(rName),
                        oblastStem = city.oblastStem
                    )
                )
            }
        }
        list
    }

    private val cityByUa: Map<String, AdminCity> by lazy {
        ALL_CITIES.groupBy { it.nameUa }.mapValues { (_, list) -> list.maxByOrNull { it.pop }!! }
    }

    private val cityByEn: Map<String, AdminCity> by lazy {
        ALL_CITIES.groupBy { it.nameEn.lowercase() }.mapValues { (_, list) -> list.maxByOrNull { it.pop }!! }
    }

    private val oblastByStem: Map<String, AdminOblast> by lazy {
        ALL_OBLASTS.associateBy { it.stem.lowercase() }
    }

    /** Resolves a city by Ukrainian or English name. */
    fun getCity(name: String): AdminCity? {
        val trimmed = name.trim()
        return cityByUa[trimmed]
            ?: cityByEn[trimmed.lowercase()]
            ?: ALL_CITIES.firstOrNull {
                it.nameUa.equals(trimmed, ignoreCase = true) || it.nameEn.equals(trimmed, ignoreCase = true)
            }
    }

    /** Resolves an oblast by its stem (e.g. "Київськ") or full name. */
    fun getOblast(stemOrName: String): AdminOblast? {
        val lower = stemOrName.trim().lowercase()
        return oblastByStem[lower]
            ?: ALL_OBLASTS.firstOrNull {
                it.stem.lowercase() in lower || lower in it.nameUa.lowercase() || lower in it.nameEn.lowercase()
            }
    }

    /** Resolves a raion by name (e.g. "Бучанський" or "бучанський"), optionally scoped by oblast stem. */
    fun getRaion(raionName: String, oblastStem: String? = null): AdminRaion? {
        val key = raionName.trim().lowercase()
        if (oblastStem != null) {
            val match = ALL_RAIONS.firstOrNull {
                it.oblastStem.equals(oblastStem, ignoreCase = true) &&
                    (it.key == key || it.nameUa.equals(key, ignoreCase = true) || it.nameEn.equals(key, ignoreCase = true))
            }
            if (match != null) return match
        }
        return ALL_RAIONS.firstOrNull {
            it.key == key || it.nameUa.equals(key, ignoreCase = true) || it.nameEn.equals(key, ignoreCase = true)
        }
    }

    /** Direct map of city name (UA) to its Raion adjectival name (e.g. "Одеса" -> "Одеський"). */
    fun cityToRaion(cityName: String): String? = CityRaions.cityRaion[cityName]

    /** Direct map of city name (UA) to its parent Oblast stem (e.g. "Одеса" -> "Одеськ"). */
    fun cityToOblast(cityName: String): String? = Cities.cityOblast[cityName]

    /**
     * Fallback lookup if a raion was misattributed to an adjacent function in [CompactRaionBoundaries]
     * (e.g. Odesa raions inside _Миколаївськ).
     */
    private fun fallbackRaionLookup(raionKey: String): ua.ukrainedrones.community.ScaledRing? {
        val allStems = CompactOblastBoundaries.allStems
        for (stem in allStems) {
            val found = CompactRaionBoundaries.forKey(stem, raionKey)
            if (found != null) return found
        }
        return null
    }

    private val OBLAST_NAMES: Map<String, Pair<String, String>> = mapOf(
        "Вінницьк" to ("Вінницька область" to "Vinnytska oblast"),
        "Волинськ" to ("Волинська область" to "Volynska oblast"),
        "Дніпропетровськ" to ("Дніпропетровська область" to "Dnipropetrovska oblast"),
        "Донецьк" to ("Донецька область" to "Donetska oblast"),
        "Житомирськ" to ("Житомирська область" to "Zhytomyrska oblast"),
        "Закарпатськ" to ("Закарпатська область" to "Zakarpatska oblast"),
        "Запорізьк" to ("Запорізька область" to "Zaporizka oblast"),
        "Івано-Франківськ" to ("Івано-Франківська область" to "Ivano-Frankivska oblast"),
        "Київськ" to ("Київська область" to "Kyivska oblast"),
        "Кіровоградськ" to ("Кіровоградська область" to "Kirovohradska oblast"),
        "Луганськ" to ("Луганська область" to "Luhanska oblast"),
        "Львівськ" to ("Львівська область" to "Lvivska oblast"),
        "Миколаївськ" to ("Миколаївська область" to "Mykolaivska oblast"),
        "Одеськ" to ("Одеська область" to "Odeska oblast"),
        "Полтавськ" to ("Полтавська область" to "Poltavska oblast"),
        "Рівненськ" to ("Рівненська область" to "Rivnenska oblast"),
        "Сумськ" to ("Сумська область" to "Sumska oblast"),
        "Тернопільськ" to ("Тернопільська область" to "Ternopilska oblast"),
        "Харківськ" to ("Харківська область" to "Kharkivska oblast"),
        "Херсонськ" to ("Херсонська область" to "Khersonska oblast"),
        "Хмельницьк" to ("Хмельницька область" to "Khmelnytska oblast"),
        "Черкаськ" to ("Черкаська область" to "Cherkaska oblast"),
        "Чернівецьк" to ("Чернівецька область" to "Chernivetska oblast"),
        "Чернігівськ" to ("Чернігівська область" to "Chernihivska oblast"),
        "Крим" to ("Автономна Республіка Крим" to "Autonomous Republic of Crimea")
    )
}
