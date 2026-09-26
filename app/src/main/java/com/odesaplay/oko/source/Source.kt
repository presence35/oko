package com.odesaplay.oko.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.odesaplay.oko.ThreatType
import com.odesaplay.oko.connection.ConnEvent
import com.odesaplay.oko.connection.ConnEventKind
import com.odesaplay.oko.connection.ConnRetryState
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.OblastAlert
import com.odesaplay.oko.engine.ThreatProps

/** A source's connection state, surfaced in the Logs Sources tab and the aggregate health. */
enum class SourceState {
    DISCONNECTED, CONNECTING, CONNECTED, DEGRADED, OFFLINE
}

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

/** How a source reaches the network. WS sources hold an always-on socket (FGS-safe,
 *  background monitoring). REST sources poll and are battery-managed (adaptive intervals,
 *  never constant high-rate — see [OperationalMode]). */
enum class SourceType { WS, REST }

/** What a source is doing right now, surfaced in the Logs Sources tab. */
enum class OperationalMode {
    /** Always-on stream (WS socket). */
    STREAMING,
    /** Actively fetching (REST poller engaged). */
    POLLING,
    /** Registered but not working — primary healthy, off, or waiting. */
    STANDBY
}

/** Result of a one-shot [Source.testConnection] for the Sources tab. */
data class SourceTestResult(
    val ok: Boolean,
    val summary: String
)

private val emptyRemovedFlow = MutableSharedFlow<ThreatRemoved>(replay = 0).asSharedFlow()

/**
 * A source of normalized threat/alert data. The only SPI consumers may talk to is
 * [SourceRegistry]; this interface is implemented per real feed (currently just [NeptunSource]).
 * Source-agnostic: threats/alerts travel as engine currencies ([NormalizedThreat]/[OblastAlert]),
 * never in a source-specific format.
 *
 * Multi-source merging, deduplication and takeover are deliberately deferred — for now the
 * registry passes through the enabled source(s) with the existing takeover/union merge.
 */
interface Source {
    val id: String
    val name: String
    val sourceType: SourceType
    val operationalMode: StateFlow<OperationalMode>
    val typeCatalog: Map<String, ThreatProps>
    val threats: StateFlow<List<NormalizedThreat>>
    val alerts: StateFlow<List<OblastAlert>>
    val connectionState: StateFlow<SourceState>
    fun start(scope: CoroutineScope)
    fun stop()
    /** Whether the source is enabled (Sources tab switch). False stops the source and clears
     *  its output; true restarts it. Default no-op for sources without a user-facing switch. */
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
    /** Optional override badge label shown in the Sources tab instead of the WS/REST badge. */
    val badgeLabel: String? get() = null
    /** One-shot live check for the Sources tab Test button. REST sources perform a real fetch;
     *  WS sources report their current connection + data freshness. */
    suspend fun testConnection(): SourceTestResult =
        SourceTestResult(
            ok = connectionState.value == SourceState.CONNECTED,
            summary = connectionState.value.toString()
        )

    /** Threat-resolution feed (map death animation + resolved tally). Source-agnostic. */
    val removedThreats: SharedFlow<ThreatRemoved> get() = emptyRemovedFlow
    /** Remember a user-initiated "shot" so a same-id respawn within the grace window doesn't
     *  re-alert; the source preserves the shot track across snapshots (no marker flicker). */
    fun markUserShot(id: String) {}
    fun wasUserShotRecently(id: String): Boolean = false
    /** User pressed Retry while offline. */
    fun retryNow() {}
    /** The app came to the foreground — retry if offline. */
    fun onAppForeground() {}
    /** Optional branding link shown in the Logs header (null = hide). */
    val siteUrl: String? get() = null
}

/**
 * Optional capability of a [Source]: rich reconnect diagnostics (offline milestones, retry
 * countdown, the connection-log card) surfaced through [SourceRegistry] for the Logs sheet.
 * Only the WS transport source implements it today.
 */
interface ConnectionLogSource {
    val connEvents: StateFlow<List<ConnEvent>>
    val retryState: StateFlow<ConnRetryState?>
    fun dismissLogCard()
    fun annotateConnectionLog(kind: ConnEventKind, attempt: Int? = null, delayMs: Long? = null, detail: String? = null)
    /** Mirrors which source owns the alert feed into the per-episode log entry. */
    fun setActiveAlertSource(sourceId: String?)
}
