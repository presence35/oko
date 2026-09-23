package com.presaince.oko.engine

import com.presaince.oko.CityRaions

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

/** True when [token] appears in [text] delimited by word boundaries (no regex allocation). */
private fun containsWord(text: String, token: String): Boolean {
    var idx = text.indexOf(token, ignoreCase = true)
    while (idx >= 0) {
        val before = idx == 0 || !text[idx - 1].isLetterOrDigit()
        val after = idx + token.length >= text.length || !text[idx + token.length].isLetterOrDigit()
        if (before && after) return true
        idx = text.indexOf(token, idx + 1, ignoreCase = true)
    }
    return false
}

/** True when the official alert belongs to the oblast whose adjectival stem is [token]. */
fun OblastAlert.inOblast(token: String): Boolean {
    val t = token.trim()
    if (t.isEmpty()) return false
    // Prefix match handles "Харківськ" → "Харківська область"; a whole-word match handles
    // Crimea ("Крим" in "Автономна Республіка Крим") and short stems.
    if (oblast.startsWith(t, ignoreCase = true) || name.startsWith(t, ignoreCase = true) ||
        key.startsWith(t, ignoreCase = true) ||
        containsWord(oblast, t) || containsWord(name, t) || containsWord(key, t)
    ) return true

    // Special cases: Kyiv city belongs to Kyiv oblast stem ("Київськ")
    if (t.equals("Київськ", ignoreCase = true) &&
        (name.contains("Київ", ignoreCase = true) || key.contains("kyiv", ignoreCase = true) || oblast.contains("Київ", ignoreCase = true))
    ) return true
    // Sevastopol city belongs to Crimea stem ("Крим")
    if (t.equals("Крим", ignoreCase = true) &&
        (name.contains("Севастополь", ignoreCase = true) || key.contains("sevastopol", ignoreCase = true) || oblast.contains("Севастополь", ignoreCase = true))
    ) return true

    return false
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
        k == "м. київ" || n == "м. київ" || k == "київ" || n == "київ" || k == "kyiv" ||
        k == "м. севастополь" || n == "м. севастополь" || k == "севастополь" || n == "севастополь"
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
 * True when the alert's raion key/name matches the given raion name ([CityRaions]).
 */
fun OblastAlert.raionCovers(raion: String): Boolean {
    val k = key.trim().lowercase()
    val n = name.lowercase()
    val r = raion.lowercase()
    return (k.isNotEmpty() && (r.contains(k) || k.contains(r))) || n.contains(r)
}

/**
 * True when the official alert actually covers the focus city, for the "City alerts" scope.
 * First checks whether the alert covers the city's registered raion ([CityRaions]).
 * Otherwise falls back to matching direct name or a shared 4-char stem.
 * Oblast-wide alerts ([OblastAlert.isOblastWide]) cover every city, so they return true here.
 */
fun OblastAlert.coversCity(cityUa: String): Boolean {
    if (isOblastWide()) return true
    val raion = CityRaions.cityRaion[cityUa]
    if (raion != null && raionCovers(raion)) return true
    val c = cityUa.trim().lowercase()
    if (c.length < 4) return c.isNotEmpty() &&
        (key.lowercase().contains(c) || name.lowercase().contains(c))
    val stem = c.substring(0, 4)
    val k = key.lowercase()
    val n = name.lowercase()
    return k.contains(c) || n.contains(c) || k.startsWith(stem) || n.startsWith(stem)
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