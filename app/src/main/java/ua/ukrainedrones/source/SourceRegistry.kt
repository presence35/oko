package ua.ukrainedrones.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.ukrainedrones.ConnectionLog
import ua.ukrainedrones.connection.ConnEvent
import ua.ukrainedrones.connection.ConnEventKind
import ua.ukrainedrones.connection.ConnRetryState
import ua.ukrainedrones.connection.Monotonic
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.ThreatProps

/** Kinds of source activity surfaced in the live connection log / Sources tab. */
enum class SourceEventKind { TOGGLED_ON, TOGGLED_OFF, TAKEOVER, RESTORED }

/** One source-activity event: a toggle from the Sources tab or an alert-owner handover. */
data class SourceEvent(
    val atMillis: Long,
    val kind: SourceEventKind,
    val sourceId: String
)

private val emptyConnEvents = MutableStateFlow<List<ConnEvent>>(emptyList()).asStateFlow()
private val emptyRetryState = MutableStateFlow<ConnRetryState?>(null).asStateFlow()

/**
 * Health authority over all threat sources. Owns the merged feeds and the aggregate
 * connection state; the single public API consumers (MainViewModel, AlertService, Widget,
 * Logs) may read for data + health. Consumers never touch a specific source's internals.
 *
 * Multi-source merging, dedup and conflict resolution are explicitly deferred. For launch there
 * is exactly one real source (NEPTUN), so the existing **takeover** merge degenerates to a
 * pass-through of the single enabled source while preserving the aggregate reliability
 * behaviors (degraded/offline escalation, threat-data staleness).
 */
class SourceRegistry {

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _allThreats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    val allThreats: StateFlow<List<NormalizedThreat>> = _allThreats.asStateFlow()

    private val _allAlerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    val allAlerts: StateFlow<List<OblastAlert>> = _allAlerts.asStateFlow()

    private val _connectionState = MutableStateFlow(SourceState.DISCONNECTED)
    val connectionState: StateFlow<SourceState> = _connectionState.asStateFlow()

    /** Per-source connection state, keyed by source id (Sources tab). */
    private val _perSourceState = MutableStateFlow<Map<String, SourceState>>(emptyMap())
    val perSourceState: StateFlow<Map<String, SourceState>> = _perSourceState.asStateFlow()

    /** True when a WS source is actually delivering its live feed (CONNECTED). REST sources
     *  poll while this is false — a silent (DEGRADED) or disabled WS source is not delivering,
     *  so the backup engages. */
    private val _wsHealthy = MutableStateFlow(false)
    val wsHealthy: StateFlow<Boolean> = _wsHealthy.asStateFlow()

    /** Feed freshness: no WS source is delivering its live feed (disabled, silent, or down).
     *  The orange "degraded" tier — a data state, immediate. */
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

    /** Aggregated threat-resolution feed (map death animation + resolved tally). */
    private val _removedThreats = MutableSharedFlow<ThreatRemoved>(extraBufferCapacity = 64)
    val removedThreats: SharedFlow<ThreatRemoved> = _removedThreats.asSharedFlow()

    /** The registered source that exposes rich reconnect diagnostics (the WS transport). */
    private var logSource: ConnectionLogSource? = null

    /** Reconnect log bridge — delegated to the log-capable source (empty when none). */
    val connEvents: StateFlow<List<ConnEvent>>
        get() = logSource?.connEvents ?: emptyConnEvents

    val retryState: StateFlow<ConnRetryState?>
        get() = logSource?.retryState ?: emptyRetryState

    /** Branding link shown in the Logs header (domain of the primary source). */
    val siteUrl: String? get() = _sources.value.firstOrNull()?.siteUrl

    fun register(source: Source, scope: CoroutineScope) {
        _sources.update { it + source }
        if (logSource == null && source is ConnectionLogSource) logSource = source
        rebuildTypeCatalog()
        source.start(scope)
        scope.launch {
            source.threats.collect { remergeThreats() }
        }
        scope.launch {
            source.alerts.collect { remergeAlerts() }
        }
        scope.launch {
            source.connectionState.collect { recheckConnection() }
        }
        scope.launch {
            source.operationalMode.collect { remergeAlerts(); recheckConnection() }
        }
        scope.launch {
            source.removedThreats.collect { _removedThreats.tryEmit(it) }
        }
        recheckConnection()
    }

    fun unregister(source: Source) {
        source.stop()
        _sources.update { it - source }
        if (logSource === source) logSource = null
        rebuildTypeCatalog()
        remergeThreats()
        remergeAlerts()
        recheckConnection()
    }

    fun setEnabled(source: Source, enabled: Boolean) {
        source.setEnabled(enabled)
        remergeThreats()
        remergeAlerts()
        recheckConnection()
        val kind = if (enabled) SourceEventKind.TOGGLED_ON else SourceEventKind.TOGGLED_OFF
        _sourceEvents.tryEmit(SourceEvent(System.currentTimeMillis(), kind, source.id))
        logSource?.annotateConnectionLog(ConnEventKind.SOURCE_TOGGLED, detail = "${if (enabled) "on" else "off"}:${source.id}")
    }

    private fun rebuildTypeCatalog() {
        val merged = LinkedHashMap<String, ThreatProps>()
        for (source in _sources.value) {
            for ((type, props) in source.typeCatalog) {
                merged.putIfAbsent(type, props)
            }
        }
        _typeCatalog.value = merged
    }

    /** Sources the user has switched on — every merge/health derivation reads this view, never
     *  the raw registration list, so a disabled source can't feed, own, or degrade anything. */
    private val enabledSources: List<Source>
        get() = _sources.value.filter { it.enabled.value }

    private fun remergeThreats() {
        _lastThreatUpdateAt.value = Monotonic.now()
        _allThreats.value = enabledSources.flatMap { it.threats.value }
    }

    /**
     * Takeover merge: when at least one source is **authoritative** (actively covering the feed
     *  right now), its snapshots are the sole truth — stale holders' held alerts are dropped so
     *  an active fallback's all-clear can't be masked. When nothing is authoritative (e.g. the
     *  primary is down and the fallback hasn't covered yet), ALL sources' alerts fill the feed
     *  so the last-known state is held rather than fabricating an all-clear.
     *
     * With a single registered source this degenerates to a plain pass-through of that source's
     * alerts. Multi-source takeover/priority logic is deferred; this existing merge is kept.
     */
    private fun remergeAlerts() {
        val active = enabledSources
        val authoritative = active.filter { it.isAuthoritativeAlertSource() }
        val ordered = if (authoritative.isNotEmpty()) authoritative else _sources.value
        val owned = LinkedHashMap<String, OblastAlert>()
        var owner: String? = null
        for (source in ordered) {
            for (alert in source.alerts.value) {
                // Dedup on the alert's OWN key (NEPTUN raion keys like "одеський" vs the
                // whole-oblast "одеська"), NOT the parent oblast — otherwise every raion inside
                // an oblast collides with the oblast's wide alert and all but one is dropped.
                val key = alert.key
                if (key !in owned) {
                    owned[key] = alert
                    if (owner == null) owner = source.id
                }
            }
        }
        val prevOwner = _activeAlertSource.value
        _allAlerts.value = owned.values.toList()
        _activeAlertSource.value = owner
        if (owner != prevOwner) {
            // Handover event: a fallback took over, or ownership returned to the primary/cleared.
            val kind = when {
                owner != null && owner != PRIMARY_SOURCE_ID -> SourceEventKind.TAKEOVER
                prevOwner != null && prevOwner != PRIMARY_SOURCE_ID -> SourceEventKind.RESTORED
                else -> null
            }
            val eventSource = owner ?: prevOwner ?: PRIMARY_SOURCE_ID
            if (kind != null) {
                _sourceEvents.tryEmit(SourceEvent(System.currentTimeMillis(), kind, eventSource))
                logSource?.let { log ->
                    when (kind) {
                        SourceEventKind.TAKEOVER -> {
                            log.setActiveAlertSource(eventSource)
                            log.annotateConnectionLog(ConnEventKind.FALLBACK_ACTIVE, detail = eventSource)
                        }
                        SourceEventKind.RESTORED -> {
                            log.setActiveAlertSource(null)
                            log.annotateConnectionLog(ConnEventKind.FALLBACK_RESTORED)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    companion object {
        private const val PRIMARY_SOURCE_ID = "neptun"

        /** How long a degraded episode must persist with no fallback coverage before it escalates
         *  to full offline (red + offline notification). Notification-timer concept only — it never
         *  gates the degraded data state. */
        const val OFFLINE_EPISODE_MS = 5 * 60_000L

        /** How long a threat-data update can be absent from ALL sources before the merged feed is
         *  treated as stale (hides the map/zone logic). */
        const val THREAT_DATA_STALE_MS = 120_000L
    }

    private fun Source.isAuthoritativeAlertSource(): Boolean = when (sourceType) {
        SourceType.WS -> {
            val s = connectionState.value
            // Only a live CONNECTED socket owns the alert feed. DEGRADED (quiet for >30s) is
            // stale data, not fresh truth — it falls back to the union-hold instead.
            s == SourceState.CONNECTED
        }
        SourceType.REST -> {
            val mode = operationalMode.value
            val conn = connectionState.value
            mode == OperationalMode.POLLING && conn != SourceState.DISCONNECTED
        }
    }

    private fun recheckConnection() {
        val map = _sources.value.associate { it.id to it.connectionState.value }
        _perSourceState.value = map
        val active = enabledSources
        _connectionState.value = active.mapNotNull { map[it.id] }
            .maxByOrNull { it.ordinal }
            ?: SourceState.DISCONNECTED
        val wsDelivering = active.any { it.sourceType == SourceType.WS && map[it.id] == SourceState.CONNECTED }
        _wsHealthy.value = wsDelivering
        _degraded.value = active.isNotEmpty() && !_wsHealthy.value
        _degradedSince.value = when {
            _wsHealthy.value -> null
            _degradedSince.value == null -> Monotonic.now()
            else -> _degradedSince.value
        }
        _coveredByFallback.value = active.any {
            it.sourceType != SourceType.WS &&
                it.operationalMode.value == OperationalMode.POLLING &&
                map[it.id] == SourceState.CONNECTED
        }
    }

    /** Offline escalation (red + offline notification): degraded past the episode grace with no
     *  fallback delivering. Consumers pass a monotonic `now` (mirror rule: derivation lives here)
     *  — [degradedSince] is stamped on the monotonic clock so a wall-clock jump can't trigger or
     *  stall the escalation. */
    fun isOffline(now: Long): Boolean {
        if (enabledSources.isEmpty()) return true
        if (!_degraded.value || _coveredByFallback.value) return false
        val since = _degradedSince.value ?: return false
        return now - since >= OFFLINE_EPISODE_MS
    }

    /** Threat data is stale only when NO source has delivered an update within the window —
     *  source-agnostic. A live source (e.g. the Test simulator) delivering fresh threats clears
     *  it, so its output is never gated behind another source's quiet feed. Monotonic `now`. */
    fun isThreatDataStale(now: Long): Boolean =
        now - _lastThreatUpdateAt.value >= THREAT_DATA_STALE_MS

    // ---- Consumer-facing control + diagnostics (delegated to the active source) ----

    fun dismissLogCard() {
        logSource?.dismissLogCard()
    }

    fun retryNow() {
        for (source in enabledSources) source.retryNow()
    }

    fun pauseRetries(minutes: Int) {
        for (source in enabledSources) source.pauseRetries(minutes)
    }

    fun onAppForeground() {
        for (source in enabledSources) source.onAppForeground()
    }

    fun markUserShot(id: String) {
        for (source in _sources.value) source.markUserShot(id)
    }

    fun wasUserShotRecently(id: String): Boolean =
        _sources.value.any { it.wasUserShotRecently(id) }
}