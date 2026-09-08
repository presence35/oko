package ua.ukrainedrones.engine

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
 * True when the official alert actually covers the focus city, for the "City alerts" scope.
 * NEPTUN's raion-level entries name the district (e.g. "Одеський район") while the city is
 * "Одеса" — Ukrainian adjectival stems drop the ending, so we match on a shared 4-char stem
 * rather than exact substring (a safety app may over-ring a neighbouring city, never miss one).
 * Oblast-wide alerts ([OblastAlert.isOblastWide]) cover every city, so they return true here.
 */
fun OblastAlert.coversCity(cityUa: String): Boolean {
    if (isOblastWide()) return true
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
 * Whether an official alert is active for the focus point. [scope] chooses the granularity:
 * `false` = the whole oblast rings (current behaviour); `true` = only when the alert covers the
 * focus city by name ([OblastAlert.coversCity]). Falls back to oblast-wide matching when the
 * city name is unknown (no pin / no GPS fix), so a city-scoped user never misses an oblast that
 * can't be narrowed. Single gate shared by the UI, the notification service and the widget.
 */
fun officialAlertActiveFor(
    alerts: List<OblastAlert>,
    token: String?,
    cityUa: String?,
    scope: Boolean
): Boolean {
    if (token == null) return false
    // Audio sirens only trigger for "red" level alarms (or full oblast alerts). Yellow artillery alerts remain visual/informative.
    val sirenAlerts = alerts.filter { it.level == "red" || it.isOblastWide() }
    if (!scope || cityUa.isNullOrBlank()) return sirenAlerts.any { it.inOblast(token) }
    return sirenAlerts.any { it.inOblast(token) && it.coversCity(cityUa) }
}