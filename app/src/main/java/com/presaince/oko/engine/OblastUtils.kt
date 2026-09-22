package com.presaince.oko.engine

import com.presaince.oko.AppLanguage
import com.presaince.oko.pick
import com.presaince.oko.Cities
import com.presaince.oko.CityRaions
import com.presaince.oko.ThreatType
import com.presaince.oko.ThreatTypeCatalog
import com.presaince.oko.Transliteration
import com.presaince.oko.community.CompactOblastBoundaries
import com.presaince.oko.community.CompactRaionBoundaries
import com.presaince.oko.isNationalMig
import com.presaince.oko.nationalMigWhereText

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
    val label = info.label(lang)
    // The national MiG carries descriptors, not places — never transliterate them as a city.
    // UA shows the plain label; EN and RU (EN text for now) show the fixed descriptor.
    if (lang.pick(false, true, true) && isNationalMig(t)) return "$label — ${nationalMigWhereText()}"
    val where = t.locality ?: t.district ?: t.region
    val whereText = where?.let { w ->
        val en = Cities.byUa[w]?.nameEn ?: Transliteration.transliterate(w)
        lang.pick(w, en, en)
    }
    return if (whereText != null) "$label — $whereText" else label
}

/** The alert's region name in the given language: UA keeps the raw server text; EN/RU
 *  transliterate (КМУ №55) so an oblast alert never leaks Cyrillic into the EN path, and
 *  "район" is TRANSLATED to "district" rather than transliterated to "raion". */
fun alertRegionName(alert: OblastAlert, lang: AppLanguage): String {
    val raw = alert.name.ifBlank { alert.oblast }.ifBlank { alert.key }
    val base = Cities.byUa[raw]?.nameEn ?: Transliteration.transliterate(raw)
    val en = base.replace("район", "district").replace("Raion", "district").replace("raion", "district")
    return lang.pick(raw, en, en)
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

/**
 * True when the city's registered raion matches any entry in [raionKeys].
 * Scoped by parent oblast so raions with identical names in different oblasts never collide.
 * Supports exact canonical pair matching and canonical-equivalent alias lookups.
 */
fun coversCityRaion(
    cityUa: String,
    oblastId: String,
    raionKeys: Set<Pair<String, String>>
): Boolean {
    if (raionKeys.isEmpty()) return false
    val rawRaion = CityRaions.cityRaion[cityUa] ?: return false
    val cityStem = Cities.cityOblast[cityUa] ?: return false
    val cityOblastId = CompactOblastBoundaries.canonicalId(cityStem) ?: return false
    if (cityOblastId != oblastId) return false
    val canonicalCityRaion = CompactRaionBoundaries.canonicalKey(rawRaion) ?: return false
    if ((oblastId to canonicalCityRaion) in raionKeys) return true
    return raionKeys.any { (alertOblastId, alertRaion) ->
        alertOblastId == oblastId && (
            alertRaion.equals(canonicalCityRaion, ignoreCase = true) ||
            CompactRaionBoundaries.canonicalKey(alertRaion) == canonicalCityRaion
        )
    }
}
