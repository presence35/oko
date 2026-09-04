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
    /** Hard on/off from the Sources tab. Default no-op for sources with no user-facing
     *  enable switch (WS sources are driven by their own lifecycle). */
    fun setEnabled(enabled: Boolean) {}
}
