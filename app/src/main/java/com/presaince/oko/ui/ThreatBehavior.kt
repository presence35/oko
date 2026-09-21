package com.presaince.oko

import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.engine.destinationPoint
import com.presaince.oko.engine.distanceFlat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class BehaviorOutcome(
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val moving: Boolean
)

fun interface ThreatBehavior {
    fun apply(
        t: NormalizedThreat,
        engine: ThreatEngine,
        now: Long
    ): BehaviorOutcome?
}

class OrbitBehavior(
    private val redKm: Int,
    private val yellowKm: Int
) : ThreatBehavior {
    override fun apply(
        t: NormalizedThreat,
        engine: ThreatEngine,
        now: Long
    ): BehaviorOutcome? {
        val destination = orbitCenter(t) ?: return null
        if (!shouldOrbitDestination(t, destination, redKm)) {
            return BehaviorOutcome(t.lat, t.lon, engine.courseDeg(t).toFloat(), moving = false)
        }
        if (!engine.canDrift(t, engine.propsFor(t.type), now)) return null

        val angle = orbitAngle(now, t.id)
        val position = orbitPosition(destination, yellowKm * 1000.0, angle)
        return BehaviorOutcome(
            position.lat,
            position.lon,
            orbitTangentBearing(angle).toFloat(),
            moving = true
        )
    }
}

fun resolveThreatBehavior(
    engine: ThreatEngine,
    t: NormalizedThreat,
    behaviors: List<ThreatBehavior>,
    now: Long
): BehaviorOutcome {
    for (behavior in behaviors) {
        behavior.apply(t, engine, now)?.let { return it }
    }

    val props = engine.propsFor(t.type)
    if (engine.canDrift(t, props, now)) {
        val speed = engine.speedCache.estimate(t.id, t, props)
        val predicted = speed?.let { engine.predictPosition(t, it, props, now) }
        if (predicted != null) {
            return BehaviorOutcome(
                predicted.lat,
                predicted.lon,
                engine.courseDeg(t).toFloat(),
                moving = true
            )
        }
    }

    return BehaviorOutcome(t.lat, t.lon, engine.courseDeg(t).toFloat(), moving = false)
}

private fun orbitPosition(center: LatLng, radiusMeters: Double, angleRad: Double): LatLng {
    val bearing = (Math.toDegrees(angleRad) + 360.0) % 360.0
    return destinationPoint(center.lat, center.lon, radiusMeters, bearing)
}

private fun orbitCenter(nt: NormalizedThreat): LatLng? {
    if (nt.areaOnly || nt.positionQuality != "approx") return null
    val place = courseTargetPlace(nt.explanationShort) ?: return null
    return Cities.findCity(place)?.let { LatLng(it.lat, it.lon) }
}

private fun shouldOrbitDestination(nt: NormalizedThreat, destination: LatLng, redKm: Int): Boolean {
    if (nt.areaOnly || nt.positionQuality != "approx") return false
    return distanceFlat(destination.lat, destination.lon, nt.lat, nt.lon) / 1000.0 > redKm
}

private fun orbitPhase(id: String): Double {
    val deg = Math.floorMod(id.hashCode(), 360)
    return Math.toRadians(deg.toDouble())
}

internal fun orbitAngle(now: Long, id: String): Double =
    (now / 15_000.0) * 2.0 * Math.PI + orbitPhase(id)

internal fun orbitTangentBearing(angleRad: Double): Double {
    val deg = Math.toDegrees(atan2(cos(angleRad), -sin(angleRad)))
    return (deg + 360.0) % 360.0
}
