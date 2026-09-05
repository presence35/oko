package ua.ukrainedrones.engine

import ua.ukrainedrones.AppLanguage
import ua.ukrainedrones.Cities
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.Transliteration

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

data class OblastMatch(
    val stem: String,
    val nameUa: String,
    val nameEn: String
)
