package com.presaince.oko.engine

import com.presaince.oko.ThreatType

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
