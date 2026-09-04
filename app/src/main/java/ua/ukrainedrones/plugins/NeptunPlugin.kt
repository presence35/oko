package ua.ukrainedrones.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.ukrainedrones.OblastAlert
import ua.ukrainedrones.connection.ConnectionState
import ua.ukrainedrones.connection.isConnected
import ua.ukrainedrones.connection.isDegraded
import ua.ukrainedrones.connection.isPaused
import ua.ukrainedrones.connection.NeptunConnectionClient
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OperationalMode
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.SourceType
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource

class NeptunPlugin(private val client: NeptunConnectionClient) : ThreatSource {

    override val id = "neptun"
    override val name = "NEPTUN"
    override val sourceType = SourceType.WS

    private val _operationalMode = MutableStateFlow(OperationalMode.STREAMING)
    override val operationalMode: StateFlow<OperationalMode> = _operationalMode.asStateFlow()

    override val typeCatalog: Map<String, ThreatProps> = NEPTUN_TYPES

    private val _threats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun start(scope: CoroutineScope) {
        scope.launch {
            client.threats.collect { map ->
                _threats.update { map.values.toList() }
            }
        }
        scope.launch {
            client.alerts.collect { list ->
                _alerts.update { list }
            }
        }
        scope.launch {
            client.connectionState.collect { state ->
                _connectionState.update { mapConnectionState(state) }
            }
        }
        client.start()
    }

    override fun stop() {
        client.stop()
        _connectionState.value = PluginConnectionState.DISCONNECTED
        _operationalMode.value = OperationalMode.STANDBY
        _threats.value = emptyList()
        _alerts.value = emptyList()
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled) client.start() else client.stop()
    }

    private fun mapConnectionState(state: ConnectionState): PluginConnectionState = when {
        state.isPaused -> PluginConnectionState.PAUSED
        state.isDegraded -> PluginConnectionState.DEGRADED
        state.isConnected -> PluginConnectionState.CONNECTED
        state is ConnectionState.Connecting -> PluginConnectionState.CONNECTING
        else -> PluginConnectionState.OFFLINE
    }
}
