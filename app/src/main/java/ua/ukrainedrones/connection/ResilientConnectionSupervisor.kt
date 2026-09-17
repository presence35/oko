package ua.ukrainedrones.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ua.ukrainedrones.ConnectionLog
import ua.ukrainedrones.ConnStatus
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Resilient Connection Supervisor.
 *
 * Combines OS-level network gating, a 42-second byte silence hardware watchdog,
 * full-jitter backoff, and monotonic clock telemetry into a single authoritative supervisor.
 *
 * Invariants:
 * 1. OS Network Gate: Does NOT spin reconnect loops while offline. Wakes immediately upon network validation.
 * 2. 42-Second Byte Silence Watchdog: Actively tears down dead sockets when no frame/ping byte arrives.
 * 3. Thread-safe, non-blocking coroutines.
 */
class ResilientConnectionSupervisor(
    private val context: Context,
    private val endpointUrl: String = DEFAULT_WS_URL,
    private val onFrameReceived: (String) -> Unit,
    private val onBaselineRequired: () -> Unit,
    private val onWatchdogTick: ((Long) -> Unit)? = null
) {
    companion object {
        const val DEFAULT_WS_URL = "wss://neptun.in.ua/api/v1/stream"
        const val SILENCE_TIMEOUT_MS = 42_000L
        const val DEGRADED_STALE_MS = 30_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val WATCHDOG_TICK_MS = 3_000L
        const val MILESTONE_3_MS = 3 * 60_000L
        const val MILESTONE_5_MS = 5 * 60_000L
        const val MILESTONE_6_MS = 6 * 60_000L
        const val MILESTONE_10_MS = 10 * 60_000L
        const val MILESTONE_20_MS = 20 * 60_000L
        /** Offline/Connecting with a live network and no reconnect progress past this → force retry. */
        const val STUCK_OFFLINE_MS = MAX_BACKOFF_MS + 20_000L
        private const val MAX_CONN_EVENTS = 50
        private const val TAG = "ResilientConnSuper"

        /** Pure full-jitter backoff: exponential base capped at [MAX_BACKOFF_MS], scaled by
         *  [jitter] (default random 0.75–1.25). Extracted for deterministic unit tests. */
        fun backoffDelayMs(attempt: Int, jitter: Double = Random.nextDouble(0.75, 1.25)): Long {
            val expDelay = min(MAX_BACKOFF_MS.toDouble(), BASE_BACKOFF_MS * 2.0.pow(attempt.coerceAtMost(6).toDouble())).toLong()
            return (expDelay * jitter).toLong().coerceIn(BASE_BACKOFF_MS, MAX_BACKOFF_MS)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite read timeout for long-lived WS
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var activeWebSocket: WebSocket? = null
    private val isNetworkValidated = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    /** Only executeConnect mints generations; closeCurrentSocket never bumps it. */
    private val connectionGeneration = AtomicInteger(0)
    /** One-shot per generation: onFailure and onClosed both fire per socket, only the first
     *  may schedule a reconnect. */
    private val disconnectHandledGen = AtomicInteger(-1)
    private val lastIncomingByteMono = AtomicLong(0L)
    private val reconnectAttempts = AtomicInteger(0)
    /** Last monotonic stamp of reconnect progress (schedule/connect/trigger); drives the
     *  stuck-offline watchdog. */
    private val lastReconnectProgressMono = AtomicLong(0L)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connEvents = MutableStateFlow<List<ConnEvent>>(emptyList())
    val connEvents: StateFlow<List<ConnEvent>> = _connEvents.asStateFlow()

    private val _retryState = MutableStateFlow<ConnRetryState?>(null)
    val retryState: StateFlow<ConnRetryState?> = _retryState.asStateFlow()

    /** Offline-episode milestones, once per episode. The service collects this for
     *  notifications; the same marks are also recorded in [connEvents]. */
    private val _milestones = MutableSharedFlow<ConnectionMilestone>(extraBufferCapacity = 16)
    val milestones: SharedFlow<ConnectionMilestone> = _milestones.asSharedFlow()

    /** Which milestones already fired for [milestoneEpisodeStart] (a reconnectStartMillis). */
    private var firedM3 = false
    private var firedM5 = false
    private var firedM6 = false
    private var firedM10 = false
    private var firedM20 = false
    private var milestoneEpisodeStart = 0L

    val lastSocketFrame = MutableStateFlow(0L)

    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    private var pauseUntilMono = 0L
    @Volatile private var activeSource: String? = null

    private fun updateConnectionState(newState: ConnectionState) {
        _connectionState.value = newState
        val now = System.currentTimeMillis()
        when (newState) {
            is ConnectionState.Connected -> {
                ConnectionLog.observe(ConnStatus.ONLINE, now, activeSource)
            }
            is ConnectionState.Degraded -> {
                ConnectionLog.observe(ConnStatus.DEGRADED, now, activeSource)
            }
            is ConnectionState.Offline, ConnectionState.Disconnected, is ConnectionState.Paused -> {
                ConnectionLog.observe(ConnStatus.OFFLINE, now, activeSource)
            }
            is ConnectionState.Connecting -> {
                // Keep previous state until connection resolves
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val valid = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (valid) {
                if (isNetworkValidated.compareAndSet(false, true)) {
                    recordEvent(ConnEventKind.FALLBACK_RESTORED, detail = "Network validated")
                    if (isRunning.get() && !isPaused()) {
                        reconnectAttempts.set(0)
                        triggerReconnect("Network restored")
                    }
                }
            } else {
                handleNetworkLost("Network capability invalidated")
            }
        }

        override fun onLost(network: Network) {
            handleNetworkLost("Network lost")
        }
    }

    fun start(savedReconnectStartMs: Long = 0L, savedIgnoreUntilMs: Long = 0L) {
        if (!isRunning.compareAndSet(false, true)) return

        if (savedIgnoreUntilMs > System.currentTimeMillis()) {
            val remainMs = savedIgnoreUntilMs - System.currentTimeMillis()
            pauseUntilMono = Monotonic.now() + remainMs
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }

        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        val valid = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        isNetworkValidated.set(valid)

        startWatchdogLoop()
        lastReconnectProgressMono.set(Monotonic.now())

        if (isPaused()) {
            updateConnectionState(ConnectionState.Paused(
                untilMs = System.currentTimeMillis() + (pauseUntilMono - Monotonic.now()),
                since = System.currentTimeMillis(),
                reconnectStartMillis = savedReconnectStartMs
            ))
        } else if (valid) {
            triggerReconnect("Initial start")
        } else {
            val startMs = if (savedReconnectStartMs > 0L) savedReconnectStartMs else System.currentTimeMillis()
            updateConnectionState(ConnectionState.Offline(
                since = System.currentTimeMillis(),
                reconnectStartMillis = startMs,
                reason = "No validated internet"
            ))
            // Resumed mid-episode: past milestones are recorded silently (no flow emission,
            // so the service sends no retroactive burst); future crossings notify normally.
            markPastMilestonesSilent(System.currentTimeMillis() - startMs)
            recordEvent(ConnEventKind.NO_NETWORK)
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        watchdogJob?.cancel()
        connectJob?.cancel()
        closeCurrentSocket("Supervisor stopped")
        updateConnectionState(ConnectionState.Disconnected)
    }

    private fun handleNetworkLost(reason: String) {
        val wasDown = _connectionState.value is ConnectionState.Offline ||
                _connectionState.value is ConnectionState.Connecting
        isNetworkValidated.set(false)
        closeCurrentSocket(reason)
        connectJob?.cancel()
        val now = System.currentTimeMillis()
        // Episode age is stamped once on the Connected → down edge; flickers never rewrite it.
        val episodeStart = beginEpisodeIfNeeded(now)
        val prev = _connectionState.value
        updateConnectionState(ConnectionState.Offline(
            since = prev.offlineSinceOrNull ?: now,
            reconnectStartMillis = episodeStart,
            reason = reason
        ))
        if (!wasDown) recordEvent(ConnEventKind.NO_NETWORK)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRunning.get()) {
                delay(WATCHDOG_TICK_MS)
                val nowMono = Monotonic.now()
                onWatchdogTick?.invoke(nowMono)

                // Check pause expiration
                if (isPaused() && nowMono >= pauseUntilMono) {
                    pauseUntilMono = 0L
                    recordEvent(ConnEventKind.RETRY_SCHEDULED, detail = "Pause elapsed")
                    triggerReconnect("Pause expired")
                    continue
                }

                val cs = _connectionState.value
                val lastByte = lastIncomingByteMono.get()

                if (cs.isConnected && lastByte > 0L) {
                    val silenceDuration = nowMono - lastByte
                    // Silence watchdog: 42s without a single byte -> force tear-down
                    if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                        Log.w(TAG, "42-Second Byte Silence Watchdog expired ($silenceDuration ms) - forcing socket restart")
                        recordEvent(ConnEventKind.CONNECTION_LOST, detail = "42s silence timeout")
                        closeCurrentSocket("Silence timeout")
                        scheduleReconnectWithBackoff("42s silence watchdog")
                    } else if (silenceDuration >= DEGRADED_STALE_MS && cs !is ConnectionState.Degraded) {
                        val gen = connectionGeneration.get()
                        updateConnectionState(ConnectionState.Degraded(
                            generation = gen,
                            openedAtMs = System.currentTimeMillis() - silenceDuration,
                            lastFrameAtMs = System.currentTimeMillis() - silenceDuration,
                            quietDurationMs = silenceDuration
                        ))
                        recordEvent(ConnEventKind.DEGRADED)
                    }
                }

                val csNow = _connectionState.value
                if (csNow is ConnectionState.Offline || csNow is ConnectionState.Connecting) {
                    checkMilestones(System.currentTimeMillis())
                    checkStuckOffline(nowMono)
                }
            }
        }
    }

    /** Stuck-state watchdog: Offline/Connecting with a live network but no reconnect progress
     *  (no scheduled job running, no recent schedule/connect) gets force-retried. Covers the
     *  weak-WiFi stall where a backoff job was lost and validation never flapped. */
    private fun checkStuckOffline(nowMono: Long) {
        if (!isNetworkValidated.get() || isPaused()) return
        if (connectJob?.isActive == true) return
        if (nowMono - lastReconnectProgressMono.get() >= STUCK_OFFLINE_MS) {
            recordEvent(ConnEventKind.RETRY_SCHEDULED, detail = "Stuck watchdog")
            triggerReconnect("Stuck offline watchdog")
        }
    }

    fun triggerReconnect(@Suppress("UNUSED_PARAMETER") reason: String) {
        if (!isRunning.get()) return
        lastReconnectProgressMono.set(Monotonic.now())
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            executeConnect()
        }
    }

    private fun scheduleReconnectWithBackoff(reason: String) {
        if (isPaused()) return
        // Schedule the timer regardless of validation; the socket is only created when the
        // network is validated at fire time, so a genuinely dead network never spins.
        if (!isNetworkValidated.get()) {
            handleNetworkLost("No network available for reconnect")
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            val attempt = reconnectAttempts.incrementAndGet()
            val delayMs = backoffDelayMs(attempt)
            lastReconnectProgressMono.set(Monotonic.now())

            val nextAt = System.currentTimeMillis() + delayMs
            _retryState.value = ConnRetryState(attempt, delayMs, nextAt, isNetworkValidated.get())
            recordEvent(ConnEventKind.RETRY_SCHEDULED, attempt, delayMs)

            val now = System.currentTimeMillis()
            val prev = _connectionState.value
            updateConnectionState(ConnectionState.Offline(
                since = prev.offlineSinceOrNull ?: now,
                reconnectStartMillis = beginEpisodeIfNeeded(now),
                reason = reason,
                attempt = attempt
            ))

            delay(delayMs)
            if (isRunning.get() && isNetworkValidated.get() && !isPaused()) {
                executeConnect()
            }
        }
    }

    private fun executeConnect() {
        closeCurrentSocket("Starting fresh connection")
        lastReconnectProgressMono.set(Monotonic.now())
        val gen = connectionGeneration.incrementAndGet()
        updateConnectionState(ConnectionState.Connecting(
            generation = gen,
            attempt = reconnectAttempts.get(),
            nextRetryAtMs = 0L,
            networkValidated = isNetworkValidated.get(),
            reconnectStartMillis = beginEpisodeIfNeeded(System.currentTimeMillis())
        ))

        val request = Request.Builder()
            .url(endpointUrl)
            .header("User-Agent", "OkoThreatEngine/2.0")
            .build()

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionGeneration.get() != gen) {
                    webSocket.close(1000, "Stale generation")
                    return
                }
                lastIncomingByteMono.set(Monotonic.now())
                reconnectAttempts.set(0)
                _retryState.value = null
                resetMilestoneFlags()
                milestoneEpisodeStart = 0L
                val nowWall = System.currentTimeMillis()
                updateConnectionState(ConnectionState.Connected(gen, nowWall, nowWall))
                onBaselineRequired()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionGeneration.get() != gen) return
                val nowMono = Monotonic.now()
                lastIncomingByteMono.set(nowMono)
                lastSocketFrame.value = System.currentTimeMillis()

                val currentCs = _connectionState.value
                if (currentCs !is ConnectionState.Connected) {
                    val openedAt = if (currentCs is ConnectionState.Degraded) currentCs.openedAtMs else System.currentTimeMillis()
                    resetMilestoneFlags()
                    milestoneEpisodeStart = 0L
                    updateConnectionState(ConnectionState.Connected(gen, openedAt, System.currentTimeMillis()))
                }
                onFrameReceived(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionGeneration.get() != gen) return
                if (disconnectHandledGen.getAndSet(gen) == gen) return
                activeWebSocket = null
                beginEpisodeIfNeeded(System.currentTimeMillis())
                recordEvent(ConnEventKind.CONNECTION_LOST, detail = t.message)
                scheduleReconnectWithBackoff("Socket failure: ${t.message}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionGeneration.get() != gen) return
                if (disconnectHandledGen.getAndSet(gen) == gen) return
                activeWebSocket = null
                // No validated gate: the backoff path itself no-ops into handleNetworkLost
                // when the network is down, so a validation flap can never strand us.
                if (isRunning.get() && !isPaused()) {
                    beginEpisodeIfNeeded(System.currentTimeMillis())
                    scheduleReconnectWithBackoff("Socket closed ($code)")
                }
            }
        })
    }

    /** Closes the socket without minting a generation — only executeConnect mints, so an
     *  in-flight failure can never race a fresh connect into a stranded Offline. The current
     *  generation's disconnect is claimed here, so the socket's own onFailure/onClosed can
     *  never double-schedule behind an intentional close. */
    private fun closeCurrentSocket(@Suppress("UNUSED_PARAMETER") reason: String) {
        try {
            disconnectHandledGen.set(connectionGeneration.get())
            activeWebSocket?.cancel()
            activeWebSocket = null
        } catch (_: Exception) {}
    }

    fun isPaused(): Boolean = Monotonic.now() < pauseUntilMono

    fun pauseFor(minutes: Int) {
        pauseUntilMono = Monotonic.now() + (minutes * 60_000L)
        closeCurrentSocket("User paused retries")
        val now = System.currentTimeMillis()
        val currentStart = _connectionState.value.reconnectStartMillisOrZero
        updateConnectionState(ConnectionState.Paused(
            untilMs = now + (minutes * 60_000L),
            since = now,
            reconnectStartMillis = if (currentStart > 0L) currentStart else now
        ))
        recordEvent(ConnEventKind.PAUSED, detail = "$minutes min")
    }

    fun retryNow() {
        pauseUntilMono = 0L
        reconnectAttempts.set(0)
        _retryState.value = null
        triggerReconnect("User manual retry")
    }

    fun onForeground() {
        if (!isRunning.get() || isPaused() || !isNetworkValidated.get()) return
        val cs = _connectionState.value
        if (cs.isOffline || cs is ConnectionState.Connecting) {
            triggerReconnect("App foregrounded")
        }
    }

    fun dismissLogCard() {
        _connEvents.value = emptyList()
        _retryState.value = null
    }

    fun setActiveSource(sourceId: String?) {
        activeSource = sourceId
        ConnectionLog.setPendingSource(sourceId)
    }

    /** Returns the current episode's reconnectStartMillis, starting a new episode (and rotating
     *  the transient log) when the previous state carried none. Call before recording any
     *  event for the drop so rotation never wipes the new episode's first line. */
    private fun beginEpisodeIfNeeded(nowWall: Long): Long {
        val existing = _connectionState.value.reconnectStartMillisOrZero
        if (existing > 0L) return existing
        _connEvents.value = emptyList()
        return nowWall
    }

    private fun resetMilestoneFlags() {
        firedM3 = false
        firedM5 = false
        firedM6 = false
        firedM10 = false
        firedM20 = false
    }

    /** Live milestone timer: fires each mark once per episode into the log and the milestones
     *  flow. Keyed off the persisted reconnectStartMillis, so process restarts don't refire. */
    private fun checkMilestones(nowWall: Long) {
        val cs = _connectionState.value
        if (cs !is ConnectionState.Offline && cs !is ConnectionState.Connecting) return
        val episodeStart = cs.reconnectStartMillisOrZero
        if (episodeStart <= 0L) return
        if (episodeStart != milestoneEpisodeStart) {
            resetMilestoneFlags()
            milestoneEpisodeStart = episodeStart
        }
        val age = nowWall - episodeStart
        maybeFireMilestone(age, MILESTONE_3_MS, firedM3, ConnEventKind.MILESTONE_3, ConnectionMilestone.M3) { firedM3 = true }
        maybeFireMilestone(age, MILESTONE_5_MS, firedM5, ConnEventKind.MILESTONE_5, ConnectionMilestone.M5_CRITICAL) { firedM5 = true }
        maybeFireMilestone(age, MILESTONE_6_MS, firedM6, ConnEventKind.MILESTONE_6, ConnectionMilestone.M6) { firedM6 = true }
        maybeFireMilestone(age, MILESTONE_10_MS, firedM10, ConnEventKind.MILESTONE_10, ConnectionMilestone.M10) { firedM10 = true }
        if (age >= MILESTONE_20_MS && !firedM20) {
            firedM20 = true
            recordEvent(ConnEventKind.MILESTONE_20)
            recordEvent(ConnEventKind.GAVE_UP)
            _milestones.tryEmit(ConnectionMilestone.M20_GAVE_UP)
        }
    }

    private fun maybeFireMilestone(
        ageMs: Long,
        thresholdMs: Long,
        fired: Boolean,
        kind: ConnEventKind,
        milestone: ConnectionMilestone,
        mark: () -> Unit
    ) {
        if (ageMs >= thresholdMs && !fired) {
            mark()
            recordEvent(kind)
            _milestones.tryEmit(milestone)
        }
    }

    /** Restart catch-up for a persisted episode: past marks are recorded silently (no flow
     *  emission → no retroactive notification burst); future crossings notify normally. */
    private fun markPastMilestonesSilent(ageMs: Long) {
        milestoneEpisodeStart = _connectionState.value.reconnectStartMillisOrZero
        if (ageMs >= MILESTONE_3_MS && !firedM3) { firedM3 = true; recordEvent(ConnEventKind.MILESTONE_3) }
        if (ageMs >= MILESTONE_5_MS && !firedM5) { firedM5 = true; recordEvent(ConnEventKind.MILESTONE_5) }
        if (ageMs >= MILESTONE_6_MS && !firedM6) { firedM6 = true; recordEvent(ConnEventKind.MILESTONE_6) }
        if (ageMs >= MILESTONE_10_MS && !firedM10) { firedM10 = true; recordEvent(ConnEventKind.MILESTONE_10) }
        if (ageMs >= MILESTONE_20_MS && !firedM20) {
            firedM20 = true
            recordEvent(ConnEventKind.MILESTONE_20)
            recordEvent(ConnEventKind.GAVE_UP)
        }
    }

    fun recordEvent(
        kind: ConnEventKind,
        attempt: Int? = null,
        delayMs: Long? = null,
        detail: String? = null
    ) {
        val event = ConnEvent(
            atMillis = System.currentTimeMillis(),
            kind = kind,
            attempt = attempt,
            delayMs = delayMs,
            detail = detail
        )
        _connEvents.update { list ->
            (listOf(event) + list).take(MAX_CONN_EVENTS)
        }
    }
}
