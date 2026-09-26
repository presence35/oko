package com.odesaplay.oko.engine

import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.ThreatTypeCatalog
import com.odesaplay.oko.ThreatTypeInfo

fun String.toThreatType(): ThreatType = ThreatType.fromApi(this)

fun ThreatType.toEngineString(): String = when (this) {
    ThreatType.SHAHED -> "shahed"
    ThreatType.FPV_LOITERING -> "fpv"
    ThreatType.CRUISE_MISSILE -> "cruise"
    ThreatType.BALLISTIC -> "ballistic"
    ThreatType.KAB -> "kab"
    ThreatType.AVIATION -> "aviation"
    ThreatType.RECON -> "recon"
    ThreatType.UNKNOWN -> "unknown"
}

fun threatTypeInfoByString(type: String): ThreatTypeInfo? =
    ThreatTypeCatalog.INFO[type.toThreatType()]

fun isFastType(type: ThreatType, catalog: Map<String, ThreatProps> = emptyMap()): Boolean =
    catalog[type.apiKey]?.isFast ?: DEFAULT_THREAT_PROPS.isFast

fun typicalSpeedKmh(type: ThreatType, catalog: Map<String, ThreatProps> = emptyMap()): Double? =
    catalog[type.apiKey]?.nominalSpeedMps?.times(3.6)
