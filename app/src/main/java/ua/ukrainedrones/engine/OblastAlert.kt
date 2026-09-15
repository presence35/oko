package ua.ukrainedrones.engine

import ua.ukrainedrones.CityRaions

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
    return oblast.startsWith(t, ignoreCase = true) || name.startsWith(t, ignoreCase = true) ||
        containsWord(oblast, t) || containsWord(name, t)
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
    // for sources that don't tag (Ubilling/Test). Heuristic alone misreads e.g. "Севастополь",
    // which is oblast-wide but whose name lacks "область"/"республіка".
    wide?.let { return it }
    val k = key.lowercase()
    val n = name.lowercase()
    return k.contains("область") || n.contains("область") ||
        k.contains("республіка") || n.contains("республіка")
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