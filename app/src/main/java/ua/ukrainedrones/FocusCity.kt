package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng

data class FocusCityInfo(
    val nameUa: String,
    val oblastStem: String?,
    val lat: Double,
    val lon: Double
)

object FocusCity {
    fun lookup(name: String): Pair<String, LatLng>? =
        Cities.findCity(name)?.let { it.nameUa to LatLng(it.lat, it.lon) }

    fun find(name: String): FocusCityInfo? =
        Cities.findCity(name)?.let {
            FocusCityInfo(
                nameUa = it.nameUa,
                oblastStem = Cities.cityOblast[it.nameUa],
                lat = it.lat,
                lon = it.lon
            )
        }
}
