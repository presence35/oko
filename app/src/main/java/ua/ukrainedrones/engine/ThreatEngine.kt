package ua.ukrainedrones.engine

import ua.ukrainedrones.AppLanguage
import ua.ukrainedrones.Cities
import ua.ukrainedrones.CityRaions
import ua.ukrainedrones.community.CompactRaionBoundaries
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
    val fillOblastTokens: Set<String> = emptySet(),
    val fillRaionKeys: Set<Pair<String, String>> = emptySet(),
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
        now: Long,
        alerts: List<OblastAlert> = emptyList(),
        focusToken: String? = null,
        focusCityUa: String? = null,
        cityScope: Boolean = false,
        fillRegions: Boolean = false,
        lang: AppLanguage = AppLanguage.EN
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

        // Official-alert facts: single derivation owned by the engine (mirror rule). The gate,
        // red-city labels and the human-readable reason all come from here — consumers never
        // re-implement alert matching. Orchestration (region latch, announce-once, sound policy)
        // stays in AlertService.
        val focusOblastAlertActive = officialAlertActiveFor(alerts, focusToken, focusCityUa, cityScope)
        val redCities = computeRedCities(alerts, fillRegions)
        val (fillOblastTokens, fillRaionKeys) = computeFillKeys(alerts, fillRegions)
        val activeAlert = focusToken?.let { token -> alerts.firstOrNull { it.inOblast(token) } }
        val (officialReason, reasonThreatId) = if (activeAlert != null) {
            deriveOfficialAlertReason(activeAlert, threats, focus, params, lang, now)
        } else {
            null to null
        }

        return ThreatEvaluationResult(
            threatsInner = inInner,
            threatsOuter = inOuter,
            zoneThreats = zoneThreatsMap,
            mapThreats = mapThreats,
            threatScores = threatScores,
            activeZone = activeZone,
            redCities = redCities,
            fillOblastTokens = fillOblastTokens,
            fillRaionKeys = fillRaionKeys,
            focusOblastAlertActive = focusOblastAlertActive,
            officialReason = officialReason,
            reasonThreatId = reasonThreatId,
            threatLevel = aggregateScores(threatScores)
        )
    }

    /** Cities shown red on the map — region-precise (mirrors NEPTUN):
 *  - a whole-oblast alert covers every city in the region;
 *  - a raion/city alert with the region fill ON covers only the cities in that raion
 *    ([CityRaions] membership, name-match as a last resort);
 *  - a raion/city alert with the fill OFF covers every city in the alerting oblast (broad labels
 *    stand in for the missing fill).
 *  Scope-independent, so red labels light nationwide. */
fun computeRedCities(alerts: List<OblastAlert>, fillRegions: Boolean): Set<String> {
        if (alerts.isEmpty()) return emptySet()
        return buildSet {
            for (city in Cities.ALL) {
                val token = Cities.cityOblast[city.nameUa] ?: continue
                val wide = alerts.any { it.inOblast(token) && it.isOblastWide() }
                if (wide) {
                    add(city.nameUa)
                    continue
                }
                val regionAlert = alerts.any { it.inOblast(token) && !it.isOblastWide() }
                if (!regionAlert) continue
                if (fillRegions) {
                    val regionAlerts = alerts.filter { it.inOblast(token) && !it.isOblastWide() }
                    val raion = CityRaions.cityRaion[city.nameUa]
                    val covered = regionAlerts.any { a ->
                        (raion != null && raionCovers(a, raion)) || a.coversCity(city.nameUa)
                    }
                    if (covered) add(city.nameUa)
                } else {
                    add(city.nameUa)
                }
            }
        }
    }

    /** Region-fill keys derived DIRECTLY from the alerts (mirrors NEPTUN), so the map shades
     *  exactly the regions NEPTUN names — no city-list dependency:
     *  - a whole-oblast alert shades the whole oblast ([fillOblastTokens]);
     *  - a raion-level alert shades the raion it names ([fillRaionKeys], via [raionName]).
     *  A red city is always inside one of these filled regions by construction — it only went
     *  red because its oblast/raion was alerted. Raion keys are emitted only when the raion has
     *  a boundary polygon, so the fill is real. Empty when the fill is off — broad red labels
     *  stand in for the missing fill. */
    fun computeFillKeys(
        alerts: List<OblastAlert>,
        fillRegions: Boolean
    ): Pair<Set<String>, Set<Pair<String, String>>> {
        if (!fillRegions || alerts.isEmpty()) return emptySet<String>() to emptySet<Pair<String, String>>()
        val stems = Cities.cityOblast.values
        val fillOblastTokens = buildSet {
            for (token in stems) {
                if (alerts.any { it.inOblast(token) && it.isOblastWide() }) add(token)
            }
        }
        val fillRaionKeys = buildSet {
            for (alert in alerts) {
                if (alert.isOblastWide()) continue
                val raion = alert.raionName() ?: continue
                val stem = stems.firstOrNull { alert.inOblast(it) } ?: continue
                if (CompactRaionBoundaries.forKey(stem, raion) != null) add(stem to raion)
            }
        }
        return fillOblastTokens to fillRaionKeys
    }

    /** True when the alert's raion key/name matches this city's raion ([CityRaions]). */
    private fun raionCovers(alert: OblastAlert, raion: String): Boolean {
        val key = alert.key.trim().lowercase()
        val name = alert.name.lowercase()
        val r = raion.lowercase()
        return (key.isNotEmpty() && (r.contains(key) || key.contains(r))) || name.contains(r)
    }

    /** Human-readable attribution for an active official alert: the highest-scoring live threat
     *  inside the user's configured zones, falling back to the transliterated region name when
     *  nothing is in range (or there is no focus point to judge proximity by). */
    fun deriveOfficialAlertReason(
        alert: OblastAlert,
        threats: List<NormalizedThreat>,
        focus: LatLng?,
        params: ZoneParams,
        lang: AppLanguage,
        now: Long
    ): Pair<String?, String?> {
        val token = canonicalToken(alert.oblast) ?: return null to null
        // No focus point → can't judge proximity; fall back to the alert name alone.
        if (focus == null) return alertRegionName(alert, lang) to null
        var best: NormalizedThreat? = null
        var bestDistKm = Double.MAX_VALUE
        for (t in threats) {
            if (t.status != "active" || t.advisory || t.areaOnly) continue
            if (isStale(t, propsFor(t.type), now)) continue
            if (!inOblast(t.region, t.district, t.locality, token)) continue
            val distKm = distanceFlat(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
            // Only threats inside the user's configured zones qualify as the "reason" — a drone
            // 100km away in the same oblast must not be announced as if it were local.
            val props = propsFor(t.type)
            if (zoneTier(props, distKm, t.speedKmh, params) == null) continue
            if (distKm < bestDistKm) {
                bestDistKm = distKm
                best = t
            }
        }
        return if (best != null) {
            threatBody(best, lang) to best.id
        } else {
            alertRegionName(alert, lang) to null
        }
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

    /** Whether a threat may be dead-reckoned between server fixes: it must be fresh (not stale)
     *  and the source itself reports a course (server `bearingDeg`/`heading`) with an anchor while
     *  still active. Client-side measured fix-track heading alone is never enough to move a track
     *  NEPTUN isn't moving. Shared single source for the map glide and engine prediction. */
    fun canDrift(t: NormalizedThreat, props: ThreatProps, now: Long): Boolean =
        !isStale(t, props, now) && t.flying

    fun predictPosition(
        t: NormalizedThreat,
        speedMps: Double,
        props: ThreatProps,
        nowMillis: Long
    ): LatLng? {
        // Only drift fresh, server-coursed, active tracks (see canDrift).
        if (!canDrift(t, props, nowMillis)) return null
        // Course priority: authoritative velocity bearing > reported heading. The measured
        // fix-track fallback (motionHeading) is intentionally NOT used here — a source that
        // reports no course is never made to move (plugin model is respected).
        val heading = t.bearingDeg ?: t.heading ?: return null
        // Anchor on the LATEST fix: the raw lat/lon is valid as-of updatedAt (or confirmedAt if
        // updatedAt is missing). Gliding from an old confirmedAt would over-extrapolate a track
        // whose position has been refreshed since.
        val anchor = t.updatedAtMillis ?: t.confirmedAtMillis ?: return null
        var elapsedSec = (nowMillis - anchor) / 1000.0
        if (elapsedSec < 0) return null
        elapsedSec = minOf(elapsedSec, props.horizonSec)
        // Distance-capped drift: the marker may never move more than DRIFT_MAX_METERS from its
        // last confirmed fix (nor the per-type ghost cap) — a time window alone would let a fast
        // threat appear to cross the whole country at the app's map scale.
        val dist = minOf(speedMps * elapsedSec, props.maxGhostMeters, DRIFT_MAX_METERS)
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
        /** Hard cap on dead-reckoning distance: a marker may never sit farther than this from its
         *  last confirmed fix — a "relevant distance" at the app's map scale, so drift never looks
         *  like the threat crossed the country. */
        const val DRIFT_MAX_METERS = 5_000.0

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
