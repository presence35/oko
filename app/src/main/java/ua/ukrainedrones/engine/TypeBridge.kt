package ua.ukrainedrones.engine

import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.ThreatTypeInfo

fun String.toThreatType(): ThreatType = ThreatType.fromApi(this)

fun threatTypeInfoByString(type: String): ThreatTypeInfo? =
    ThreatTypeCatalog.INFO[type.toThreatType()]

fun isFastType(type: ThreatType, catalog: Map<String, ThreatProps> = emptyMap()): Boolean =
    catalog[type.apiKey]?.isFast ?: DEFAULT_THREAT_PROPS.isFast

fun typicalSpeedKmh(type: ThreatType, catalog: Map<String, ThreatProps> = emptyMap()): Double? =
    catalog[type.apiKey]?.nominalSpeedMps?.times(3.6)
