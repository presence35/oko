package ua.ukrainedrones.engine

import kotlin.math.*

private const val EARTH_RADIUS_M = 6_371_008.8

fun distanceHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun bearingHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(rLat2)
    val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun distanceFlat(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * 110_574.0
    val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
    return sqrt(dLat * dLat + dLon * dLon)
}

fun bearingFlat(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * 110_574.0
    val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
    val deg = Math.toDegrees(atan2(dLon, dLat))
    return (deg + 360.0) % 360.0
}
