package ua.ukrainedrones.engine

data class ThreatProps(
    val isFast: Boolean,
    val reachKm: Double,
    val alwaysInnerWithinReach: Boolean,
    val staleAfterMs: Long,
    val ghostCapMs: Long,
    val nominalSpeedMps: Double?,
    val horizonSec: Double,
    val maxGhostMeters: Double
)

val DEFAULT_THREAT_PROPS = ThreatProps(
    isFast = false,
    reachKm = 1500.0,
    alwaysInnerWithinReach = false,
    staleAfterMs = 300_000L,
    ghostCapMs = 900_000L,
    nominalSpeedMps = null,
    horizonSec = 300.0,
    maxGhostMeters = 18_000.0
)

val NEPTUN_TYPES = mapOf(
    "shahed" to ThreatProps(
        isFast = false, reachKm = 1000.0, alwaysInnerWithinReach = false,
        staleAfterMs = 300_000, ghostCapMs = 900_000,
        nominalSpeedMps = 50.0, horizonSec = 300.0, maxGhostMeters = 18_000.0
    ),
    "fpv" to ThreatProps(
        isFast = false, reachKm = 40.0, alwaysInnerWithinReach = false,
        staleAfterMs = 300_000, ghostCapMs = 900_000,
        nominalSpeedMps = 33.33, horizonSec = 300.0, maxGhostMeters = 18_000.0
    ),
    "cruise" to ThreatProps(
        isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 180_000, ghostCapMs = 900_000,
        nominalSpeedMps = 236.11, horizonSec = 180.0, maxGhostMeters = 30_000.0
    ),
    "ballistic" to ThreatProps(
        isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 90_000, ghostCapMs = 900_000,
        nominalSpeedMps = 916.67, horizonSec = 90.0, maxGhostMeters = 20_000.0
    ),
    "kab" to ThreatProps(
        isFast = true, reachKm = 70.0, alwaysInnerWithinReach = false,
        staleAfterMs = 180_000, ghostCapMs = 900_000,
        nominalSpeedMps = 250.0, horizonSec = 180.0, maxGhostMeters = 10_000.0
    ),
    "aviation" to ThreatProps(
        isFast = true, reachKm = 9999.0, alwaysInnerWithinReach = true,
        staleAfterMs = 240_000, ghostCapMs = 7_200_000,
        nominalSpeedMps = 250.0, horizonSec = 240.0, maxGhostMeters = 24_000.0
    ),
    "recon" to ThreatProps(
        isFast = false, reachKm = 50.0, alwaysInnerWithinReach = false,
        staleAfterMs = 300_000, ghostCapMs = 900_000,
        nominalSpeedMps = 22.22, horizonSec = 300.0, maxGhostMeters = 12_000.0
    ),
    "unknown" to ThreatProps(
        isFast = false, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 300_000, ghostCapMs = 900_000,
        nominalSpeedMps = null, horizonSec = 240.0, maxGhostMeters = 10_000.0
    )
)
