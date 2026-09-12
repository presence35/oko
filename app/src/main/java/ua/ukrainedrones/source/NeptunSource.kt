package ua.ukrainedrones.source

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.ukrainedrones.ConnectionLog
import ua.ukrainedrones.connection.ConnEvent
import ua.ukrainedrones.connection.ConnEventKind
import ua.ukrainedrones.connection.ConnRetryState
import ua.ukrainedrones.connection.ConnectionState
import ua.ukrainedrones.connection.ConnectionSupervisor
import ua.ukrainedrones.connection.Monotonic
import ua.ukrainedrones.connection.NeptunDecoder
import ua.ukrainedrones.connection.WsTransport
import ua.ukrainedrones.connection.isConnected
import ua.ukrainedrones.connection.isDegraded
import ua.ukrainedrones.connection.isPaused
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.service.ServiceState

/**
 * The NEPTUN source: the only production [Source] implementation for launch. Owns the pieces
 * the old client used to — the WS transport ([WsTransport]), the frame decoder ([NeptunDecoder])
 * and the reconnect supervisor ([ConnectionSupervisor]) — but as small focused collaborators,
 * not a god object. Consumers never touch it directly; [SourceRegistry] is the only public API.
 */
class NeptunSource(private val context: Context) : Source, ConnectionLogSource {

    private val transport = WsTransport(context.applicationContext)
    private val decoder = NeptunDecoder()
    private val supervisor = ConnectionSupervisor(context.applicationContext, transport.connectionState)

    override val id = "neptun"
    override val name = "NEPTUN"
    override val sourceType = SourceType.WS
    override val typeCatalog: Map<String, ThreatProps> = NEPTUN_TYPES

    private val _operationalMode = MutableStateFlow(OperationalMode.STREAMING)
    override val operationalMode: StateFlow<OperationalMode> = _operationalMode.asStateFlow()

    private val _threats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _connectionState = MutableStateFlow(SourceState.DISCONNECTED)
    override val connectionState: StateFlow<SourceState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override val removedThreats: SharedFlow<ThreatRemoved> get() = decoder.removedThreats
    override val siteUrl: String? get() = NEPTUN_SITE_URL

    override val connEvents: StateFlow<List<ConnEvent>> get() = supervisor.connEvents
    override val retryState: StateFlow<ConnRetryState?> get() = supervisor.retryState

    init {
        transport.onWatchdogTick = { nowMono -> decoder.flushPendingAlertClear(nowMono) }
    }

    override fun start(scope: CoroutineScope) {
        scope.launch {
            val svc = ServiceState(context.applicationContext)
            transport.start(
                savedReconnectStartMs = svc.reconnectStartMillis().first(),
                savedIgnoreUntilMs = svc.ignoreRetryUntil().first()
            )
            supervisor.start()
        }
        scope.launch { frameLoop() }
        scope.launch { persistReconnectStart() }
        scope.launch {
            transport.connectionState.collect { cs ->
                _connectionState.value = mapConnectionState(cs)
                // A transport drop voids an unconfirmed alert-clear debounce.
                if (cs is ConnectionState.Offline) decoder.handleTransportDrop()
            }
        }
        scope.launch { collectThreats() }
        scope.launch { collectAlerts() }
    }

    private suspend fun frameLoop() {
        for (text in transport.frames) {
            decoder.handleFrame(text, System.currentTimeMillis(), Monotonic.now())
        }
    }

    private suspend fun collectThreats() {
        decoder.threats.collect { map ->
            _threats.update { map.values.toList() }
        }
    }

    private suspend fun collectAlerts() {
        decoder.alerts.collect { list ->
            _alerts.update { list }
        }
    }

    private suspend fun persistReconnectStart() {
        val svc = ServiceState(context.applicationContext)
        transport.connectionState.collect { cs ->
            when (cs) {
                is ConnectionState.Offline -> svc.setReconnectStartMillis(cs.reconnectStartMillis)
                is ConnectionState.Connected -> svc.setReconnectStartMillis(0L)
                else -> {}
            }
        }
    }

    override fun stop() {
        transport.close()
        supervisor.stop()
        _connectionState.value = SourceState.DISCONNECTED
        _operationalMode.value = OperationalMode.STANDBY
        _threats.value = emptyList()
        _alerts.value = emptyList()
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled) {
            transport.start()
            _operationalMode.value = OperationalMode.STREAMING
        } else {
            transport.stop()
            _connectionState.value = SourceState.DISCONNECTED
            _operationalMode.value = OperationalMode.STANDBY
            _threats.value = emptyList()
        }
    }

    override suspend fun testConnection(): SourceTestResult {
        val state = _connectionState.value
        if (state != SourceState.CONNECTED) {
            return SourceTestResult(false, "connection: $state")
        }
        val now = System.currentTimeMillis()
        val socketAgeMs = transport.lastSocketFrame.value
        val threatAgeMs = decoder.lastValidThreatUpdate.value
        val parts = mutableListOf("connected")
        if (threatAgeMs > 0L) {
            parts += "threats ${_threats.value.size} · data ${(now - threatAgeMs) / 1000}s old"
        } else if (socketAgeMs > 0L) {
            parts += "socket ${(now - socketAgeMs) / 1000}s old"
        }
        parts += "alerts ${_alerts.value.size}"
        return SourceTestResult(true, parts.joinToString(" · "))
    }

    override fun markUserShot(id: String) = decoder.markUserShot(id)
    override fun wasUserShotRecently(id: String): Boolean = decoder.wasUserShotRecently(id)
    override fun retryNow() = transport.retryNow()
    override fun pauseRetries(minutes: Int) = transport.pauseFor(minutes)
    override fun onAppForeground() = transport.onForeground()

    override fun dismissLogCard() = supervisor.dismissLogCard()
    override fun annotateConnectionLog(kind: ConnEventKind, attempt: Int?, delayMs: Long?, detail: String?) =
        supervisor.recordEvent(kind, attempt, delayMs, detail)

    override fun setActiveAlertSource(sourceId: String?) {
        supervisor.setActiveSource(sourceId)
        ConnectionLog.setPendingSource(sourceId)
    }

    private fun mapConnectionState(state: ConnectionState): SourceState = when {
        state.isPaused -> SourceState.PAUSED
        state.isDegraded -> SourceState.DEGRADED
        state.isConnected -> SourceState.CONNECTED
        state is ConnectionState.Connecting -> SourceState.CONNECTING
        else -> SourceState.OFFLINE
    }

    companion object {
        const val NEPTUN_DOMAIN = "neptun.in.ua"
        const val NEPTUN_SITE_URL = "https://$NEPTUN_DOMAIN/"
    }
}