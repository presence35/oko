package com.odesaplay.oko.engine

import com.odesaplay.oko.Cities
import com.odesaplay.oko.community.CompactOblastBoundaries
import com.odesaplay.oko.community.CompactRaionBoundaries
import com.odesaplay.oko.normalizePlace

/** Severity a zone/threat evaluation resolves to for alert routing. Mirrors [OblastAlert.level]'s
 *  two real values (red = air-raid siren, yellow = tactical/artillery warning) plus NONE for no alert.
 *  Used for alert gating in [AlertService] and carried on the debug audit trail
 *  ([DebugLogEntry.level]) for official-alert rows. */
enum class AlertLevel {
    /** No active alert at this point. */
    NONE,

    /** Tactical / artillery warning — visual + chime, not a full air-raid siren.
     *  Maps to [OblastAlert.level] == "yellow". */
    YELLOW,

    /** Air-raid alert — siren + banner. Maps to [OblastAlert.level] == "red". */
    RED
}

/** A regional air-raid alert as produced by any source (NEPTUN, Ubilling, Test). Source-agnostic
 *  alert currency — parsing of source-specific JSON happens in the plugins/data layer, never here. */
data class OblastAlert(
    val key: String,
    val name: String,
    val oblast: String,
    val since: String?,
    /** True when this entry covers the whole oblast (NEPTUN's `oblasts` array) rather than a
     *  single raion/city (`raions` array). Null = unknown (Ubilling/Test fallback sources) —
     *  falls back to the name heuristic in [isOblastWide]. */
    val wide: Boolean? = null,
    /** Alert severity level: "red" (air-raid alert) or "yellow" (tactical / artillery threat). Defaults to "red". */
    val level: String = "red"
)

/** Canonical oblast ids that share one air-raid coverage group: Crimea and Sevastopol ring
 *  together. Kyiv City is already merged into `kyivska` by [CompactOblastBoundaries]. */
internal val SHARED_ALERT_REGIONS: Set<String> = setOf("krym", "sevastopol")

/** True when two canonical oblast ids denote the same alert region. Exact equality, except the
 *  shared Crimea/Sevastopol group. Never a substring. */
fun sameAlertRegion(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    if (a == b) return true
    return a in SHARED_ALERT_REGIONS && b in SHARED_ALERT_REGIONS
}

/** Canonical oblast boundary id of the alert's parent region, or null when unknown. */
fun OblastAlert.canonicalOblastId(): String? =
    CompactOblastBoundaries.canonicalId(oblast)
        ?: CompactOblastBoundaries.canonicalId(key)
        ?: CompactOblastBoundaries.canonicalId(name)

/** Canonical raion boundary key named by the alert's own [OblastAlert.key], or null for
 *  wide/city-only alerts. The display name is never treated as a raion key — a bare city alert
 *  (e.g. "Бердянськ") must not resolve to its enclosing raion. */
fun OblastAlert.canonicalRaionKey(): String? =
    CompactRaionBoundaries.canonicalKey(key)

/** True when the official alert belongs to the oblast [token] (a canonical id, or a stem/name
 *  that resolves to one). Exact identity only — stems are canonicalized, never prefix-matched. */
fun OblastAlert.inOblast(token: String): Boolean {
    val raw = token.trim()
    if (raw.isEmpty()) return false
    val id = CompactOblastBoundaries.canonicalId(raw) ?: raw.lowercase()
    return sameAlertRegion(canonicalOblastId(), id)
}

/**
 * True when the alert names the whole oblast (or autonomous republic) rather than a single
 * city/raion. Oblast-wide alerts cover every city in the region: e.g. "Луганська область" or
 * "Автономна Республіка Крим" ring/lit the whole stem, not just the seat. Checks only the
 * alert's own region designation ([key]/[name]) — the `oblast` field of a raion alert names
 * its parent oblast, so it must never count here.
 */
fun OblastAlert.isOblastWide(): Boolean {
    // NEPTUN tags the whole-oblast entries explicitly; fall back to the name heuristic only
    // for sources that don't tag (Ubilling/Test).
    wide?.let { return it }
    val k = key.lowercase().trim()
    val n = name.lowercase().trim()
    if (k.contains("область") || n.contains("область") ||
        k.contains("республіка") || n.contains("республіка") ||
        k.contains("автономна") || n.contains("автономна") ||
        k == "м. київ" || n == "м. київ" || k == "київ" || n == "київ" || k == "kyiv"
    ) return true

    // Detect oblast-wide adjectival names (e.g. "Харківська", "odeska") that are not raions/hromadas
    val isSubRegion = n.contains("район") || n.contains("р-н") || n.contains("громада") || n.contains(" тг") ||
        k.contains("район") || k.contains("р-н") || k.contains("громада") || k.contains(" тг")
    if (!isSubRegion && (n.endsWith("ська") || n.endsWith("зька") || n.endsWith("цька") || k.endsWith("ska") || k.endsWith("zka") || k.endsWith("tska"))) {
        return true
    }

    return false
}

/**
 * True when the official alert actually covers [cityUa], for the "City alerts" scope.
 * Canonical identity only: same oblast (Crimea/Sevastopol merged), then the city's registered
 * raion boundary key, or an exact full-name match for a bare city alert. Oblast-wide alerts
 * cover every city in their oblast. No stem/substring matching — a 4-letter prefix like
 * "Нова Каховка" can never cover "Нова Одеса".
 */
fun OblastAlert.coversCity(cityUa: String): Boolean {
    val cityOblast = Cities.cityOblastId[cityUa] ?: return false
    val alertOblast = canonicalOblastId() ?: return false
    if (!sameAlertRegion(alertOblast, cityOblast)) return false
    if (isOblastWide()) return true
    val alertRaion = canonicalRaionKey()
    val cityRaion = Cities.cityRaionKey[cityUa]
    if (alertRaion != null && cityRaion != null && alertRaion == cityRaion) return true
    val named = Cities.cityNameToUa[normalizePlace(key)] ?: Cities.cityNameToUa[normalizePlace(name)]
    return named != null && named == cityUa
}

/**
 * The alerting raion's adjectival name (e.g. "Бердянський" from "Бердянський район"),
 * or null when the alert names a whole oblast or a bare city rather than a raion.
 * The map fills the alerting raion polygon only when this is non-null and matches a
 * boundary key. Falls back to the key when the name carries no "район" suffix; both
 * candidates must read as a Ukrainian raion adjectival (ends in "кий").
 */
fun OblastAlert.raionName(): String? {
    if (isOblastWide()) return null
    val n = name.trim().lowercase()
    val k = key.trim().lowercase()
    val fromName = n.substringBefore(" район").substringBefore(" р-н").trim()
    if (fromName.isNotEmpty() && fromName != n) return fromName.takeIf { it.endsWith("кий") }
    val fromKey = k.substringBefore(" район").substringBefore(" р-н").trim()
    return fromKey.takeIf { it.length >= 4 && it.endsWith("кий") }
}

/**
 * The single official-alert fact for the focus point: the highest severity matching
 * [token]+[scope], or NONE. Official is official — red and yellow share this path;
 * only the user notif toggles and the tint/copy differ downstream. [scope] chooses the
 * granularity: `false` = the whole oblast rings; `true` = only when the alert covers the
 * focus city by name ([OblastAlert.coversCity]). Falls back to oblast-wide matching when the
 * city name is unknown (no pin / no GPS fix), so a city-scoped user never misses an oblast that
 * can't be narrowed. Single gate shared by the UI, the notification service and the widget.
 */
data class OfficialState(
    val level: AlertLevel,
    val alert: OblastAlert?
)

fun List<OblastAlert>.officialStateFor(
    token: String?,
    cityUa: String?,
    scope: Boolean
): OfficialState {
    if (token.isNullOrBlank()) return OfficialState(AlertLevel.NONE, null)
    fun matches(level: String): OblastAlert? {
        val inScope: (OblastAlert) -> Boolean =
            if (!scope || cityUa.isNullOrBlank()) ({ a -> a.inOblast(token) })
            else ({ a -> a.inOblast(token) && a.coversCity(cityUa) })
        // Oblast-wide entries cover every city, so they match any scope; raion/city
        // entries must name the focus city when scoped. Red preferred over yellow —
        // callers see one level, never two competing facts.
        return filter { it.level == level && inScope(it) }
            .maxByOrNull { it.isOblastWide() }
    }
    matches("red")?.let { return OfficialState(AlertLevel.RED, it) }
    matches("yellow")?.let { return OfficialState(AlertLevel.YELLOW, it) }
    return OfficialState(AlertLevel.NONE, null)
}

/**
 * Whether an official alert is active for the focus point. Thin view over
 * [officialStateFor] — kept for call-site readability, never a parallel matching rule.
 */
fun officialAlertActiveFor(
    alerts: List<OblastAlert>,
    token: String?,
    cityUa: String?,
    scope: Boolean
): Boolean = alerts.officialStateFor(token, cityUa, scope).level == AlertLevel.RED

/** Whether an official *yellow* (tactical/artillery) alert is active for the focus point.
 *  Thin view over [officialStateFor] — kept for call-site readability, never a parallel
 *  matching rule. */
fun officialYellowAlertActiveFor(
    alerts: List<OblastAlert>,
    token: String?,
    cityUa: String?,
    scope: Boolean
): Boolean = alerts.officialStateFor(token, cityUa, scope).level == AlertLevel.YELLOW

/** What a latched official episode does on a new alert snapshot: hold or end. */
enum class EpisodeTransition { STAY, ENDED }

/** What a *restored* (process-lifetime-foreign) latch does when the feed says ended:
 *  fire the all-clear only for episodes observed live in this lifetime; otherwise
 *  expire silently so a stale persisted latch can never chime again. */
enum class RestoredResolution { FIRE_OFF, EXPIRE_SILENTLY, HOLD }

data class LatchedEpisode(
    val level: AlertLevel,
    val token: String,
    val since: String?,
    val city: String
) {
    fun isRawActive(alerts: List<OblastAlert>): Boolean =
        alerts.officialStateFor(token, null, false).level != AlertLevel.NONE

    /** Pure episode transition: an unready feed (no snapshot heard yet) holds the
     *  episode rather than ending it, so a cold start can never synthesize an
     *  all-clear out of the initial empty. Only a ready feed showing no raw
     *  alert ends the episode. */
    fun resolve(alertsReady: Boolean, alerts: List<OblastAlert>): EpisodeTransition =
        if (!alertsReady || isRawActive(alerts)) EpisodeTransition.STAY else EpisodeTransition.ENDED

    /** Second gate for restores: only an episode confirmed live in this lifetime may
     *  end loudly. A latch resurrected from persistence that was never observed live
     *  expires without notification, chime or log — it ended while we were dead. */
    fun resolveRestored(confirmedLive: Boolean, transition: EpisodeTransition): RestoredResolution =
        when {
            transition == EpisodeTransition.STAY -> RestoredResolution.HOLD
            confirmedLive -> RestoredResolution.FIRE_OFF
            else -> RestoredResolution.EXPIRE_SILENTLY
        }

    companion object {
        fun parse(s: String?): LatchedEpisode? {
            if (s.isNullOrBlank()) return null
            val parts = s.split('|')
            return when {
                parts.size >= 4 -> {
                    val lvl = try { AlertLevel.valueOf(parts[0].uppercase()) } catch (_: Exception) { return null }
                    val token = parts[1].trim()
                    if (token.isEmpty()) return null
                    LatchedEpisode(lvl, token, parts[2].ifBlank { null }, parts[3])
                }
                parts.size == 3 -> {
                    val token = parts[0].trim()
                    if (token.isEmpty()) return null
                    LatchedEpisode(AlertLevel.RED, token, parts[1].ifBlank { null }, parts[2])
                }
                else -> null
            }
        }
    }
}

/** Highest severity present in [alerts] for the given focus. When [cityUa] is non-null and
 *  [scope] is true, only city-covered alerts count. Compares [OblastAlert.level] strings directly
 *  (the plugin layer already normalises them to "red"/"yellow"). */
fun List<OblastAlert>.maxLevelFor(
    token: String?,
    cityUa: String?,
    scope: Boolean
): AlertLevel {
    if (token.isNullOrBlank()) return AlertLevel.NONE
    val red = alertsForLevel("red", token, cityUa, scope)
    val yellow = alertsForLevel("yellow", token, cityUa, scope)
    return when {
        red -> AlertLevel.RED
        yellow -> AlertLevel.YELLOW
        else -> AlertLevel.NONE
    }
}

private fun List<OblastAlert>.alertsForLevel(
    level: String,
    token: String,
    cityUa: String?,
    scope: Boolean
): Boolean {
    if (scope && cityUa != null) {
        return any { it.level == level && it.inOblast(token) && it.coversCity(cityUa) }
    }
    return any { it.level == level && it.inOblast(token) }
}
