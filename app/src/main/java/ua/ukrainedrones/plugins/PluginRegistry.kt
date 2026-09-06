package ua.ukrainedrones.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OperationalMode
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.SourceType
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource

/** Kinds of source activity surfaced in the live connection log / Sources tab. */
enum class SourceEventKind { TOGGLED_ON, TOGGLED_OFF, TAKEOVER, RESTORED }

/** One source-activity event: a toggle from the Sources tab or an alert-owner handover. */
data class SourceEvent(
    val atMillis: Long,
    val kind: SourceEventKind,
    val sourceId: String
)

/**
 * Health authority over all threat sources. Owns the merged feeds and the aggregate
 * connection state. REST sources are activated by [wsHealthy] (their primary is down),
 * never by observing a specific sibling plugin.
 *
 * Merge policy is **takeover**, not union: the highest-priority source that is actually
 * providing data owns each oblast. This prevents a source's held/stale alert from masking
 * a live all-clear from an active fallback (see ARCHITECTURE.md "Official alert sources").
 */
class PluginRegistry {

    private val _plugins = MutableStateFlow<List<ThreatSource>>(emptyList())
    val plugins: StateFlow<List<ThreatSource>> = _plugins.asStateFlow()

    private val _allThreats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    val allThreats: StateFlow<List<NormalizedThreat>> = _allThreats.asStateFlow()

    private val _allAlerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    val allAlerts: StateFlow<List<OblastAlert>> = _allAlerts.asStateFlow()

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    /** Per-source connection state, keyed by plugin id (Sources tab). */
    private val _perSourceState = MutableStateFlow<Map<String, PluginConnectionState>>(emptyMap())
    val perSourceState: StateFlow<Map<String, PluginConnectionState>> = _perSourceState.asStateFlow()

    /** True when a WS source is actually delivering its live feed (CONNECTED). REST sources
     *  poll while this is false — a silent (DEGRADED) or disabled WS source is not delivering,
     *  so the backup engages. */
    private val _wsHealthy = MutableStateFlow(false)
    val wsHealthy: StateFlow<Boolean> = _wsHealthy.asStateFlow()

    /** True when the primary WS feed is not delivering but a fallback source is actively
     *  covering — the app is "degraded" (less live data) rather than fully offline. */
    private val _coveredByFallback = MutableStateFlow(false)
    val coveredByFallback: StateFlow<Boolean> = _coveredByFallback.asStateFlow()

    private val _typeCatalog = MutableStateFlow<Map<String, ThreatProps>>(emptyMap())
    val typeCatalog: StateFlow<Map<String, ThreatProps>> = _typeCatalog.asStateFlow()

    /** The single source currently owning the alert feed (drives the takeover rule + Logs). */
    private val _activeAlertSource = MutableStateFlow<String?>(null)
    val activeAlertSource: StateFlow<String?> = _activeAlertSource.asStateFlow()

    /** Source-activity feed (toggles + alert-owner handovers) consumed by the live log. */
    private val _sourceEvents = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 64)
    val sourceEvents: SharedFlow<SourceEvent> = _sourceEvents.asSharedFlow()

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
        scope.launch {
            plugin.operationalMode.collect { remergeAlerts(); recheckConnection() }
        }
        recheckConnection()
    }

    fun unregister(plugin: ThreatSource) {
        plugin.stop()
        _plugins.update { it - plugin }
        rebuildTypeCatalog()
        remergeThreats()
        remergeAlerts()
        recheckConnection()
    }

    fun setEnabled(plugin: ThreatSource, enabled: Boolean) {
        plugin.setEnabled(enabled)
        remergeAlerts()
        recheckConnection()
        _sourceEvents.tryEmit(
            SourceEvent(
                atMillis = System.currentTimeMillis(),
                kind = if (enabled) SourceEventKind.TOGGLED_ON else SourceEventKind.TOGGLED_OFF,
                sourceId = plugin.id
            )
        )
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

    /**
     * Takeover merge: when at least one source is **authoritative** (actively covering the feed
     * right now), its snapshots are the sole truth — stale holders' held alerts are dropped so
     * an active fallback's all-clear can't be masked. When nothing is authoritative (e.g. the
     * primary is down and the fallback hasn't covered yet), every source's alerts fill the feed
     * so the last-known state is held rather than fabricating an all-clear.
     *
     * Authoritative: a WS source whose socket is CONNECTED/DEGRADED (fresh data), or a REST
     * source whose poller is engaged AND has actually attempted a fetch (mode POLLING + a
     * non-DISCONNECTED connection — a never-started poller is not yet authoritative, so its
     * empty snapshot doesn't wipe the held last-known feed mid-takeover).
     */
    private fun remergeAlerts() {
        val authoritative = _plugins.value.filter { it.isAuthoritativeAlertSource() }
        val ordered = if (authoritative.isNotEmpty()) authoritative
        else _plugins.value
        val owned = LinkedHashMap<String, OblastAlert>()
        var owner: String? = null
        for (plugin in ordered) {
            for (alert in plugin.alerts.value) {
                val key = alert.oblast
                if (key !in owned) {
                    owned[key] = alert
                    if (owner == null) owner = plugin.id
                }
            }
        }
        val prevOwner = _activeAlertSource.value
        _allAlerts.value = owned.values.toList()
        _activeAlertSource.value = owner
        if (owner != prevOwner) {
            // Handover event: a fallback took over, or ownership returned to the primary/cleared.
            val kind = when {
                owner != null && owner != PRIMARY_ID -> SourceEventKind.TAKEOVER
                prevOwner != null && prevOwner != PRIMARY_ID -> SourceEventKind.RESTORED
                else -> null
            }
            if (kind != null) {
                _sourceEvents.tryEmit(SourceEvent(System.currentTimeMillis(), kind, owner ?: prevOwner ?: PRIMARY_ID))
            }
        }
    }

    companion object {
        private const val PRIMARY_ID = "neptun"
    }

    private fun ThreatSource.isAuthoritativeAlertSource(): Boolean = when (sourceType) {
        SourceType.WS -> {
            val s = connectionState.value
            // Only a live CONNECTED socket owns the alert feed. DEGRADED (quiet for >30s) is
            // stale data, not fresh truth — it falls back to the union-hold instead.
            s == PluginConnectionState.CONNECTED
        }
        SourceType.REST -> {
            val mode = operationalMode.value
            val conn = connectionState.value
            mode == OperationalMode.POLLING && conn != PluginConnectionState.DISCONNECTED
        }
    }

    private fun recheckConnection() {
        val map = _plugins.value.associate { it.id to it.connectionState.value }
        _perSourceState.value = map
        _connectionState.value = worstPluginState(map)
        _wsHealthy.value = _plugins.value.any { it.isWsDelivering(map) }
        _coveredByFallback.value = computeCoveredByFallback(map)
    }

    /** A WS source is delivering only when it is enabled AND actually connected. A disabled or
     *  silent (DEGRADED) source is not delivering its live feed. */
    private fun ThreatSource.isWsDelivering(map: Map<String, PluginConnectionState>): Boolean =
        enabled.value && sourceType == SourceType.WS && map[id] == PluginConnectionState.CONNECTED

    /** Degraded (not offline): no WS source is delivering its live feed, but a non-WS fallback
     *  source is authoritative and actively covering. Offline only when nothing covers. */
    private fun computeCoveredByFallback(map: Map<String, PluginConnectionState>): Boolean {
        val wsDelivering = _plugins.value.any { it.isWsDelivering(map) }
        if (wsDelivering) return false
        return _plugins.value.any { p ->
            p.sourceType != SourceType.WS && p.isAuthoritativeAlertSource()
        }
    }

    private fun worstPluginState(map: Map<String, PluginConnectionState>): PluginConnectionState =
        _plugins.value.maxByOrNull { map[it.id]?.ordinal ?: PluginConnectionState.DISCONNECTED.ordinal }
            ?.let { map[it.id] }
            ?: PluginConnectionState.DISCONNECTED
}
