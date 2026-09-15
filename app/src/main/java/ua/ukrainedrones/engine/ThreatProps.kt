package ua.ukrainedrones.engine

/**
 * Source-agnostic per-type shape the engine operates on. The engine owns this struct
 * and its mechanics only ([ThreatEngine.isStale]/isGhost/canDrift/predictPosition take
 * props as input) — never any per-type VALUES. Every value lives in its [Source]
 * implementation (e.g. NEPTUN's catalog in NeptunSource) and reaches consumers via
 * the merged SourceRegistry.typeCatalog. Importing a concrete source catalog from
 * engine/ or ui/ is a violation.
 */
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
