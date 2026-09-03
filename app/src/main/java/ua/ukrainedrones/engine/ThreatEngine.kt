package ua.ukrainedrones.engine

import kotlin.math.*

enum class ThreatZone { INNER, OUTER }

data class ThreatEvaluationResult(
    val threatsInner: List<NormalizedThreat> = emptyList(),
    val threatsOuter: List<NormalizedThreat> = emptyList(),
    val zoneThreats: Map<String, ThreatZone> = emptyMap(),
    val mapThreats: List<NormalizedThreat> = emptyList(),
    val threatScores: List<Double> = emptyList(),
    val activeZone: ThreatZone? = null,
    val redCities: Set<String> = emptySet(),
    val focusOblastAlertActive: Boolean = false,
    val officialReason: String? = null,
    val reasonThreatId: String? = null,
    val threatLevel: Double = 0.0
)

data class ThreatProximity(
    val predicted: LatLng,
    val distToUserKm: Double?,
    val etaToUserMin: Double?,
    val speedKmh: Double?,
    val speedSource: SpeedSource
)

class ThreatEngine(
    private val typeCatalog: Map<String, ThreatProps> = emptyMap()
) {
    val speedCache = SpeedCache()

    fun propsFor(type: String): ThreatProps =
        typeCatalog[type] ?: DEFAULT_THREAT_PROPS

    fun evaluate(
        threats: List<NormalizedThreat>,
        focus: LatLng?,
        params: ZoneParams,
        hiddenTypes: Set<String>,
        silencedTypes: Set<String>,
        now: Long
    ): ThreatEvaluationResult {
        val inInner = mutableListOf<NormalizedThreat>()
        val inOuter = mutableListOf<NormalizedThreat>()
        val zoneThreatsMap = LinkedHashMap<String, ThreatZone>()
        val mapThreats = mutableListOf<NormalizedThreat>()
        val threatScores = mutableListOf<Double>()

        for (t in threats) {
            val props = propsFor(t.type)
            if (t.status == "resolved" || isGhost(t, props, now)) continue
            if (t.type in hiddenTypes) continue

            val stale = isStale(t, props, now)
            if (!stale) {
                speedCache.record(t.id, t.updatedAtMillis ?: now, t.lat, t.lon)
            }
            val speedPair = speedCache.estimateWithSource(t.id, t, props)
            val speed = speedPair?.first
            val predicted = speed?.let { predictPosition(t, it, props, now) }
                ?: LatLng(t.lat, t.lon)

            mapThreats.add(t.copy())

            if (stale || focus == null) continue
            if (t.advisory || t.areaOnly || t.type in silencedTypes) continue

            val tierLat = if (props.isFast) predicted.lat else t.lat
            val tierLon = if (props.isFast) predicted.lon else t.lon
            val distKm = distanceHaversine(focus.lat, focus.lon, tierLat, tierLon) / 1000.0
            val speedKmh = speed?.times(3.6)
            val tier = zoneTier(props, distKm, speedKmh, params)

            if (tier != null) {
                val eta = etaMinutes(distKm, speedKmh)
                val (redVal, yellowVal) =
                    if (props.isFast) params.fastRedMin to params.fastYellowMin
                    else params.slowRedKm to params.slowYellowKm
                threatScores.add(
                    scoreThreat(t, props, distKm, eta, redVal, yellowVal, now)
                )
                zoneThreatsMap[t.id] = tier
                when (tier) {
                    ThreatZone.INNER -> inInner.add(t)
                    ThreatZone.OUTER -> inOuter.add(t)
                }
            }
        }

        val activeZone = when {
            inInner.isNotEmpty() -> ThreatZone.INNER
            inOuter.isNotEmpty() -> ThreatZone.OUTER
            else -> null
        }

        return ThreatEvaluationResult(
            threatsInner = inInner,
            threatsOuter = inOuter,
            zoneThreats = zoneThreatsMap,
            mapThreats = mapThreats,
            threatScores = threatScores,
            activeZone = activeZone,
            threatLevel = aggregateScores(threatScores)
        )
    }

    fun zoneTier(
        props: ThreatProps,
        distKm: Double,
        speedKmh: Double?,
        params: ZoneParams
    ): ThreatZone? {
        if (distKm > props.reachKm) return null
        if (props.alwaysInnerWithinReach) return ThreatZone.INNER
        if (props.isFast) {
            val eta = etaMinutes(distKm, speedKmh) ?: return null
            return when {
                eta <= params.fastRedMin -> ThreatZone.INNER
                eta <= params.fastYellowMin -> ThreatZone.OUTER
                else -> null
            }
        }
        return when {
            distKm <= params.slowRedKm -> ThreatZone.INNER
            distKm <= params.slowYellowKm -> ThreatZone.OUTER
            else -> null
        }
    }

    fun predictPosition(
        t: NormalizedThreat,
        speedMps: Double,
        props: ThreatProps,
        nowMillis: Long
    ): LatLng? {
        if (!t.flying) return null
        val heading = motionHeading(t) ?: return null
        val confirmedAt = t.confirmedAtMillis ?: return null
        var elapsedSec = (nowMillis - confirmedAt) / 1000.0
        if (elapsedSec < 0) return null
        elapsedSec = minOf(elapsedSec, props.horizonSec)
        val dist = minOf(speedMps * elapsedSec, props.maxGhostMeters)
        val rad = Math.toRadians(heading)
        val dLat = dist * cos(rad) / 111_320.0
        val dLon = dist * sin(rad) / (111_320.0 * cos(Math.toRadians(t.lat)).coerceAtLeast(0.01))
        return LatLng(t.lat + dLat, t.lon + dLon)
    }

    fun motionHeading(t: NormalizedThreat): Double? =
        t.bearingDeg ?: t.heading ?: speedCache.measuredHeading(t.id)

    fun courseDeg(t: NormalizedThreat): Double =
        motionHeading(t) ?: fallbackCourse(t.id)

    fun isStale(t: NormalizedThreat, props: ThreatProps, now: Long): Boolean =
        t.status == "stale" || isExpired(t, props, now)

    fun isExpired(t: NormalizedThreat, props: ThreatProps, now: Long): Boolean {
        val updated = t.updatedAtMillis ?: t.confirmedAtMillis ?: return false
        return now - updated > props.staleAfterMs
    }

    fun isGhost(t: NormalizedThreat, props: ThreatProps, now: Long): Boolean {
        val updated = t.updatedAtMillis ?: t.confirmedAtMillis ?: return false
        return now - updated > props.staleAfterMs + props.ghostCapMs
    }

    fun computeProximity(
        t: NormalizedThreat?,
        focus: LatLng?,
        params: ZoneParams,
        now: Long
    ): ThreatProximity? {
        if (t == null || t.areaOnly) return null
        val props = propsFor(t.type)
        speedCache.record(t.id, t.updatedAtMillis ?: now, t.lat, t.lon)
        val speedPair = speedCache.estimateWithSource(t.id, t, props)
        val speed = speedPair?.first
        val predicted = speed?.let { predictPosition(t, it, props, now) }
            ?: LatLng(t.lat, t.lon)
        val distUser = focus?.let {
            distanceHaversine(it.lat, it.lon, predicted.lat, predicted.lon) / 1000.0
        }
        val etaUser = if (distUser != null && speed != null && speed > 0.0) {
            distUser / (speed * 3.6) * 60.0
        } else null
        return ThreatProximity(
            predicted = predicted,
            distToUserKm = distUser,
            etaToUserMin = etaUser,
            speedKmh = speed?.times(3.6),
            speedSource = speedPair?.second ?: SpeedSource.TYPICAL
        )
    }

    fun scoreThreat(
        t: NormalizedThreat,
        props: ThreatProps,
        distKm: Double,
        etaMin: Double?,
        redKm: Int,
        yellowKm: Int,
        now: Long
    ): Double {
        val distanceFactor = when {
            distKm <= redKm -> 1.0
            distKm <= yellowKm -> 0.65
            else -> 0.0
        }
        if (distanceFactor == 0.0) return 0.0
        val baseSeverity = BASE_SEVERITY[t.type] ?: 4.0
        return (baseSeverity
            * distanceFactor
            * reliabilityFactor(t.reliability)
            * confirmFactor(t.confirmations)
            * countFactor(t.count)
            * qualityFactor(t)
            * staleFactor(t, props, now)
            * etaFactor(etaMin))
            .coerceIn(0.0, 10.0)
    }

    fun aggregateScores(scores: List<Double>): Double {
        val sorted = scores.sortedDescending()
        var total = 0.0
        val weights = listOf(1.0, 0.5, 0.25)
        for (i in 0 until minOf(sorted.size, weights.size)) total += sorted[i] * weights[i]
        return total.coerceIn(0.0, 10.0)
    }

    companion object {
        val BASE_SEVERITY: Map<String, Double> = mapOf(
            "ballistic" to 10.0,
            "cruise" to 8.0,
            "aviation" to 7.0,
            "shahed" to 5.0,
            "kab" to 4.0,
            "unknown" to 4.0,
            "fpv" to 3.0,
            "recon" to 2.0
        )

        private fun reliabilityFactor(r: String): Double = when (r.lowercase()) {
            "high" -> 1.0
            "medium" -> 0.8
            "low" -> 0.5
            else -> 0.7
        }

        private fun confirmFactor(n: Int): Double = 1.0 + 0.15 * min((n - 1).coerceAtLeast(0), 6)

        private fun countFactor(c: Int): Double = 1.0 + 0.1 * min((c - 1).coerceAtLeast(0), 8)

        private fun qualityFactor(t: NormalizedThreat): Double {
            val base = when (t.positionQuality) {
                "approx" -> 0.85
                "confirmed" -> 1.0
                else -> 0.9
            }
            val u = t.uncertaintyKm ?: return base
            val uncert = when {
                u >= 8.0 -> 0.85
                u <= 1.0 -> 1.0
                else -> 1.0 - 0.15 * ((u - 1.0) / 7.0)
            }
            return base * uncert
        }

        private fun staleFactor(t: NormalizedThreat, props: ThreatProps, now: Long): Double {
            val updated = t.updatedAtMillis ?: return 1.0
            val remaining = (1.0 - (now - updated).coerceAtLeast(0) / props.staleAfterMs.toDouble())
                .coerceIn(0.0, 1.0)
            return (0.4 + 0.6 * remaining).coerceIn(0.4, 1.0)
        }

        private fun etaFactor(etaMin: Double?): Double = when {
            etaMin == null -> 0.9
            etaMin <= 1.0 -> 1.0
            etaMin <= 3.0 -> 0.95
            etaMin <= 8.0 -> 0.85
            etaMin <= 15.0 -> 0.75
            else -> 0.7
        }

        fun etaMinutes(distKm: Double, speedKmh: Double?): Double? {
            if (speedKmh == null || speedKmh <= 0.0) return null
            return distKm / speedKmh * 60.0
        }
    }
}

data class ZoneParams(
    val slowRedKm: Int,
    val slowYellowKm: Int,
    val fastRedMin: Int,
    val fastYellowMin: Int
)
