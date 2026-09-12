package ua.ukrainedrones.connection

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ua.ukrainedrones.BuildConfig
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The NEPTUN WebSocket transport: pure socket lifecycle for [NeptunSource]. Owns the
 * generation-based reconnect machine, the [ConnectionState] state machine, network-gated
 * retries, pause/ignore and the socket-quiet watchdog (degraded detection + stale-socket close).
 * Frame decoding is **not** here — it lives in [NeptunDecoder]. Text frames are pushed onto
 * [frames] for the owner to consume.
 *
 * Strict generation-based lifecycle ([connectionGeneration]) eliminates socket identity races:
 * a stale socket's callbacks compare against the current generation before touching any state.
 * The watchdog gates run on a **monotonic clock** ([Monotonic]) so a wall-clock jump can't stall
 * or trigger a reconnect; the public freshness stamps stay wall-clock for the Sources tab.
 */
class WsTransport(
    private val context: Context,
    private val client: OkHttpClient = defaultHttpClient()
) {

    companion object {
        const val OFFLINE_GRACE_MS = 5_000L
        const val DEGRADED_STALE_MS = 30_000L
        const val WATCHDOG_STALE_MS = 45_000L
        const val NO_NETWORK_RECONNECT_MS = 60_000L

        private const val WS_URL = "wss://neptun.in.ua/api/v1/stream"

        fun calculateBackoffMs(attempt: Int): Long = when {
            attempt <= 1 -> 1000L + (0..2000).random()
            else -> minOf(15_000L, 1000L * (1 shl (attempt - 1))) + (0..400).random()
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkMonitor = NetworkMonitor(context)
    internal val frames = Channel<String>(capacity = Channel.UNLIMITED)

    // Generation counter for all socket instances
    private val connectionGeneration = AtomicInteger(0)
    @Volatile private var activeWebSocket: WebSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastSocketFrame = MutableStateFlow(0L)
    val lastSocketFrame: StateFlow<Long> = _lastSocketFrame.asStateFlow()

    // Monotonic mirror of the socket freshness stamp, used only by the in-process watchdog gates
    // so a wall-clock jump can't stall the degraded/watchdog timers. The public StateFlow above
    // stays wall-clock (the Sources tab renders it against a wall `now`).
    @Volatile internal var lastSocketFrameMono = 0L

    // Tracking
    private var openedAtMs = 0L
    @Volatile private var lastFrameAtMs = 0L
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var isManuallyStopped = false
    /** Last generation whose disconnect was already handled — onClosed and onFailure can both
     *  fire for the same socket, and only the first should run [handleDisconnect]. */
    private val disconnectHandledGen = AtomicInteger(-1)

    // Pause / Ignore state
    private var ignoreUntilMs = 0L
    private var persistedReconnectStartMs = 0L

    /** Invoked by the transport's own watchdog tick so the owner can flush timer-gated feed state
     *  (e.g. the decoder's 30s alert-clear debounce) without owning the loop. */
    internal var onWatchdogTick: ((nowMono: Long) -> Unit)? = null

    init {
        startNetworkObserver()
    }

    private fun startNetworkObserver() {
        scope.launch {
            networkMonitor.isValidated.collect { isValidated ->
                if (isValidated && !isManuallyStopped && !isIgnoringPause()) {
                    val current = _connectionState.value
                    if (current is ConnectionState.Connecting && !current.networkValidated) {
                        retryNow()
                    } else if (current is ConnectionState.Offline) {
                        retryNow()
                    }
                } else if (!isValidated && !isManuallyStopped) {
                    // Network lost (e.g. airplane mode): immediately close the socket so the
                    // connection state transitions to Offline and the notification updates.
                    val current = _connectionState.value
                    if (current is ConnectionState.Connected || current is ConnectionState.Degraded) {
                        activeWebSocket?.close(1001, "network lost")
                    }
                }
            }
        }
    }

    fun start(savedReconnectStartMs: Long = 0L, savedIgnoreUntilMs: Long = 0L) {
        if (savedReconnectStartMs > 0L) persistedReconnectStartMs = savedReconnectStartMs
        if (savedIgnoreUntilMs > 0L) ignoreUntilMs = savedIgnoreUntilMs
        isManuallyStopped = false
        if (_connectionState.value.isConnected) return
        startWatchdog()
        connect()
    }

    fun stop() {
        isManuallyStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        connectionGeneration.incrementAndGet()
        activeWebSocket?.close(1000, "client stop")
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun close() {
        stop()
        scope.cancel()
    }

    fun retryNow() {
        if (isManuallyStopped) return
        ignoreUntilMs = 0L
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        val gen = connectionGeneration.incrementAndGet()
        activeWebSocket?.close(1000, "manual retry")
        activeWebSocket = null
        connect(gen)
    }

    fun onForeground() {
        if (!isManuallyStopped && _connectionState.value.isOffline && !isIgnoringPause()) {
            retryNow()
        }
    }

    fun pauseFor(minutes: Int) {
        val now = System.currentTimeMillis()
        ignoreUntilMs = now + minutes * 60_000L
        reconnectJob?.cancel()
        reconnectJob = null
        val recStart = when {
            persistedReconnectStartMs > 0L -> persistedReconnectStartMs
            _connectionState.value.reconnectStartMillisOrZero > 0L -> _connectionState.value.reconnectStartMillisOrZero
            else -> now
        }
        _connectionState.value = ConnectionState.Paused(
            untilMs = ignoreUntilMs,
            since = now,
            reconnectStartMillis = recStart
        )
        schedulePauseExpiry()
    }

    fun isIgnoringPause(): Boolean = System.currentTimeMillis() < ignoreUntilMs

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (isManuallyStopped) return@launch
                val nowMono = Monotonic.now()
                onWatchdogTick?.invoke(nowMono)
                tickSocketWatchdog(nowMono)
            }
        }
    }

    /** Degraded + stale-socket watchdog gates. [nowMono] is a monotonic stamp. */
    private fun tickSocketWatchdog(nowMono: Long) {
        val socketQuietFor = if (lastSocketFrameMono > 0L) nowMono - lastSocketFrameMono else 0L
        val currentState = _connectionState.value
        if (currentState.isConnected && lastSocketFrameMono > 0L && socketQuietFor > DEGRADED_STALE_MS && currentState !is ConnectionState.Degraded) {
            _connectionState.value = ConnectionState.Degraded(
                generation = (currentState as? ConnectionState.Connected)?.generation ?: 0,
                openedAtMs = openedAtMs,
                lastFrameAtMs = _lastSocketFrame.value,
                quietDurationMs = socketQuietFor
            )
        }
        if (currentState.isConnected && lastSocketFrameMono > 0L && socketQuietFor > WATCHDOG_STALE_MS) {
            // Trigger watchdog reconnect
            activeWebSocket?.close(1001, "watchdog stale")
        }
    }

    private fun connect(gen: Int = connectionGeneration.incrementAndGet()) {
        if (isManuallyStopped) return
        val request = Request.Builder().url(WS_URL)
            .header("User-Agent", "Oko/${BuildConfig.VERSION_NAME} (Android)")
            .build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionGeneration.get() != gen || isManuallyStopped) {
                    webSocket.close(1000, "superseded")
                    return
                }
                activeWebSocket = webSocket
                val now = System.currentTimeMillis()
                val nowMono = Monotonic.now()
                reconnectAttempt = 0
                openedAtMs = nowMono
                lastFrameAtMs = now
                _lastSocketFrame.value = now
                lastSocketFrameMono = nowMono
                persistedReconnectStartMs = 0L
                _lastError.value = null
                _connectionState.value = ConnectionState.Connected(
                    generation = gen,
                    openedAtMs = now,
                    lastFrameAtMs = now
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionGeneration.get() != gen) return
                val now = System.currentTimeMillis()
                lastFrameAtMs = now
                _lastSocketFrame.value = now
                lastSocketFrameMono = Monotonic.now()

                if (_connectionState.value is ConnectionState.Degraded) {
                    _connectionState.value = ConnectionState.Connected(
                        generation = gen,
                        openedAtMs = openedAtMs,
                        lastFrameAtMs = now
                    )
                }
                frames.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionGeneration.get() != gen) return
                handleDisconnect(gen, reason = reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionGeneration.get() != gen) return
                webSocket.close(1001, t.message)
                _lastError.value = t.message
                handleDisconnect(gen, reason = t.message)
            }
        })
    }

    private fun handleDisconnect(gen: Int, reason: String?) {
        if (isManuallyStopped || connectionGeneration.get() != gen) return
        // One-shot per socket generation: onClosed and onFailure can both fire for the same
        // socket, and only the first disconnect handling may run regardless of timing.
        if (disconnectHandledGen.getAndSet(gen) == gen) return
        val now = System.currentTimeMillis()
        val previousState = _connectionState.value
        val offlineSince = previousState.offlineSinceOrNull ?: now
        val recStart = when {
            persistedReconnectStartMs > 0L -> persistedReconnectStartMs
            previousState.reconnectStartMillisOrZero > 0L -> previousState.reconnectStartMillisOrZero
            else -> now
        }
        persistedReconnectStartMs = recStart
        openedAtMs = 0L
        _connectionState.value = ConnectionState.Offline(
            since = offlineSince,
            reconnectStartMillis = recStart,
            reason = reason,
            attempt = reconnectAttempt
        )
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (isManuallyStopped || isIgnoringPause()) return
        if (openedAtMs > 0L && Monotonic.now() - openedAtMs > 60_000L) {
            reconnectAttempt = reconnectAttempt.coerceAtMost(2)
        }
        reconnectAttempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val isNetValidated = networkMonitor.isValidated.value
            val delayMs = if (isNetValidated) calculateBackoffMs(reconnectAttempt) else NO_NETWORK_RECONNECT_MS
            val now = System.currentTimeMillis()
            val gen = connectionGeneration.incrementAndGet()

            _connectionState.value = ConnectionState.Connecting(
                generation = gen,
                attempt = reconnectAttempt,
                nextRetryAtMs = now + delayMs,
                networkValidated = isNetValidated
            )
            delay(delayMs)
            if (!isManuallyStopped && !isIgnoringPause()) {
                connect(gen)
            }
        }
    }

    private fun schedulePauseExpiry() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val remaining = ignoreUntilMs - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            if (!isManuallyStopped) {
                reconnectAttempt = 0
                connect()
            }
        }
    }
}