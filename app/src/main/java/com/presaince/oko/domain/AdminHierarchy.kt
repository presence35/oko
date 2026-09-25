package com.presaince.oko

import com.presaince.oko.community.CompactOblastBoundaries
import com.presaince.oko.community.CompactRaionBoundaries
import com.presaince.oko.engine.LatLng

/**
 * Unified administrative mapping component for Ukraine.
 *
 * Bridges [Cities], [CityRaions], [CompactOblastBoundaries], and [CompactRaionBoundaries] into a single,
 * cohesive domain model. Guarantees consistent coordinate ordering ([LatLng] with lat, lon)
 * across both oblast and raion polygon rings. All oblast references are canonical boundary IDs.
 */
object AdminHierarchy {

    data class AdminCity(
        val nameUa: String,
        val nameEn: String,
        val nameRu: String,
        val lat: Double,
        val lon: Double,
        val tier: CityTier,
        val pop: Int,
        val raionName: String?,
        val oblastId: String
    ) {
        val location: LatLng get() = LatLng(lat, lon)

        fun name(lang: AppLanguage): String = lang.pick(nameUa, nameEn, nameRu)

        /** Boundary polygon of the enclosing raion (if known), normalized to [LatLng]. */
        fun raionPolygon(): List<List<LatLng>>? =
            raionName?.let { getRaion(it, oblastId)?.polygon() }

        /** Boundary polygon of the enclosing oblast, normalized to [LatLng]. */
        fun oblastPolygon(): List<List<LatLng>>? =
            getOblast(oblastId)?.polygon()
    }

    data class AdminRaion(
        val key: String,
        val nameUa: String,
        val nameEn: String,
        val nameRu: String,
        val oblastId: String
    ) {
        fun name(lang: AppLanguage): String = lang.pick(nameUa, nameEn, nameRu)
        /** Boundary polygon rings for this raion in normalized [LatLng] order (lat, lon). */
        fun polygon(): List<List<LatLng>>? {
            val polygon = CompactRaionBoundaries.forKey(oblastId, key) ?: return null
            return polygon.toPoints().map { ring -> ring.map { LatLng(lat = it.lat, lon = it.lon) } }
        }

        /** All cities cataloged in this raion. */
        fun cities(): List<AdminCity> =
            ALL_CITIES.filter { it.oblastId == oblastId && it.raionName?.equals(nameUa, ignoreCase = true) == true }
    }

    data class AdminOblast(
        val id: String,
        val nameUa: String,
        val nameEn: String,
        val nameRu: String
    ) {
        fun name(lang: AppLanguage): String = lang.pick(nameUa, nameEn, nameRu)
        /** Boundary polygon rings for this oblast in normalized [LatLng] order (lat, lon). */
        fun polygon(): List<List<LatLng>>? {
            val polygon = CompactOblastBoundaries.get(id) ?: return null
            return polygon.toPoints().map { ring -> ring.map { LatLng(lat = it.lat, lon = it.lon) } }
        }

        /** All raions belonging to this oblast. */
        fun raions(): List<AdminRaion> =
            ALL_RAIONS.filter { it.oblastId == id }

        /** All cities cataloged in this oblast. */
        fun cities(): List<AdminCity> =
            ALL_CITIES.filter { it.oblastId == id }
    }

    /** All cities with raion and oblast attributes. */
    val ALL_CITIES: List<AdminCity> by lazy {
        Cities.REGIONS.flatMap { region ->
            region.cities.map { city ->
                AdminCity(
                    nameUa = city.nameUa,
                    nameEn = city.nameEn,
                    nameRu = city.nameRu,
                    lat = city.lat,
                    lon = city.lon,
                    tier = city.tier,
                    pop = city.pop,
                    raionName = CityRaions.cityRaion[city.nameUa],
                    oblastId = region.id
                )
            }
        }
    }

    /** All 25 oblasts. */
    val ALL_OBLASTS: List<AdminOblast> by lazy {
        OBLAST_NAMES.map { (id, names) ->
            AdminOblast(
                id = id,
                nameUa = names.first,
                nameEn = names.second,
                nameRu = RussianToponyms.oblast(id)
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
            val id = "${city.oblastId}:$key"
            if (seen.add(id)) {
                list.add(
                    AdminRaion(
                        key = key,
                        nameUa = rName,
                        nameEn = Transliteration.transliterate(rName),
                        nameRu = RussianToponyms.raion(rName),
                        oblastId = city.oblastId
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

    private val cityByRu: Map<String, AdminCity> by lazy {
        ALL_CITIES.groupBy { it.nameRu.lowercase() }.mapValues { (_, list) -> list.maxByOrNull { it.pop }!! }
    }

    private val oblastById: Map<String, AdminOblast> by lazy {
        ALL_OBLASTS.associateBy { it.id.lowercase() }
    }

    /** Resolves a city by Ukrainian, English or Russian name. */
    fun getCity(name: String): AdminCity? {
        val trimmed = name.trim()
        return cityByUa[trimmed]
            ?: cityByEn[trimmed.lowercase()]
            ?: cityByRu[trimmed.lowercase()]
            ?: ALL_CITIES.firstOrNull {
                it.nameUa.equals(trimmed, ignoreCase = true) ||
                    it.nameEn.equals(trimmed, ignoreCase = true) ||
                    it.nameRu.equals(trimmed, ignoreCase = true)
            }
    }

    /** Resolves an oblast by its canonical id or full name (UA/EN). */
    fun getOblast(idOrName: String): AdminOblast? {
        val lower = idOrName.trim().lowercase()
        return oblastById[lower]
            ?: ALL_OBLASTS.firstOrNull {
                it.id.lowercase() == lower || lower in it.nameUa.lowercase() || lower in it.nameEn.lowercase()
            }
    }

    /** Resolves a raion by name (e.g. "Бучанський"), optionally scoped by canonical oblast id. */
    fun getRaion(raionName: String, oblastId: String? = null): AdminRaion? {
        val key = raionName.trim().lowercase()
        if (oblastId != null) {
            val match = ALL_RAIONS.firstOrNull {
                it.oblastId.equals(oblastId, ignoreCase = true) &&
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

    /** Direct map of city name (UA) to its parent canonical oblast id (e.g. "Одеса" -> "odeska"). */
    fun cityToOblast(cityName: String): String? = Cities.cityOblastId[cityName]

    private val OBLAST_NAMES: Map<String, Pair<String, String>> = mapOf(
        "vinnytska" to ("Вінницька область" to "Vinnytska oblast"),
        "volynska" to ("Волинська область" to "Volynska oblast"),
        "dnipropetrovska" to ("Дніпропетровська область" to "Dnipropetrovska oblast"),
        "donetska" to ("Донецька область" to "Donetska oblast"),
        "zhytomyrska" to ("Житомирська область" to "Zhytomyrska oblast"),
        "zakarpatska" to ("Закарпатська область" to "Zakarpatska oblast"),
        "zaporizka" to ("Запорізька область" to "Zaporizka oblast"),
        "ivano_frankivska" to ("Івано-Франківська область" to "Ivano-Frankivska oblast"),
        "kyivska" to ("Київська область" to "Kyivska oblast"),
        "kirovohradska" to ("Кіровоградська область" to "Kirovohradska oblast"),
        "luhanska" to ("Луганська область" to "Luhanska oblast"),
        "lvivska" to ("Львівська область" to "Lvivska oblast"),
        "mykolaivska" to ("Миколаївська область" to "Mykolaivska oblast"),
        "odeska" to ("Одеська область" to "Odeska oblast"),
        "poltavska" to ("Полтавська область" to "Poltavska oblast"),
        "rivnenska" to ("Рівненська область" to "Rivnenska oblast"),
        "sumska" to ("Сумська область" to "Sumska oblast"),
        "ternopilska" to ("Тернопільська область" to "Ternopilska oblast"),
        "kharkivska" to ("Харківська область" to "Kharkivska oblast"),
        "khersonska" to ("Херсонська область" to "Khersonska oblast"),
        "khmelnytska" to ("Хмельницька область" to "Khmelnytska oblast"),
        "cherkaska" to ("Черкаська область" to "Cherkaska oblast"),
        "chernivetska" to ("Чернівецька область" to "Chernivetska oblast"),
        "chernihivska" to ("Чернігівська область" to "Chernihivska oblast"),
        "krym" to ("Автономна Республіка Крим" to "Autonomous Republic of Crimea")
    )
}
