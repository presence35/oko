package com.presaince.oko

import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.engine.ThreatEvaluationResult
import com.presaince.oko.engine.ThreatProps
import com.presaince.oko.engine.ThreatZone
import com.presaince.oko.engine.ZoneParams
import com.presaince.oko.engine.bearingFlat
import com.presaince.oko.engine.destinationPoint
import com.presaince.oko.engine.distanceHaversine
import kotlin.math.abs
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

/** Half-width of the "course points at me" cone. */
private const val AIM_CONE_DEG = 45.0

/**
 * A track the source reports heading toward our area, within its reach: either the source named a
 * destination that lands in our yellow ring, or an approximate track aims its server course at the
 * focus. Source data only — no timers, no observation counting.
 */
fun isInbound(t: NormalizedThreat, focus: LatLng?, params: ZoneParams, props: ThreatProps, now: Long): Boolean {
    if (focus == null || t.positionQuality != "approx") return false
    if (t.advisory || t.areaOnly || t.status != "active") return false
    val anchor = t.updatedAtMillis ?: t.confirmedAtMillis ?: return false
    if (now - anchor > props.staleAfterMs) return false
    if (distanceHaversine(t.lat, t.lon, focus.lat, focus.lon) / 1000.0 > props.reachKm) return false
    t.destination?.let { d ->
        if (distanceHaversine(d.lat, d.lon, focus.lat, focus.lon) / 1000.0 <= params.slowYellowKm) return true
    }
    val course = t.bearingDeg ?: t.heading ?: return false
    val toFocus = bearingFlat(t.lat, t.lon, focus.lat, focus.lon)
    return abs(((toFocus - course + 540.0) % 360.0) - 180.0) <= AIM_CONE_DEG
}

/** The ring an inbound track patrols: fast tracks the red ring, slow tracks the yellow one. */
fun approachRingKm(isFast: Boolean, params: ZoneParams): Int =
    if (isFast) params.slowRedKm else params.slowYellowKm

fun stagedTier(isFast: Boolean): ThreatZone =
    if (isFast) ThreatZone.INNER else ThreatZone.OUTER

/**
 * Re-tier the engine result: an inbound track the engine already placed in a zone moves to its
 * ring tier, so a fast track still fires (red) while it circles its ring and a slow track reads
 * yellow instead of landing on the city. Out-of-range tracks are never staged. Both consumers
 * apply this to the same engine output (mirror rule); the engine stays a pure reporter.
 */
fun stageInbound(
    eval: ThreatEvaluationResult,
    threats: List<NormalizedThreat>,
    focus: LatLng?,
    params: ZoneParams,
    propsFor: (String) -> ThreatProps,
    now: Long,
    silencedTypes: Set<String> = emptySet()
): ThreatEvaluationResult {
    if (focus == null) return eval
    // Only tracks the engine already put in a zone: staging rescues a track from landing on the
    // city, it never invents a far alarm for a track still out of range.
    val inbound = threats.filter {
        it.id in eval.zoneThreats &&
            it.type !in silencedTypes &&
            isInbound(it, focus, params, propsFor(it.type), now)
    }
    if (inbound.isEmpty()) return eval
    val tiers = LinkedHashMap(eval.zoneThreats)
    inbound.forEach { tiers[it.id] = stagedTier(propsFor(it.type).isFast) }
    val inner = threats.filter { tiers[it.id] == ThreatZone.INNER }
    val outer = threats.filter { tiers[it.id] == ThreatZone.OUTER }
    return eval.copy(
        zoneThreats = tiers,
        threatsInner = inner,
        threatsOuter = outer,
        activeZone = when {
            inner.isNotEmpty() -> ThreatZone.INNER
            outer.isNotEmpty() -> ThreatZone.OUTER
            else -> null
        }
    )
}

/**
 * Patrols an inbound track around its destination (or the focus) on its ring, instead of parking
 * it on the city centre. Slow tracks circle the yellow ring, fast tracks the red.
 */
class OrbitBehavior(
    private val params: ZoneParams,
    private val focus: LatLng?
) : ThreatBehavior {
    override fun apply(
        t: NormalizedThreat,
        engine: ThreatEngine,
        now: Long
    ): BehaviorOutcome? {
        val props = engine.propsFor(t.type)
        if (!isInbound(t, focus, params, props, now)) return null
        if (!engine.canDrift(t, props, now)) return null
        val center = t.destination ?: focus ?: return null
        val angle = orbitAngle(now, t.id)
        val position = orbitPosition(center, approachRingKm(props.isFast, params) * 1000.0, angle)
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
