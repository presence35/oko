package com.odesaplay.oko

import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.ThreatEngine
import com.odesaplay.oko.engine.ThreatEvaluationResult
import com.odesaplay.oko.engine.ThreatProps
import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.engine.ZoneParams
import com.odesaplay.oko.engine.armedTier
import com.odesaplay.oko.engine.bearingFlat
import com.odesaplay.oko.engine.destinationPoint
import com.odesaplay.oko.engine.distanceHaversine
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

/**
 * A track the source reports heading toward our area, still approximate. The only signal that
 * rescues a track from "parks on the city" is the source naming a destination that lands in our
 * yellow ring — a bare course-bearing is not enough (fast types reach 1500 km and would otherwise
 * orbit from anywhere in the country).
 */
fun isInbound(
    t: NormalizedThreat,
    focus: LatLng?,
    params: ZoneParams,
    props: ThreatProps,
    now: Long
): Boolean {
    if (focus == null || t.positionQuality != "approx") return false
    if (t.advisory || t.areaOnly || t.status != "active") return false
    val dest = t.destination ?: return false
    if (distanceHaversine(dest.lat, dest.lon, focus.lat, focus.lon) / 1000.0 > params.slowYellowKm) return false
    val anchor = t.updatedAtMillis ?: t.confirmedAtMillis ?: return false
    return now - anchor <= props.staleAfterMs
}

/** Inbound tracks always orbit the yellow ring so they read "coming toward you". */
fun approachRingKm(params: ZoneParams): Int = params.slowYellowKm

/** Final tier: slow inbound → OUTER, fast inbound → INNER (so it still sounds). */
fun stagedTier(isFast: Boolean): ThreatZone =
    if (isFast) ThreatZone.INNER else ThreatZone.OUTER

/**
 * The engine result plus WHICH tracks the inbound staging actually touched.
 *
 * [inboundIds] exists so the popup card never re-derives the staging decision: the card used to
 * run its own [isInbound] over the selected track, which is a second, ungated evaluation of the
 * same question (no reach limit, no silenced-type check) and could disagree with the tier the
 * service rang on. One verdict, published once.
 */
data class StagedEvaluation(
    val eval: ThreatEvaluationResult,
    val inboundIds: Set<String>
) {
    val zoneThreats: Map<String, ThreatZone> get() = eval.zoneThreats
}

/**
 * Re-tier the engine result for inbound tracks: every inbound track the engine already placed in
 * a zone moves to its ring tier (slow → OUTER, fast → INNER) while the map orbits it on the
 * yellow ring. Both consumers apply this to the same engine output (mirror rule); the engine
 * stays a pure reporter of source data.
 *
 * The staged tier is the ALARM tier and nothing more: the card keeps showing the track's real
 * predicted distance, because a zone radius is a threshold and must never become a measurement.
 */
fun stageInbound(
    eval: ThreatEvaluationResult,
    threats: List<NormalizedThreat>,
    focus: LatLng?,
    params: ZoneParams,
    propsFor: (String) -> ThreatProps,
    now: Long,
    silencedTypes: Set<String> = emptySet()
): StagedEvaluation {
    if (focus == null) return StagedEvaluation(eval, emptySet())
    val inbound = threats.filter {
        it.id in eval.zoneThreats &&
            it.type !in silencedTypes &&
            isInbound(it, focus, params, propsFor(it.type), now)
    }
    if (inbound.isEmpty()) return StagedEvaluation(eval, emptySet())

    val tiers = LinkedHashMap(eval.zoneThreats)
    inbound.forEach { tiers[it.id] = stagedTier(propsFor(it.type).isFast) }
    // The staged tier is an alarm tier, so it passes the same bell gate as the engine's lists.
    val inner = threats.filter { armedTier(tiers[it.id], propsFor(it.type).isFast, params) == ThreatZone.INNER }
    val outer = threats.filter { armedTier(tiers[it.id], propsFor(it.type).isFast, params) == ThreatZone.OUTER }
    return StagedEvaluation(
        eval = eval.copy(
            zoneThreats = tiers,
            threatsInner = inner,
            threatsOuter = outer,
            activeZone = when {
                inner.isNotEmpty() -> ThreatZone.INNER
                outer.isNotEmpty() -> ThreatZone.OUTER
                else -> null
            }
        ),
        inboundIds = inbound.mapTo(HashSet()) { it.id }
    )
}

/**
 * Patrols an inbound track around its destination on the yellow ring. A track whose raw fix is
 * still inside the red gate would otherwise park right on the city — it instead rides the ring.
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
        val focus = focus ?: return null
        val dest = t.destination ?: return null
        val props = engine.propsFor(t.type)
        if (!isInbound(t, focus, params, props, now)) return null
        // Only rescue the case this gate exists for: the raw fix is already on the city.
        if (distanceHaversine(t.lat, t.lon, dest.lat, dest.lon) / 1000.0 > params.slowRedKm) return null
        if (!engine.canDrift(t, props, now)) return null
        val angle = orbitAngle(now, t, dest)
        val position = orbitPosition(dest, approachRingKm(params) * 1000.0, angle)
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

/** Calm patrol: one slow lap per [ORBIT_PERIOD_MS] — a visible drift, never a race, and not the
 *  track's real speed. */
private const val ORBIT_PERIOD_MS = 180_000.0

/** Bearing from the destination toward the side the track is approaching from: the raw fix's
 *  direction when it is still offset, otherwise the reverse of its course (it comes from behind). */
private fun approachBearingDeg(t: NormalizedThreat, dest: LatLng): Double {
    if (distanceHaversine(dest.lat, dest.lon, t.lat, t.lon) > 500.0) {
        return bearingFlat(dest.lat, dest.lon, t.lat, t.lon)
    }
    val course = t.bearingDeg ?: t.heading ?: 0.0
    return (course + 180.0) % 360.0
}

internal fun orbitAngle(now: Long, t: NormalizedThreat, dest: LatLng): Double {
    val drift = ((now % ORBIT_PERIOD_MS.toLong()) / ORBIT_PERIOD_MS) * 2.0 * Math.PI
    return Math.toRadians(approachBearingDeg(t, dest)) + drift
}

internal fun orbitTangentBearing(angleRad: Double): Double {
    val deg = Math.toDegrees(atan2(cos(angleRad), -sin(angleRad)))
    return (deg + 360.0) % 360.0
}
