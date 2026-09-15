package ua.ukrainedrones.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
        private const val MAX_CONN_EVENTS = 50
        private const val TAG = "ResilientConnSuper"
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
    private val connectionGeneration = AtomicInteger(0)
    private val lastIncomingByteMono = AtomicLong(0L)
    private val reconnectAttempts = AtomicInteger(0)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connEvents = MutableStateFlow<List<ConnEvent>>(emptyList())
    val connEvents: StateFlow<List<ConnEvent>> = _connEvents.asStateFlow()

    private val _retryState = MutableStateFlow<ConnRetryState?>(null)
    val retryState: StateFlow<ConnRetryState?> = _retryState.asStateFlow()

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

        if (isPaused()) {
            updateConnectionState(ConnectionState.Paused(
                untilMs = System.currentTimeMillis() + (pauseUntilMono - Monotonic.now()),
                since = System.currentTimeMillis(),
                reconnectStartMillis = savedReconnectStartMs
            ))
        } else if (valid) {
            triggerReconnect("Initial start")
        } else {
            updateConnectionState(ConnectionState.Offline(
                since = System.currentTimeMillis(),
                reconnectStartMillis = if (savedReconnectStartMs > 0L) savedReconnectStartMs else System.currentTimeMillis(),
                reason = "No validated internet"
            ))
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
        isNetworkValidated.set(false)
        closeCurrentSocket(reason)
        connectJob?.cancel()
        val now = System.currentTimeMillis()
        val currentStart = _connectionState.value.reconnectStartMillisOrZero
        updateConnectionState(ConnectionState.Offline(
            since = now,
            reconnectStartMillis = if (currentStart > 0L) currentStart else now,
            reason = reason
        ))
        recordEvent(ConnEventKind.NO_NETWORK)
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
            }
        }
    }

    fun triggerReconnect(reason: String) {
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            executeConnect()
        }
    }

    private fun scheduleReconnectWithBackoff(reason: String) {
        if (!isNetworkValidated.get()) {
            handleNetworkLost("No network available for reconnect")
            return
        }
        if (isPaused()) return

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            val attempt = reconnectAttempts.incrementAndGet()
            val expDelay = min(MAX_BACKOFF_MS.toDouble(), BASE_BACKOFF_MS * 2.0.pow(attempt.coerceAtMost(6).toDouble())).toLong()
            val jitter = Random.nextDouble(0.75, 1.25)
            val delayMs = (expDelay * jitter).toLong().coerceIn(BASE_BACKOFF_MS, MAX_BACKOFF_MS)

            val nextAt = System.currentTimeMillis() + delayMs
            _retryState.value = ConnRetryState(attempt, delayMs, nextAt, isNetworkValidated.get())
            recordEvent(ConnEventKind.RETRY_SCHEDULED, attempt, delayMs)

            val currentStart = _connectionState.value.reconnectStartMillisOrZero
            updateConnectionState(ConnectionState.Offline(
                since = System.currentTimeMillis(),
                reconnectStartMillis = if (currentStart > 0L) currentStart else System.currentTimeMillis(),
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
        val gen = connectionGeneration.incrementAndGet()
        updateConnectionState(ConnectionState.Connecting(
            generation = gen,
            attempt = reconnectAttempts.get(),
            nextRetryAtMs = 0L,
            networkValidated = isNetworkValidated.get()
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
                    updateConnectionState(ConnectionState.Connected(gen, openedAt, System.currentTimeMillis()))
                }
                onFrameReceived(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionGeneration.get() != gen) return
                activeWebSocket = null
                recordEvent(ConnEventKind.CONNECTION_LOST, detail = t.message)
                scheduleReconnectWithBackoff("Socket failure: ${t.message}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionGeneration.get() != gen) return
                activeWebSocket = null
                if (isRunning.get() && isNetworkValidated.get()) {
                    scheduleReconnectWithBackoff("Socket closed ($code)")
                }
            }
        })
    }

    private fun closeCurrentSocket(reason: String) {
        try {
            connectionGeneration.incrementAndGet()
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
        if (_connectionState.value.isOffline && !isPaused() && isNetworkValidated.get()) {
            triggerReconnect("App foregrounded")
        }
    }

    fun dismissLogCard() {
        _retryState.value = null
    }

    fun setActiveSource(sourceId: String?) {
        activeSource = sourceId
        ConnectionLog.setPendingSource(sourceId)
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
