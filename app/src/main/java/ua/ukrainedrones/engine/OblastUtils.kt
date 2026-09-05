package ua.ukrainedrones.engine

import ua.ukrainedrones.AppLanguage
import ua.ukrainedrones.Cities
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.Transliteration
import ua.ukrainedrones.OblastAlert

private val oblastEngine = ThreatEngine(NEPTUN_TYPES)

fun inOblast(region: String?, district: String?, locality: String?, token: String?): Boolean {
    if (token == null) return false
    return (region != null && inOblastText(region, token)) ||
        (district != null && inOblastText(district, token)) ||
        (locality != null && inOblastText(locality, token))
}

private fun inOblastText(text: String, token: String): Boolean =
    text.startsWith(token, ignoreCase = true) || Cities.cityOblast[text] == token

fun inFocusOblast(t: NormalizedThreat, token: String?): Boolean {
    if (token == null) return false
    return inOblast(t.region, t.district, t.locality, token)
}

fun threatBody(t: NormalizedThreat, lang: AppLanguage): String {
    val info = threatTypeInfoByString(t.type) ?: ThreatTypeCatalog.INFO.getValue(ThreatType.UNKNOWN)
    val label = if (lang == AppLanguage.UA) info.labelUa else info.labelEn
    val where = t.locality ?: t.district ?: t.region
    val whereText = if (where == null) null else if (lang == AppLanguage.UA) where
    else Cities.byUa[where]?.nameEn ?: Transliteration.transliterate(where)
    return if (whereText != null) "$label — $whereText" else label
}

/** The alert's region name in the given language: UA keeps the raw server text; EN
 *  transliterates (КМУ №55) so an oblast alert never leaks Cyrillic into the EN path. */
fun alertRegionName(alert: OblastAlert, lang: AppLanguage): String {
    val raw = alert.name.ifBlank { alert.oblast }.ifBlank { alert.key }
    return if (lang == AppLanguage.UA) raw
    else Cities.byUa[raw]?.nameEn ?: Transliteration.transliterate(raw)
}

fun matchOblast(lat: Double, lon: Double): OblastMatch? {
    val city = Cities.nearestCity(lat, lon) ?: return null
    val stem = Cities.cityOblast[city.nameUa] ?: return null
    return OblastMatch(stem, city.nameUa, city.nameEn)
}

fun canonicalToken(region: String): String? {
    if (region.isBlank()) return null
    val trimmed = region.trim()
    val idx = trimmed.indexOf(' ')
    val stem = if (idx > 0) trimmed.substring(0, idx) else trimmed
    return stem.ifBlank { null }
}

fun deriveOfficialAlertReason(
    threats: List<NormalizedThreat>,
    alert: OblastAlert?,
    focus: LatLng?,
    params: ZoneParams,
    lang: AppLanguage
): Pair<String?, String?> {
    if (alert == null) return null to null
    val token = canonicalToken(alert.oblast) ?: return null to null
    val now = System.currentTimeMillis()
    // No focus point → can't judge proximity; fall back to the alert name alone.
    if (focus == null) return alertRegionName(alert, lang) to null
    var best: NormalizedThreat? = null
    var bestDistKm = Double.MAX_VALUE
    for (t in threats) {
        if (t.status != "active" || t.advisory || t.areaOnly) continue
        if (oblastEngine.isStale(t, oblastEngine.propsFor(t.type), now)) continue
        if (!inOblast(t.region, t.district, t.locality, token)) continue
        val distKm = distanceFlat(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
        // Only threats inside the user's configured zones qualify as the "reason" — a drone
        // 100km away in the same oblast must not be announced as if it were local.
        val props = oblastEngine.propsFor(t.type)
        if (oblastEngine.zoneTier(props, distKm, t.speedKmh, params) == null) continue
        if (distKm < bestDistKm) {
            bestDistKm = distKm
            best = t
        }
    }
    return if (best != null) {
        val body = threatBody(best, lang)
        body to best.id
    } else {
        alertRegionName(alert, lang) to null
    }
}

data class OblastMatch(
    val stem: String,
    val nameUa: String,
    val nameEn: String
)
