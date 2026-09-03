package ua.ukrainedrones.engine

import ua.ukrainedrones.AppLanguage
import ua.ukrainedrones.Cities
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.Transliteration
import ua.ukrainedrones.FocusCityInfo
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

fun isCityScopedSuppressed(city: FocusCityInfo, threats: List<NormalizedThreat>): Boolean {
    if (threats.isEmpty()) return false
    val stem = city.oblastStem ?: return false
    return threats.none { t ->
        t.status == "active" && !t.advisory && !t.areaOnly &&
            (t.region?.contains(stem, ignoreCase = true) == true ||
                t.locality?.contains(city.nameUa, ignoreCase = true) == true)
    }
}

fun deriveOfficialAlertReason(
    threats: List<NormalizedThreat>,
    alert: OblastAlert?,
    focus: LatLng?,
    lang: AppLanguage
): Pair<String?, String?> {
    if (alert == null) return null to null
    val token = canonicalToken(alert.oblast) ?: return null to null
    val now = System.currentTimeMillis()
    var best: NormalizedThreat? = null
    var bestScore = -1.0
    for (t in threats) {
        if (t.status != "active" || t.advisory || t.areaOnly) continue
        if (oblastEngine.isStale(t, oblastEngine.propsFor(t.type), now)) continue
        if (!inOblast(t.region, t.district, t.locality, token)) continue
        val distKm = if (focus != null) {
            distanceFlat(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
        } else null
        val score = distKm ?: 0.0
        if (score > bestScore) {
            bestScore = score
            best = t
        }
    }
    return if (best != null) {
        val body = threatBody(best, lang)
        body to best.id
    } else {
        alert.name to null
    }
}

data class OblastMatch(
    val stem: String,
    val nameUa: String,
    val nameEn: String
)
