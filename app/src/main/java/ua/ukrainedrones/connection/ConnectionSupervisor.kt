package ua.ukrainedrones.connection

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.ukrainedrones.ConnStatus
import ua.ukrainedrones.ConnectionLog

/**
 * Supervised connection health & milestone tracker.
 *
 * Responsibilities:
 * 1. Tracks offline duration milestones (3m, 5m, 6m, 10m, 20m) accurately across app lifecycles.
 * 2. Emits real-time [ConnEvent] items directly on connection lifecycle transitions.
 * 3. Bridges [NeptunConnectionClient] states to [ConnectionLog] event-driven updates.
 * 4. Manages the transient UI retry countdown state ([ConnRetryState]).
 */
class ConnectionSupervisor(
    private val context: Context,
    private val client: NeptunConnectionClient,
    private val onMilestoneReached: ((ConnEventKind, Long) -> Unit)? = null
) {
    companion object {
        private const val MAX_CONN_EVENTS = 50
        const val MILESTONE_3_MS = 3 * 60_000L
        const val MILESTONE_5_MS = 5 * 60_000L
        const val MILESTONE_6_MS = 6 * 60_000L
        const val MILESTONE_10_MS = 10 * 60_000L
        const val MILESTONE_20_MS = 20 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connEvents = MutableStateFlow<List<ConnEvent>>(emptyList())
    val connEvents: StateFlow<List<ConnEvent>> = _connEvents.asStateFlow()

    private val _retryState = MutableStateFlow<ConnRetryState?>(null)
    val retryState: StateFlow<ConnRetryState?> = _retryState.asStateFlow()

    private var milestoneMonitorJob: Job? = null
    private var lastRecordedState: ConnectionState? = null

    // Track which milestones have already fired in the current outage episode
    private var firedMilestone3 = false
    private var firedMilestone5 = false
    private var firedMilestone6 = false
    private var firedMilestone10 = false
    private var firedMilestone20 = false

    fun start() {
        scope.launch {
            client.connectionState.collect { state ->
                handleStateTransition(state)
            }
        }
        startMilestoneTimer()
    }

    fun stop() {
        milestoneMonitorJob?.cancel()
        scope.cancel()
    }

    fun recordEvent(kind: ConnEventKind, attempt: Int? = null, delayMs: Long? = null) {
        _connEvents.update { list ->
            (list + ConnEvent(System.currentTimeMillis(), kind, attempt, delayMs)).takeLast(MAX_CONN_EVENTS)
        }
    }

    fun dismissLogCard() {
        _connEvents.value = emptyList()
        _retryState.value = null
    }

    private fun handleStateTransition(state: ConnectionState) {
        val prev = lastRecordedState
        lastRecordedState = state
        val now = System.currentTimeMillis()

        when (state) {
            is ConnectionState.Connected -> {
                resetMilestones()
                _retryState.value = null
                ConnectionLog.observe(ConnStatus.ONLINE, now)
            }

            is ConnectionState.Degraded -> {
                if (prev !is ConnectionState.Degraded) {
                    recordEvent(ConnEventKind.DEGRADED)
                }
                ConnectionLog.observe(ConnStatus.DEGRADED, now)
            }

            is ConnectionState.Connecting -> {
                val delayMs = (state.nextRetryAtMs - now).coerceAtLeast(0L)
                _retryState.value = ConnRetryState(
                    attempt = state.attempt,
                    delayMs = delayMs,
                    nextAtMs = state.nextRetryAtMs,
                    networkValidated = state.networkValidated
                )
                if (prev !is ConnectionState.Connecting) {
                    val kind = if (state.networkValidated) ConnEventKind.RETRY_SCHEDULED else ConnEventKind.NO_NETWORK
                    recordEvent(kind, state.attempt, delayMs)
                }
            }

            is ConnectionState.Offline -> {
                if (prev is ConnectionState.Connected || prev == null) {
                    recordEvent(ConnEventKind.CONNECTION_LOST)
                }
                ConnectionLog.observe(ConnStatus.OFFLINE, now)
            }

            is ConnectionState.Paused -> {
                _retryState.value = null
                recordEvent(ConnEventKind.PAUSED)
            }

            ConnectionState.Disconnected -> {
                _retryState.value = null
            }
        }
    }

    private fun resetMilestones() {
        firedMilestone3 = false
        firedMilestone5 = false
        firedMilestone6 = false
        firedMilestone10 = false
        firedMilestone20 = false
    }

    private fun startMilestoneTimer() {
        milestoneMonitorJob?.cancel()
        milestoneMonitorJob = scope.launch {
            while (isActive) {
                delay(1000)
                val state = client.connectionState.value
                if (state is ConnectionState.Offline) {
                    val outageDuration = System.currentTimeMillis() - state.reconnectStartMillis
                    checkMilestones(outageDuration)
                }
            }
        }
    }

    private fun checkMilestones(durationMs: Long) {
        if (durationMs >= MILESTONE_3_MS && !firedMilestone3) {
            firedMilestone3 = true
            recordEvent(ConnEventKind.MILESTONE_3)
            onMilestoneReached?.invoke(ConnEventKind.MILESTONE_3, durationMs)
        }
        if (durationMs >= MILESTONE_5_MS && !firedMilestone5) {
            firedMilestone5 = true
            recordEvent(ConnEventKind.MILESTONE_5)
            onMilestoneReached?.invoke(ConnEventKind.MILESTONE_5, durationMs)
        }
        if (durationMs >= MILESTONE_6_MS && !firedMilestone6) {
            firedMilestone6 = true
            recordEvent(ConnEventKind.MILESTONE_6)
            onMilestoneReached?.invoke(ConnEventKind.MILESTONE_6, durationMs)
        }
        if (durationMs >= MILESTONE_10_MS && !firedMilestone10) {
            firedMilestone10 = true
            recordEvent(ConnEventKind.MILESTONE_10)
            onMilestoneReached?.invoke(ConnEventKind.MILESTONE_10, durationMs)
        }
        if (durationMs >= MILESTONE_20_MS && !firedMilestone20) {
            firedMilestone20 = true
            recordEvent(ConnEventKind.MILESTONE_20)
            recordEvent(ConnEventKind.GAVE_UP)
            onMilestoneReached?.invoke(ConnEventKind.MILESTONE_20, durationMs)
        }
    }
}
