package ua.ukrainedrones.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.ukrainedrones.OblastAlert
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource

class PluginRegistry {

    private val _plugins = MutableStateFlow<List<ThreatSource>>(emptyList())
    val plugins: StateFlow<List<ThreatSource>> = _plugins.asStateFlow()

    private val _allThreats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    val allThreats: StateFlow<List<NormalizedThreat>> = _allThreats.asStateFlow()

    private val _allAlerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    val allAlerts: StateFlow<List<OblastAlert>> = _allAlerts.asStateFlow()

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    private val _typeCatalog = MutableStateFlow<Map<String, ThreatProps>>(emptyMap())
    val typeCatalog: StateFlow<Map<String, ThreatProps>> = _typeCatalog.asStateFlow()

    fun register(plugin: ThreatSource, scope: CoroutineScope) {
        _plugins.update { it + plugin }
        rebuildTypeCatalog()
        plugin.start(scope)
        scope.launch {
            plugin.threats.collect { remergeThreats() }
        }
        scope.launch {
            plugin.alerts.collect { remergeAlerts() }
        }
        scope.launch {
            plugin.connectionState.collect { recheckConnection() }
        }
    }

    fun unregister(plugin: ThreatSource) {
        plugin.stop()
        _plugins.update { it - plugin }
        rebuildTypeCatalog()
        remergeThreats()
        remergeAlerts()
        recheckConnection()
    }

    private fun rebuildTypeCatalog() {
        val merged = LinkedHashMap<String, ThreatProps>()
        for (plugin in _plugins.value) {
            for ((type, props) in plugin.typeCatalog) {
                merged.putIfAbsent(type, props)
            }
        }
        _typeCatalog.value = merged
    }

    private fun remergeThreats() {
        val all = ArrayList<NormalizedThreat>()
        for (plugin in _plugins.value) {
            all.addAll(plugin.threats.value)
        }
        _allThreats.value = all
    }

    private fun remergeAlerts() {
        val all = ArrayList<OblastAlert>()
        for (plugin in _plugins.value) {
            all.addAll(plugin.alerts.value)
        }
        _allAlerts.value = all
    }

    private fun recheckConnection() {
        val worst = _plugins.value.maxByOrNull { it.connectionState.value.ordinal }
        _connectionState.value = worst?.connectionState?.value ?: PluginConnectionState.DISCONNECTED
    }
}
