package com.odesaplay.oko.engine

import com.odesaplay.oko.AppLanguage
import com.odesaplay.oko.pick
import com.odesaplay.oko.Cities
import com.odesaplay.oko.CityRaions
import com.odesaplay.oko.RussianToponyms
import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.Transliteration
import com.odesaplay.oko.community.CompactOblastBoundaries
import com.odesaplay.oko.community.CompactRaionBoundaries
import com.odesaplay.oko.isNationalMig
import com.odesaplay.oko.nationalMigWhereText
import com.odesaplay.oko.normalizePlace

/** Canonical oblast id for arbitrary Ukrainian place text (city name, oblast name, stem).
 *  Exact resolution only — a city is resolved by its full registered name, never a prefix. */
fun resolveOblastId(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val t = text.trim()
    Cities.cityOblastId[t]?.let { return it }
    Cities.cityNameToUa[normalizePlace(t)]?.let { ua -> Cities.cityOblastId[ua]?.let { return it } }
    return CompactOblastBoundaries.canonicalId(t)
}

fun inOblast(region: String?, district: String?, locality: String?, token: String?): Boolean {
    if (token == null) return false
    val id = CompactOblastBoundaries.canonicalId(token.trim()) ?: token.trim().lowercase()
    return (region != null && sameAlertRegion(resolveOblastId(region), id)) ||
        (district != null && sameAlertRegion(resolveOblastId(district), id)) ||
        (locality != null && sameAlertRegion(resolveOblastId(locality), id))
}

fun inFocusOblast(t: NormalizedThreat, token: String?): Boolean {
    if (token == null) return false
    return inOblast(t.region, t.district, t.locality, token)
}

/** Russian display form of arbitrary Ukrainian place text (city, raion or oblast), falling back
 *  to the original when unknown. Display-only. */
fun placeRu(text: String): String {
    RussianToponyms.city(text).let { if (it != text) return it }
    if (text.contains("район", ignoreCase = true) || text.contains("р-н", ignoreCase = true)) {
        return RussianToponyms.raion(text)
    }
    resolveOblastId(text)?.let { return RussianToponyms.oblast(it) }
    return RussianToponyms.raion(text)
}

fun threatBody(t: NormalizedThreat, lang: AppLanguage): String {
    val info = threatTypeInfoByString(t.type) ?: ThreatTypeCatalog.INFO.getValue(ThreatType.UNKNOWN)
    val label = info.label(lang)
    // The national MiG carries descriptors, not places — never transliterate them as a city.
    // UA shows the plain label; EN and RU show their fixed descriptor.
    if (lang.pick(false, true, true) && isNationalMig(t)) return "$label — ${nationalMigWhereText()}"
    val where = t.locality ?: t.district ?: t.region
    val whereText = where?.let { w ->
        val en = Cities.byUa[w]?.nameEn ?: Transliteration.transliterate(w)
        lang.pick(w, en, placeRu(w))
    }
    return if (whereText != null) "$label — $whereText" else label
}

/** The alert's region name in the given language: UA keeps the raw server text; EN transliterates
 *  (КМУ №55); RU renders a real Russian form (oblast/raion/city), never a Latin transliteration. */
fun alertRegionName(alert: OblastAlert, lang: AppLanguage): String {
    val raw = alert.name.ifBlank { alert.oblast }.ifBlank { alert.key }
    return when (lang) {
        AppLanguage.UA -> raw
        AppLanguage.EN -> {
            val base = Cities.byUa[raw]?.nameEn ?: Transliteration.transliterate(raw)
            base.replace("район", "district").replace("Raion", "district").replace("raion", "district")
        }
        AppLanguage.RU -> {
            if (raw.contains("район", ignoreCase = true) || raw.contains("р-н", ignoreCase = true)) {
                RussianToponyms.raion(raw)
            } else {
                alert.canonicalOblastId()?.let { RussianToponyms.oblast(it) } ?: placeRu(raw)
            }
        }
    }
}

fun matchOblast(lat: Double, lon: Double): OblastMatch? {
    val city = Cities.nearestCity(lat, lon) ?: return null
    val id = Cities.cityOblastId[city.nameUa] ?: return null
    return OblastMatch(id, city.nameUa, city.nameEn)
}

data class OblastMatch(
    val id: String,
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
    val cityOblastId = Cities.cityOblastId[cityUa] ?: return false
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
