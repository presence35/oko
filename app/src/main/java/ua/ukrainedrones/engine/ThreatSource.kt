package ua.ukrainedrones.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import ua.ukrainedrones.OblastAlert

interface ThreatSource {
    val id: String
    val name: String
    val typeCatalog: Map<String, ThreatProps>
    val threats: StateFlow<List<NormalizedThreat>>
    val alerts: StateFlow<List<OblastAlert>>
    val connectionState: StateFlow<PluginConnectionState>
    fun start(scope: CoroutineScope)
    fun stop()
}
