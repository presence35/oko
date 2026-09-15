package ua.ukrainedrones.engine

import kotlin.math.*

/**
 * Spherical geodesy utilities for Great-Circle navigation and dead-reckoning projection.
 * Implemented using double-precision trigonometric formulas on the WGS-84 reference sphere.
 */
object GeoUtils {
    const val EARTH_RADIUS_KM = 6371.0
    const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the Great-Circle distance between two coordinates using the Haversine formula.
     * @return Distance in meters.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates the initial bearing (forward azimuth) from point 1 to point 2 in degrees [0, 360).
     */
    fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val initialBearingRad = atan2(y, x)
        val degrees = Math.toDegrees(initialBearingRad)
        return (degrees + 360.0) % 360.0
    }

    /**
     * Projects a starting coordinate along a given bearing for a specific distance.
     * @return Pair of (projectedLatitude, projectedLongitude) in degrees.
     */
    fun projectCoordinate(
        lat: Double,
        lon: Double,
        distanceMeters: Double,
        bearingDegrees: Double
    ): Pair<Double, Double> {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDegrees)
        val rLat = Math.toRadians(lat)
        val rLon = Math.toRadians(lon)

        val cosAngular = cos(angularDistance)
        val sinAngular = sin(angularDistance)
        val latArgument = (sin(rLat) * cosAngular + cos(rLat) * sinAngular * cos(bearingRad)).coerceIn(-1.0, 1.0)
        val targetLatRad = asin(latArgument)
        val targetLonRad = rLon + atan2(
            sin(bearingRad) * sinAngular * cos(rLat),
            cosAngular - sin(rLat) * sin(targetLatRad)
        )

        val targetLat = Math.toDegrees(targetLatRad)
        val targetLon = ((Math.toDegrees(targetLonRad) + 540.0) % 360.0) - 180.0 // Normalize to [-180, 180]
        return Pair(targetLat, targetLon)
    }

    /**
     * Decomposes speed (km/h) and bearing into Cartesian velocity components in m/s.
     * @return Pair of (vNorth, vEast) in m/s.
     */
    fun velocityComponentsMps(speedKmh: Double, bearingDegrees: Double): Pair<Double, Double> {
        val speedMps = speedKmh / 3.6
        val bearingRad = Math.toRadians(bearingDegrees)
        val vNorth = speedMps * cos(bearingRad)
        val vEast = speedMps * sin(bearingRad)
        return Pair(vNorth, vEast)
    }
}
