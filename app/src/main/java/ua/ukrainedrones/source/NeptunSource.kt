package ua.ukrainedrones.source

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.ukrainedrones.ConnectionLog
import ua.ukrainedrones.connection.*
import ua.ukrainedrones.engine.MonitorCoreImpl
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.service.ServiceState

/**
 * The NEPTUN source: powered by the resilient threat engine core.
 *
 * Architecture:
 * - [ResilientConnectionSupervisor] owns OS network gating, the 42s silence watchdog, and full-jitter backoff.
 * - [NeptunRawDecoder] decodes frames and manages alert debouncing.
 * - [MonitorCoreImpl] manages authoritative threat/alert state and ingestion-side dead reckoning.
 */
class NeptunSource(private val context: Context) : Source, ConnectionLogSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = MonitorCoreImpl(context.applicationContext, scope)
    private val decoder = NeptunRawDecoder(core)

    private val supervisor = ResilientConnectionSupervisor(
        context = context.applicationContext,
        endpointUrl = ResilientConnectionSupervisor.DEFAULT_WS_URL,
        onFrameReceived = { text ->
            decoder.handleFrame(text)
        },
        onBaselineRequired = {
            core.onBaselineSyncRequired()
        },
        onWatchdogTick = { nowMono ->
            decoder.flushPendingAlertClear(nowMono)
        }
    )

    override val id = "neptun"
    override val name = "NEPTUN"
    override val sourceType = SourceType.WS
    /**
     * NEPTUN's per-type truth-life: how long each fix type stays alertable (staleAfterMs),
     * map-visible (staleAfterMs + ghostCapMs), and how far it may dead-reckon. These numbers
     * encode NEPTUN's feed behavior — they are owned here, never in the source-agnostic
     * engine. Consumers must read them via [SourceRegistry.typeCatalog], never by
     * importing this map.
     */
    override val typeCatalog: Map<String, ThreatProps> = NEPTUN_TYPES

    private val _operationalMode = MutableStateFlow(OperationalMode.STREAMING)
    override val operationalMode: StateFlow<OperationalMode> = _operationalMode.asStateFlow()

    override val threats: StateFlow<List<NormalizedThreat>> = core.threats
    override val alerts: StateFlow<List<OblastAlert>> = core.alerts

    private val _connectionState = MutableStateFlow(SourceState.DISCONNECTED)
    override val connectionState: StateFlow<SourceState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override val removedThreats: SharedFlow<ThreatRemoved> get() = decoder.removedThreats
    override val siteUrl: String? get() = NEPTUN_SITE_URL

    override val connEvents: StateFlow<List<ConnEvent>> get() = supervisor.connEvents
    override val retryState: StateFlow<ConnRetryState?> get() = supervisor.retryState
    override val milestones: SharedFlow<ConnectionMilestone> get() = supervisor.milestones

    override fun start(scope: CoroutineScope) {
        scope.launch {
            val svc = ServiceState(context.applicationContext)
            supervisor.start(
                savedReconnectStartMs = svc.reconnectStartMillis().first(),
                savedIgnoreUntilMs = svc.ignoreRetryUntil().first()
            )
        }
        scope.launch { persistReconnectStart() }
        scope.launch {
            supervisor.connectionState.collect { cs ->
                _connectionState.value = mapConnectionState(cs)
                if (cs is ConnectionState.Offline) {
                    core.onNetworkDisconnected(cs.reason ?: "Offline")
                    decoder.handleTransportDrop()
                } else if (cs.isConnected) {
                    core.onNetworkReconnected()
                }
            }
        }
    }

    private suspend fun persistReconnectStart() {
        val svc = ServiceState(context.applicationContext)
        supervisor.connectionState.collect { cs ->
            when (cs) {
                is ConnectionState.Offline -> svc.setReconnectStartMillis(cs.reconnectStartMillis)
                is ConnectionState.Connected -> svc.setReconnectStartMillis(0L)
                else -> {}
            }
        }
    }

    override fun stop() {
        supervisor.stop()
        _connectionState.value = SourceState.DISCONNECTED
        _operationalMode.value = OperationalMode.STANDBY
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled) {
            supervisor.start()
            _operationalMode.value = OperationalMode.STREAMING
        } else {
            supervisor.stop()
            _connectionState.value = SourceState.DISCONNECTED
            _operationalMode.value = OperationalMode.STANDBY
        }
    }

    override suspend fun testConnection(): SourceTestResult {
        val state = _connectionState.value
        if (state != SourceState.CONNECTED) {
            return SourceTestResult(false, "connection: $state")
        }
        val now = System.currentTimeMillis()
        val socketAgeMs = supervisor.lastSocketFrame.value
        val threatAgeMs = core.lastUpdateEpochMs.value
        val parts = mutableListOf("connected")
        if (threatAgeMs > 0L) {
            parts += "threats ${threats.value.size} · data ${(now - threatAgeMs) / 1000}s old"
        } else if (socketAgeMs > 0L) {
            parts += "socket ${(now - socketAgeMs) / 1000}s old"
        }
        parts += "alerts ${alerts.value.size}"
        return SourceTestResult(true, parts.joinToString(" · "))
    }

    override fun markUserShot(id: String) = core.markUserShot(id)
    override fun wasUserShotRecently(id: String): Boolean = core.wasUserShotRecently(id)
    override fun retryNow() = supervisor.retryNow()
    override fun pauseRetries(minutes: Int) = supervisor.pauseFor(minutes)
    override fun onAppForeground() = supervisor.onForeground()
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

        /**
         * NEPTUN-owned per-type properties (see [typeCatalog]). Values as sent by NEPTUN.
         * Only [NeptunSource] may reference this map — every other consumer goes through
         * the merged [SourceRegistry.typeCatalog] so the engine stays source-agnostic.
         */
        val NEPTUN_TYPES = mapOf(
            "shahed" to ThreatProps(
                isFast = false, reachKm = 1000.0, alwaysInnerWithinReach = false,
                staleAfterMs = 300_000, ghostCapMs = 900_000,
                nominalSpeedMps = 50.0, horizonSec = 300.0, maxGhostMeters = 18_000.0
            ),
            "fpv" to ThreatProps(
                isFast = false, reachKm = 40.0, alwaysInnerWithinReach = false,
                staleAfterMs = 300_000, ghostCapMs = 900_000,
                nominalSpeedMps = 33.33, horizonSec = 300.0, maxGhostMeters = 18_000.0
            ),
            "cruise" to ThreatProps(
                isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
                staleAfterMs = 180_000, ghostCapMs = 900_000,
                nominalSpeedMps = 236.11, horizonSec = 180.0, maxGhostMeters = 30_000.0
            ),
            "ballistic" to ThreatProps(
                isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
                staleAfterMs = 90_000, ghostCapMs = 900_000,
                nominalSpeedMps = 916.67, horizonSec = 90.0, maxGhostMeters = 20_000.0
            ),
            "kab" to ThreatProps(
                isFast = true, reachKm = 70.0, alwaysInnerWithinReach = false,
                staleAfterMs = 180_000, ghostCapMs = 900_000,
                nominalSpeedMps = 250.0, horizonSec = 180.0, maxGhostMeters = 10_000.0
            ),
            "aviation" to ThreatProps(
                isFast = true, reachKm = 9999.0, alwaysInnerWithinReach = true,
                staleAfterMs = 240_000, ghostCapMs = 7_200_000,
                nominalSpeedMps = 250.0, horizonSec = 240.0, maxGhostMeters = 24_000.0
            ),
            "recon" to ThreatProps(
                isFast = false, reachKm = 50.0, alwaysInnerWithinReach = false,
                staleAfterMs = 300_000, ghostCapMs = 900_000,
                nominalSpeedMps = 22.22, horizonSec = 300.0, maxGhostMeters = 12_000.0
            ),
            "unknown" to ThreatProps(
                isFast = false, reachKm = 1500.0, alwaysInnerWithinReach = false,
                staleAfterMs = 300_000, ghostCapMs = 900_000,
                nominalSpeedMps = null, horizonSec = 240.0, maxGhostMeters = 10_000.0
            )
        )
    }
}
