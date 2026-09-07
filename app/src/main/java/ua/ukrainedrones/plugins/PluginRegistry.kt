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
import ua.ukrainedrones.connection.Monotonic
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

    /** Feed freshness: no WS source is delivering its live feed (disabled, silent, or down).
     *  The orange "degraded" tier — a data state, immediate, independent of fallback coverage. */
    private val _degraded = MutableStateFlow(false)
    val degraded: StateFlow<Boolean> = _degraded.asStateFlow()

    /** When the current degraded episode began (null = healthy). Drives the offline escalation. */
    private val _degradedSince = MutableStateFlow<Long?>(null)
    val degradedSince: StateFlow<Long?> = _degradedSince.asStateFlow()

    /** True when a non-WS fallback source is actually delivering real data (POLLING + CONNECTED). */
    private val _coveredByFallback = MutableStateFlow(false)
    val coveredByFallback: StateFlow<Boolean> = _coveredByFallback.asStateFlow()

    /** Epoch ms of the last threat update delivered by ANY source (including empty snapshots).
     *  Drives the source-agnostic threat-data staleness used to gate the map/zone logic. */
    private val _lastThreatUpdateAt = MutableStateFlow(0L)
    val lastThreatUpdateAt: StateFlow<Long> = _lastThreatUpdateAt.asStateFlow()

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
        remergeThreats()
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

    /** Sources the user has switched on — every merge/health derivation reads this view, never
     *  the raw registration list, so a disabled source can't feed, own, or degrade anything. */
    private val enabledPlugins: List<ThreatSource>
        get() = _plugins.value.filter { it.enabled.value }

    private fun remergeThreats() {
        _lastThreatUpdateAt.value = Monotonic.now()
        _allThreats.value = enabledPlugins.flatMap { it.threats.value }
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
        val active = enabledPlugins
        val authoritative = active.filter { it.isAuthoritativeAlertSource() }
        val ordered = if (authoritative.isNotEmpty()) authoritative else active
        val owned = LinkedHashMap<String, OblastAlert>()
        var owner: String? = null
        for (plugin in ordered) {
            for (alert in plugin.alerts.value) {
                // Dedup on the alert's OWN key (NEPTUN raion keys like "одеський" vs the
                // whole-oblast "одеська"), NOT the parent oblast — otherwise every raion inside
                // an oblast collides with the oblast's wide alert and all but one is dropped,
                // which silently erased most region fills.
                val key = alert.key
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

        /** How long a degraded episode must persist with no fallback coverage before it escalates
         *  to full offline (red + offline notification). Notification-timer concept only — it never
         *  gates the degraded data state. */
        const val OFFLINE_EPISODE_MS = 5 * 60_000L

        /** How long a threat-data update can be absent from ALL sources before the merged feed is
         *  treated as stale (hides the map/zone logic). */
        const val THREAT_DATA_STALE_MS = 120_000L
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
        val active = enabledPlugins
        _connectionState.value = active.mapNotNull { map[it.id] }
            .maxByOrNull { it.ordinal }
            ?: PluginConnectionState.DISCONNECTED
        val wsDelivering = active.any { it.sourceType == SourceType.WS && map[it.id] == PluginConnectionState.CONNECTED }
        _wsHealthy.value = wsDelivering
        _degraded.value = !_wsHealthy.value
        _degradedSince.value = when {
            _wsHealthy.value -> null
            _degradedSince.value == null -> Monotonic.now()
            else -> _degradedSince.value
        }
        _coveredByFallback.value = active.any {
            it.sourceType != SourceType.WS &&
                it.operationalMode.value == OperationalMode.POLLING &&
                map[it.id] == PluginConnectionState.CONNECTED
        }
    }

    /** Offline escalation (red + offline notification): degraded past the episode grace with no
     *  fallback delivering. Consumers pass a monotonic `now` (mirror rule: derivation lives here)
     *  — [degradedSince] is stamped on the monotonic clock so a wall-clock jump can't trigger or
     *  stall the escalation. */
    fun isOffline(now: Long): Boolean {
        if (!_degraded.value || _coveredByFallback.value) return false
        val since = _degradedSince.value ?: return false
        return now - since >= OFFLINE_EPISODE_MS
    }

    /** Threat data is stale only when NO source has delivered an update within the window —
     *  source-agnostic. A live source (e.g. the Test simulator) delivering fresh threats clears
     *  it, so its output is never gated behind another source's quiet feed. Monotonic `now`. */
    fun isThreatDataStale(now: Long): Boolean =
        now - _lastThreatUpdateAt.value >= THREAT_DATA_STALE_MS
}
