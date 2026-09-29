package com.odesaplay.oko.engine

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
    val maxGhostMeters: Double,
    /** Highest speed this type can plausibly travel (m/s). A reported or measured speed above
     *  this is corrupt and is rejected by [SpeedCache]. Required — every type must declare it,
     *  so "forgot to bound it" cannot compile. Plausibility is a TYPE property, so the value
     *  belongs here (plugin-owned), exactly like [nominalSpeedMps]; the engine only applies it.
     *  For fast types use a generous ceiling (a cruise missile legitimately exceeds its cruise
     *  speed late in flight); this guards corruption, not manoeuvring. */
    val maxPlausibleSpeedMps: Double,
    /** Intrinsic danger weight (0–10) used by [ThreatEngine.scoreThreat]. Plugin-provided like
     *  every other per-type value — the engine holds no severity table of its own. */
    val baseSeverity: Double = 4.0
)

val DEFAULT_THREAT_PROPS = ThreatProps(
    isFast = false,
    reachKm = 1500.0,
    alwaysInnerWithinReach = false,
    staleAfterMs = 300_000L,
    ghostCapMs = 900_000L,
    nominalSpeedMps = null,
    horizonSec = 300.0,
    maxGhostMeters = 18_000.0,
    maxPlausibleSpeedMps = 1_000.0,
    baseSeverity = 4.0
)
