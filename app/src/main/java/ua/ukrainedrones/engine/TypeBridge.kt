package ua.ukrainedrones.engine

import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.ThreatTypeCatalog
import ua.ukrainedrones.ThreatTypeInfo

fun String.toThreatType(): ThreatType = ThreatType.fromApi(this)

fun threatTypeInfoByString(type: String): ThreatTypeInfo? =
    ThreatTypeCatalog.INFO[type.toThreatType()]

fun isFastType(type: ThreatType): Boolean =
    NEPTUN_TYPES[type.name.lowercase()]?.isFast ?: DEFAULT_THREAT_PROPS.isFast

fun typicalSpeedKmh(type: ThreatType): Double? =
    NEPTUN_TYPES[type.name.lowercase()]?.nominalSpeedMps?.times(3.6)
