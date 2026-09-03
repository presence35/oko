package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng

data class FocusCityInfo(
    val nameUa: String,
    val oblastStem: String?,
    val lat: Double,
    val lon: Double
)

object FocusCity {
    fun lookup(name: String): Pair<String, LatLng>? {
        val city = Cities.byUa[name]
            ?: Cities.uaToEn.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }
                ?.key?.let { Cities.byUa[it] }
            ?: Cities.byUa.values.firstOrNull { it.nameEn.equals(name, ignoreCase = true) }
        return city?.let { it.nameUa to LatLng(it.lat, it.lon) }
    }

    fun find(name: String): FocusCityInfo? {
        val city = Cities.byUa[name]
            ?: Cities.uaToEn.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }
                ?.key?.let { Cities.byUa[it] }
            ?: Cities.byUa.values.firstOrNull { it.nameEn.equals(name, ignoreCase = true) }
        return city?.let {
            FocusCityInfo(
                nameUa = it.nameUa,
                oblastStem = Cities.cityOblast[it.nameUa],
                lat = it.lat,
                lon = it.lon
            )
        }
    }
}
