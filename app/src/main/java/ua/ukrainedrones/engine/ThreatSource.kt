package ua.ukrainedrones.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import ua.ukrainedrones.OblastAlert

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

/** Result of a one-shot [ThreatSource.testConnection] for the Sources tab. */
data class SourceTestResult(
    val ok: Boolean,
    val summary: String
)

interface ThreatSource {
    val id: String
    val name: String
    val sourceType: SourceType
    val operationalMode: StateFlow<OperationalMode>
    val typeCatalog: Map<String, ThreatProps>
    val threats: StateFlow<List<NormalizedThreat>>
    val alerts: StateFlow<List<OblastAlert>>
    val connectionState: StateFlow<PluginConnectionState>
    fun start(scope: CoroutineScope)
    fun stop()
    /** Whether the source is enabled (Sources tab switch). False stops the source and clears
     *  its alerts; true restarts it. Default no-op for sources without a user-facing switch. */
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
    /** One-shot live check for the Sources tab Test button. REST sources perform a real fetch;
     *  WS sources report their current connection + data freshness. */
    suspend fun testConnection(): SourceTestResult =
        SourceTestResult(
            ok = connectionState.value == PluginConnectionState.CONNECTED,
            summary = connectionState.value.toString()
        )
}
