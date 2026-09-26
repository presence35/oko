package com.presaince.oko.engine

import com.presaince.oko.AppLanguage
import com.presaince.oko.Cities
import com.presaince.oko.CityRaions
import com.presaince.oko.Reliability
import com.presaince.oko.community.CompactOblastBoundaries
import com.presaince.oko.community.CompactRaionBoundaries
import kotlin.math.*

enum class ThreatZone { INNER, OUTER }

/** Spatial hysteresis margin for zone tiers: upgrades apply immediately (safety),
 *  but a downgrade/exit only applies beyond threshold × (1 + margin). No clocks —
 *  a threat hovering on a boundary keeps its tier instead of flapping. Shared by
 *  the map and the notification service, so markers and sirens never disagree. */
const val ZONE_HYSTERESIS_MARGIN = 0.10

data class ThreatEvaluationResult(
    val threatsInner: List<NormalizedThreat> = emptyList(),
    val threatsOuter: List<NormalizedThreat> = emptyList(),
    val zoneThreats: Map<String, ThreatZone> = emptyMap(),
    val mapThreats: List<NormalizedThreat> = emptyList(),
    val threatScores: List<Double> = emptyList(),
    val activeZone: ThreatZone? = null,
    val cityAlerts: Map<String, AlertLevel> = emptyMap(),
    val fillOblastTokens: Set<String> = emptySet(),
    val fillRaionKeys: Set<Pair<String, String>> = emptySet(),
    val fillYellowOblastTokens: Set<String> = emptySet(),
    val fillYellowRaionKeys: Set<Pair<String, String>> = emptySet(),
    val focusOblastAlertActive: Boolean = false,
    /** Whether a yellow-level (tactical) official alert is active for the focus — the UI's
     *  "official yellow" trident tint. Independent of [focusOblastAlertActive] (red siren). */
    val focusOblastYellowAlertActive: Boolean = false,
    val officialReason: String? = null,
    val reasonThreatId: String? = null,
    val threatLevel: Double = 0.0
) {
    val redCities: Set<String> get() = cityAlerts.filterValues { it == AlertLevel.RED }.keys
}

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
        lang: AppLanguage = AppLanguage.EN,
        /** Previous tick's tiers (threat-id → tier). Makes downgrades/exits hold through
         *  [ZONE_HYSTERESIS_MARGIN]; pass empty to evaluate statelessly. Explicit input,
         *  same convention as [now] — keeps evaluation pure and deterministic. */
        prevTiers: Map<String, ThreatZone> = emptyMap()
    ): ThreatEvaluationResult {
        val inInner = mutableListOf<NormalizedThreat>()
        val inOuter = mutableListOf<NormalizedThreat>()
        val zoneThreatsMap = LinkedHashMap<String, ThreatZone>()
        val mapThreats = mutableListOf<NormalizedThreat>()
        val threatScores = mutableListOf<Double>()

        // Official-alert facts: single derivation owned by the engine (mirror rule). The gate and
        // the human-readable reason both come from here — consumers never re-implement alert
        // matching. Orchestration (region latch, announce-once, sound policy) stays in AlertService.
        val official = alerts.officialStateFor(focusToken, focusCityUa, cityScope)
        val focusOblastAlertActive = official.level == AlertLevel.RED
        val focusOblastYellowAlertActive = official.level == AlertLevel.YELLOW
        val activeAlert = official.alert
        val reasonToken = activeAlert?.canonicalOblastId()
        var reasonBest: NormalizedThreat? = null
        var reasonBestDistKm = Double.MAX_VALUE

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

            mapThreats.add(t)

            if (stale || focus == null) continue
            if (t.advisory || t.areaOnly) continue

            val distKm = distanceHaversine(focus.lat, focus.lon, predicted.lat, predicted.lon) / 1000.0
            val speedKmh = speed?.times(3.6)
            val tier = holdTier(zoneTier(props, distKm, speedKmh, params), prevTiers[t.id], props, distKm, speedKmh, params)

            // Attribution for an active official alert: the nearest in-zone threat of that oblast.
            // Silenced types stay eligible — the siren is already ringing, so naming its cause
            // re-alerts nobody (their marker is still on the map, just dimmed); hidden types are
            // skipped before this pass, so they can never be named.
            if (reasonToken != null && reasonEligible(t, tier, reasonToken) && distKm < reasonBestDistKm) {
                reasonBestDistKm = distKm
                reasonBest = t
            }

            // Silenced types are attributed above but never tiered into zones or scored.
            if (t.type in silencedTypes) continue

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

        // One official fact, derived once: the level views and the reason below are projections of
        // [official] (computed before the pass), never parallel matching rules.
        val cityAlerts = computeCityAlerts(alerts)
        val (fillOblastTokens, fillRaionKeys) =
            computeFillKeys(alerts.filter { it.level != "yellow" }, fillRegions = true)
        val (fillYellowOblastTokens, fillYellowRaionKeys) =
            computeFillKeys(alerts.filter { it.level == "yellow" }, fillRegions = true)
        val (officialReason, reasonThreatId) = when {
            activeAlert == null || reasonToken == null -> null to null
            reasonBest != null -> threatBody(reasonBest, lang) to reasonBest.id
            else -> alertRegionName(activeAlert, lang) to null
        }

        return ThreatEvaluationResult(
            threatsInner = inInner,
            threatsOuter = inOuter,
            zoneThreats = zoneThreatsMap,
            mapThreats = mapThreats,
            threatScores = threatScores,
            activeZone = activeZone,
            cityAlerts = cityAlerts,
            fillOblastTokens = fillOblastTokens,
            fillRaionKeys = fillRaionKeys,
            fillYellowOblastTokens = fillYellowOblastTokens,
            fillYellowRaionKeys = fillYellowRaionKeys,
            focusOblastAlertActive = focusOblastAlertActive,
            focusOblastYellowAlertActive = focusOblastYellowAlertActive,
            officialReason = officialReason,
            reasonThreatId = reasonThreatId,
            threatLevel = aggregateScores(threatScores)
        )
    }

    /** Per-city alert level — pure computation, no fillRegions parameter.
     *  - whole-oblast alert → all cities in the oblast get that level;
     *  - raion/city alert → only cities covered by [OblastAlert.coversCity];
     *  - RED takes precedence over YELLOW when multiple alerts hit the same city. */
    fun computeCityAlerts(alerts: List<OblastAlert>): Map<String, AlertLevel> {
        if (alerts.isEmpty()) return emptyMap()
        // Index by canonical oblast: `coversCity` already requires same-region identity, so
        // scanning only the city's own region group (Crimea/Sevastopol share one) is exactly
        // equivalent to the full cross product, minus the product.
        val byOblast = alerts.groupBy { it.canonicalOblastId() }
        return buildMap {
            for (city in Cities.ALL) {
                val cityOblast = Cities.cityOblastId[city.nameUa] ?: continue
                val groups = if (cityOblast in SHARED_ALERT_REGIONS) SHARED_ALERT_REGIONS
                    else setOf(cityOblast)
                var level: AlertLevel? = null
                for (group in groups) {
                    for (alert in byOblast[group] ?: continue) {
                        if (!alert.coversCity(city.nameUa)) continue
                        val alertLevel = if (alert.level.equals("yellow", true))
                            AlertLevel.YELLOW else AlertLevel.RED
                        if (level == null || alertLevel == AlertLevel.RED) level = alertLevel
                    }
                }
                if (level != null) put(city.nameUa, level)
            }
        }
    }

    /** Region-fill keys derived DIRECTLY from the alerts (mirrors NEPTUN), so the map shades
     *  exactly the regions NEPTUN names — no city-list dependency:
     *  - a whole-oblast alert shades the whole oblast ([fillOblastTokens]);
     *  - a raion-level alert shades the raion it names ([fillRaionKeys], via [raionName]).
     *  Tokens are canonical boundary IDs ([CompactOblastBoundaries.canonicalId], e.g. "odeska"),
     *  so consumers compare with exact set equality — no fuzzy matching downstream.
     *  Always computed by [evaluate] regardless of the fill toggle — consumers gate the
     *  actual map fill separately (via [MapLibreLayerManager.updateAlertRegions]).
     *  A red city is always inside one of these filled regions by construction — it only went
     *  red because its oblast/raion was alerted. Raion keys are emitted only when the raion has
     *  a boundary polygon, so the fill is real. */
    fun computeFillKeys(
        alerts: List<OblastAlert>,
        fillRegions: Boolean
    ): Pair<Set<String>, Set<Pair<String, String>>> {
        if (!fillRegions || alerts.isEmpty()) return emptySet<String>() to emptySet<Pair<String, String>>()
        val fillOblastTokens = buildSet {
            for (alert in alerts) {
                if (!alert.isOblastWide()) continue
                val id = CompactOblastBoundaries.canonicalId(alert.key)
                    ?: CompactOblastBoundaries.canonicalId(alert.name)
                    ?: CompactOblastBoundaries.canonicalId(alert.oblast)
                if (id != null) add(id)
            }
        }
        val fillRaionKeys = buildSet {
            for (alert in alerts) {
                if (alert.isOblastWide()) continue
                val oblastId = CompactOblastBoundaries.canonicalId(alert.oblast)
                    ?: CompactOblastBoundaries.canonicalId(alert.name)
                    ?: continue
                // The raion fill keys off the alert's own canonical key only. A bare city alert
                // (key/name = a city, e.g. "Бердянськ") must never resolve to its raion here.
                val raionKey = alert.canonicalRaionKey() ?: continue
                if (CompactRaionBoundaries.get(raionKey) != null) {
                    add(oblastId to raionKey)
                }
            }
        }
        return fillOblastTokens to fillRaionKeys
    }

    /** Shared eligibility rule for naming a threat as an official alert's cause: active, and
     *  inside the alert's oblast and the user's configured zones. Hidden types are filtered by
     *  the caller; silenced types are deliberately eligible — naming the cause of a siren that is
     *  already ringing is attribution, not a re-alert. */
    private fun reasonEligible(t: NormalizedThreat, tier: ThreatZone?, token: String): Boolean =
        tier != null && t.status == "active" &&
            inOblast(t.region, t.district, t.locality, token)

    /** Human-readable attribution for an active official alert: the nearest live threat inside
     *  the user's configured zones, falling back to the transliterated region name when nothing is
     *  in range (or there is no focus point to judge proximity by). Hidden types ([hiddenTypes])
     *  are never named; silenced ones are (see [reasonEligible]). Used by consumers that derive a
     *  reason for a region other than the evaluate() focus (AlertService's latched episode);
     *  evaluate() folds the same rule into its single pass. */
    fun deriveOfficialAlertReason(
        alert: OblastAlert,
        threats: List<NormalizedThreat>,
        focus: LatLng?,
        params: ZoneParams,
        lang: AppLanguage,
        now: Long,
        hiddenTypes: Set<String> = emptySet()
    ): Pair<String?, String?> {
        val token = alert.canonicalOblastId() ?: return null to null
        // No focus point → can't judge proximity; fall back to the alert name alone.
        if (focus == null) return alertRegionName(alert, lang) to null
        var best: NormalizedThreat? = null
        var bestDistKm = Double.MAX_VALUE
        for (t in threats) {
            if (t.advisory || t.areaOnly) continue
            if (t.type in hiddenTypes) continue
            val props = propsFor(t.type)
            if (isStale(t, props, now)) continue
            val distKm = distanceHaversine(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
            // Only threats inside the user's configured zones qualify as the "reason" — a drone
            // 100km away in the same oblast must not be announced as if it were local.
            val tier = zoneTier(props, distKm, t.speedKmh, params)
            if (!reasonEligible(t, tier, token)) continue
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

    /**
     * Asymmetric tier hold: upgrades pass through untouched, downgrades/exits hold the
     * previous tier while still within its threshold × (1 + [ZONE_HYSTERESIS_MARGIN]).
     * Beyond-reach always exits (no hold past the type's range). Pure, unit-tested.
     */
    internal fun holdTier(
        raw: ThreatZone?,
        prev: ThreatZone?,
        props: ThreatProps,
        distKm: Double,
        speedKmh: Double?,
        params: ZoneParams
    ): ThreatZone? {
        if (raw == ThreatZone.INNER || prev == null) return raw
        if (distKm > props.reachKm) return raw
        return when (prev) {
            ThreatZone.INNER -> if (withinRedMargin(props, distKm, speedKmh, params)) ThreatZone.INNER else raw
            ThreatZone.OUTER -> if (raw == null && withinYellowMargin(props, distKm, speedKmh, params)) ThreatZone.OUTER else raw
        }
    }

    private fun withinRedMargin(props: ThreatProps, distKm: Double, speedKmh: Double?, params: ZoneParams): Boolean {
        if (props.isFast) {
            val eta = etaMinutes(distKm, speedKmh) ?: return false
            return eta <= params.fastRedMin * (1 + ZONE_HYSTERESIS_MARGIN)
        }
        return distKm <= params.slowRedKm * (1 + ZONE_HYSTERESIS_MARGIN)
    }

    private fun withinYellowMargin(props: ThreatProps, distKm: Double, speedKmh: Double?, params: ZoneParams): Boolean {
        if (props.isFast) {
            val eta = etaMinutes(distKm, speedKmh) ?: return false
            return eta <= params.fastYellowMin * (1 + ZONE_HYSTERESIS_MARGIN)
        }
        return distKm <= params.slowYellowKm * (1 + ZONE_HYSTERESIS_MARGIN)
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
        // Aviation pins sit at airbases without fix updates; age-based expiry must never retire them.
        if (props.alwaysInnerWithinReach) return false
        val updated = t.updatedAtMillis ?: t.confirmedAtMillis ?: return false
        return now - updated > props.staleAfterMs
    }

    fun isGhost(t: NormalizedThreat, props: ThreatProps, now: Long): Boolean {
        val updated = t.updatedAtMillis ?: t.confirmedAtMillis ?: return false
        val cap = if (props.alwaysInnerWithinReach) props.ghostCapMs else props.staleAfterMs + props.ghostCapMs
        return now - updated > cap
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
        val baseSeverity = props.baseSeverity
        return (baseSeverity
            * distanceFactor
            * reliabilityFactor(Reliability.fromApi(t.reliability))
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

    /**
     * Intrinsic per-threat danger score for the popup gauge (0–10). Unlike the banner
     * aggregate in [evaluate], banner gates (stale/advisory/silenced) deliberately do not
     * apply here: a silenced type is still dangerous, and the card already shows the
     * alerts-off state via its chip. Returns 0 when there is no distance to score from.
     */
    fun cardLevel(
        t: NormalizedThreat,
        distKm: Double?,
        etaMin: Double?,
        params: ZoneParams,
        now: Long
    ): Double {
        if (distKm == null) return 0.0
        val props = propsFor(t.type)
        val (redVal, yellowVal) =
            if (props.isFast) params.fastRedMin to params.fastYellowMin
            else params.slowRedKm to params.slowYellowKm
        return scoreThreat(t, props, distKm, etaMin, redVal, yellowVal, now)
    }

    companion object {
        /** Hard cap on dead-reckoning distance: a marker may never sit farther than this from its
         *  last confirmed fix — a "relevant distance" at the app's map scale, so drift never looks
         *  like the threat crossed the country. */
        const val DRIFT_MAX_METERS = 5_000.0

        private fun reliabilityFactor(r: Reliability): Double = when (r) {
            Reliability.HIGH -> 1.0
            Reliability.MEDIUM -> 0.8
            Reliability.LOW -> 0.5
            Reliability.UNKNOWN -> 0.7
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

        fun formatEtaMinutes(min: Double): String =
            min.roundToInt().coerceAtLeast(1).toString()
    }
}

data class ZoneParams(
    val slowRedKm: Int,
    val slowYellowKm: Int,
    val fastRedMin: Int,
    val fastYellowMin: Int
)
