package ua.ukrainedrones.source

import ua.ukrainedrones.ThreatType

/** A threat just disappeared from a source's feed (resolved or a remove frame) — drives the
 *  map death animation and the resolved-tally. Source-agnostic removal currency. */
data class ThreatRemoved(
    val id: String,
    val lat: Double,
    val lon: Double,
    val type: ThreatType,
    val courseDeg: Double = 0.0,
    val region: String? = null,
    val district: String? = null,
    val locality: String? = null
)

/** How long a source may re-report the same resolution before consumers treat it as a real
 *  replay (NEPTUN re-sends a resolution within a 60s grace window) rather than a fresh strike. */
const val RESOLVED_REPLAY_GRACE_MS = 60_000L