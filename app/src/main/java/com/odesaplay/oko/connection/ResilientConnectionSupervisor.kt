package com.odesaplay.oko.connection

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
import com.odesaplay.oko.ConnectionLog
import com.odesaplay.oko.ConnStatus
import com.odesaplay.oko.NetTransport
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
 * Combines OS-level network gating, a 168-second byte silence hardware watchdog,
 * full-jitter backoff, and monotonic clock telemetry into a single authoritative supervisor.
 *
 * Invariants:
 * 1. OS Network Gate: Does NOT spin reconnect loops while offline. Wakes immediately upon network validation.
 * 2. Byte-Silence Watchdog: tears down a dead socket after SILENCE_TIMEOUT_MS of observed silence.
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
        /** Quiet-feed edge: no frame for this long and the socket stops claiming delivery. 4m20s. */
        const val DEGRADED_STALE_MS = 260_000L
        const val WATCHDOG_TICK_MS = 30_000L
        /** Byte-silence backstop: a socket that pongs but never speaks is force-restarted after this
         *  much OBSERVED silence. Derived as the quiet edge plus a few watchdog ticks, not a multiple
         *  of it, so raising the edge can never put the reaper in front of it. OkHttp's 60s ping is
         *  the fast local detector; this only mops up. */
        const val SILENCE_TIMEOUT_MS = DEGRADED_STALE_MS + 4 * WATCHDOG_TICK_MS
        /** Most awake time one tick may credit. A process thawed after a freeze reports a gap the
         *  loop was never awake to observe, so an uncapped credit mints a fake quiet episode on
         *  resume. Kept well under [DEGRADED_STALE_MS] for exactly that reason. */
        const val WATCHDOG_AWAKE_CREDIT_MS = WATCHDOG_TICK_MS * 2
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
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

        /** What the byte-silence watchdog should do at [silenceMs] of unbeaten quiet. Extracted so
         *  both thresholds and their ordering are testable without a live socket. There is no
         *  "first byte" guard here — the caller owns "connected and has heard a byte at least once".
         *  TEAR_DOWN outranks DEGRADE, so a socket already past the reaper is never first reported
         *  degraded, and a running quiet episode is not re-reported on every tick. */
        internal fun silenceWatchdogAction(silenceMs: Long, alreadyDegraded: Boolean): SilenceWatchdogAction = when {
            silenceMs >= SILENCE_TIMEOUT_MS -> SilenceWatchdogAction.TEAR_DOWN
            silenceMs >= DEGRADED_STALE_MS && !alreadyDegraded -> SilenceWatchdogAction.DEGRADE
            else -> SilenceWatchdogAction.NONE
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite read timeout for long-lived WS
        .pingInterval(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var activeWebSocket: WebSocket? = null
    private val isNetworkValidated = AtomicBoolean(false)
    /** All currently attached validated networks; a teardown is only justified when this drains. */
    private val validatedNetworks = ValidatedNetworkTracker<Network>()
    private val isRunning = AtomicBoolean(false)
    /** Only executeConnect mints generations; closeCurrentSocket never bumps it. */
    private val connectionGeneration = AtomicInteger(0)
    /** One-shot per generation: onFailure and onClosed both fire per socket, only the first
     *  may schedule a reconnect. */
    private val disconnectHandledGen = AtomicInteger(-1)
    private val lastIncomingByteMono = AtomicLong(0L)
    /**
     * The same "last byte" stamp, but on the watchdog's OWN accumulated awake time instead of
     * `elapsedRealtime`.
     *
     * `elapsedRealtime` advances through deep sleep while our coroutine does not run, so the
     * first tick after a doze saw hours of "silence" and force-restarted a healthy socket — the
     * overnight flapping on devices without a battery exemption. Silence must be measured in
     * time the watchdog was actually awake to observe it.
     */
    private val lastIncomingByteAwake = AtomicLong(0L)
    /** Awake milliseconds accumulated by the watchdog loop; see [lastIncomingByteAwake]. */
    private val watchdogAwakeMs = AtomicLong(0L)
    private val reconnectAttempts = AtomicInteger(0)
    /** Last monotonic stamp of reconnect progress (schedule/connect/trigger); drives the
     *  stuck-offline watchdog. */
    private val lastReconnectProgressMono = AtomicLong(0L)
    /** Host the feed resolves to, named in a DNS failure so the log says which name, not just "DNS". */
    private val endpointHost: String =
        runCatching { java.net.URI(endpointUrl).host }.getOrNull().orEmpty()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connEvents = MutableStateFlow<List<ConnEvent>>(emptyList())
    val connEvents: StateFlow<List<ConnEvent>> = _connEvents.asStateFlow()

    private val _retryState = MutableStateFlow<ConnRetryState?>(null)
    val retryState: StateFlow<ConnRetryState?> = _retryState.asStateFlow()

    val lastSocketFrame = MutableStateFlow(0L)

    private var connectJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var activeSource: String? = null
    /** Last transport Android reported as active; drives the logged row's network badge. */
    @Volatile private var transport: NetTransport? = null

    private fun updateConnectionState(newState: ConnectionState) {
        _connectionState.value = newState
        val status = when (newState) {
            is ConnectionState.Connected -> ConnStatus.ONLINE
            is ConnectionState.Degraded -> ConnStatus.DEGRADED
            is ConnectionState.Offline, ConnectionState.Disconnected -> ConnStatus.OFFLINE
            is ConnectionState.Connecting -> return // keep previous state until it resolves
        }
        ConnectionLog.observe(status, System.currentTimeMillis(), activeSource, transport)
    }

    /** Map the active network's capabilities to the logged transport (null = no network). */
    private fun transportOf(caps: NetworkCapabilities?): NetTransport? = when {
        caps == null -> null
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetTransport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetTransport.CELLULAR
        else -> NetTransport.OTHER
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            transport = transportOf(capabilities)
            val valid = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (valid) {
                validatedNetworks.add(network)
                if (isNetworkValidated.compareAndSet(false, true)) {
                    recordEvent(ConnEventKind.FALLBACK_RESTORED, detail = "Network validated")
                    if (isRunning.get() && isDownForReconnect()) {
                        reconnectAttempts.set(0)
                        triggerReconnect()
                    }
                }
            } else {
                // Only a fully drained set is an outage; losing one of several interfaces is not.
                if (validatedNetworks.remove(network)) isNetworkValidated.set(false)
            }
        }

        override fun onLost(network: Network) {
            // A lost secondary interface (e.g. Wi-Fi while LTE is up) must not tear the socket
            // down; only the last validated network going away is a real outage.
            if (validatedNetworks.remove(network)) handleNetworkLost(OfflineCause.NetworkLost(null))
        }
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

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
        if (valid && activeNet != null) validatedNetworks.add(activeNet)
        isNetworkValidated.set(valid)
        transport = transportOf(caps)

        startWatchdogLoop()
        lastReconnectProgressMono.set(Monotonic.now())

        if (valid) {
            triggerReconnect()
        } else {
            val now = System.currentTimeMillis()
            updateConnectionState(ConnectionState.Offline(
                since = now,
                reconnectStartMillis = now,
                cause = OfflineCause.NoNetwork
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
        closeCurrentSocket()
        updateConnectionState(ConnectionState.Disconnected)
    }

    private fun handleNetworkLost(cause: OfflineCause) {
        val wasDown = _connectionState.value is ConnectionState.Offline ||
                _connectionState.value is ConnectionState.Connecting
        validatedNetworks.clear()
        isNetworkValidated.set(false)
        closeCurrentSocket()
        connectJob?.cancel()
        // No job is scheduled from here, so any posted countdown is phantom — clear it.
        _retryState.value = null
        val now = System.currentTimeMillis()
        val prev = _connectionState.value
        updateConnectionState(ConnectionState.Offline(
            since = prev.offlineSinceOrNull ?: now,
            reconnectStartMillis = now,
            cause = cause
        ))
        if (!wasDown) recordEvent(ConnEventKind.NO_NETWORK)
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.Default) {
            var previousTickMono = Monotonic.now()
            while (isActive && isRunning.get()) {
                delay(WATCHDOG_TICK_MS)
                val nowMono = Monotonic.now()
                // Awake time only: the gap between two ticks is the only stretch we were actually
                // running to hear a frame. Capped at WATCHDOG_AWAKE_CREDIT_MS so a stall cannot bank
                // credit — a thaw must not look like quiet the loop never observed.
                val awake = watchdogAwakeMs.addAndGet(
                    (nowMono - previousTickMono).coerceIn(0L, WATCHDOG_AWAKE_CREDIT_MS)
                )
                previousTickMono = nowMono
                onWatchdogTick?.invoke(nowMono)

                val cs = _connectionState.value
                val lastByte = lastIncomingByteMono.get()

                if (cs.isConnected && lastByte > 0L) {
                    val silenceDuration = awake - lastIncomingByteAwake.get()
                    when (silenceWatchdogAction(silenceDuration, cs is ConnectionState.Degraded)) {
                        // SILENCE_TIMEOUT_MS of OBSERVED silence without a byte -> force tear-down
                        SilenceWatchdogAction.TEAR_DOWN -> {
                            Log.w(TAG, "Byte Silence Watchdog expired ($silenceDuration ms) - forcing socket restart")
                            recordEvent(ConnEventKind.CONNECTION_LOST, detail = "${SILENCE_TIMEOUT_MS / 1000}s silence timeout")
                            closeCurrentSocket()
                            scheduleReconnectWithBackoff(OfflineCause.SilenceTimeout)
                        }
                        SilenceWatchdogAction.DEGRADE -> {
                            val gen = connectionGeneration.get()
                            updateConnectionState(ConnectionState.Degraded(
                                generation = gen,
                                openedAtMs = System.currentTimeMillis() - silenceDuration,
                                lastFrameAtMs = System.currentTimeMillis() - silenceDuration,
                                quietDurationMs = silenceDuration
                            ))
                            recordEvent(ConnEventKind.DEGRADED)
                        }
                        SilenceWatchdogAction.NONE -> Unit
                    }
                }

                val csNow = _connectionState.value
                if (csNow is ConnectionState.Offline || csNow is ConnectionState.Connecting) {
                    if (!isNetworkValidated.get()) {
                        pollNetworkValidation()
                    } else {
                        checkStuckOffline(nowMono)
                    }
                }
            }
        }
    }

    /** Stuck-state watchdog: Offline/Connecting with a live network but no reconnect progress
     *  (no scheduled job running, no recent schedule/connect) gets force-retried. Covers the
     *  weak-WiFi stall where a backoff job was lost and validation never flapped. */
    private fun checkStuckOffline(nowMono: Long) {
        if (!isNetworkValidated.get()) return
        if (connectJob?.isActive == true) return
        if (nowMono - lastReconnectProgressMono.get() >= STUCK_OFFLINE_MS) {
            recordEvent(ConnEventKind.RETRY_SCHEDULED, detail = "Stuck watchdog")
            triggerReconnect()
        }
    }

    /** Read the OS's verdict on the active network into our cache; returns whether it is validated.
     *  The cache gates every reconnect path and the OS never re-emits a callback for a link it
     *  already considers valid — so a freeze or an overnight pause can leave it stale-false over a
     *  perfectly good link. Anything the user can invoke (foreground, manual retry) re-reads here
     *  rather than trusting it. Never clears the flag: a null read is "unknown", not "offline". */
    private fun refreshNetworkValidation(): Boolean {
        val caps = runCatching {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        }.getOrNull() ?: return false
        transport = transportOf(caps)
        val valid = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!valid) return false
        connectivityManager.activeNetwork?.let { validatedNetworks.add(it) }
        isNetworkValidated.set(true)
        return true
    }

    /** Proactive validation recheck while down. The OS doesn't always emit a callback when
     *  usability returns (a validation flap mid-connect can strand us with no job and no
     *  future event), so poll the capabilities on the watchdog tick and take the standard
     *  restore path the moment they validate. One cheap binder call per tick — no sockets,
     *  no attempts, no log spam while still down. */
    private fun pollNetworkValidation() {
        val wasValid = isNetworkValidated.get()
        if (!refreshNetworkValidation()) return
        if (wasValid) return
        recordEvent(ConnEventKind.FALLBACK_RESTORED, detail = "Network validated (poll)")
        if (isRunning.get() && isDownForReconnect()) {
            reconnectAttempts.set(0)
            triggerReconnect()
        }
    }

    private fun isDownForReconnect(): Boolean {
        val cs = _connectionState.value
        return cs is ConnectionState.Offline || cs is ConnectionState.Disconnected
    }

    /** Connect now, ignoring the backoff. No reason parameter: every caller used to pass a label
     *  that nothing read, and a free-text note is exactly what a typed [OfflineCause] replaced. */
    fun triggerReconnect() {
        if (!isRunning.get()) return
        lastReconnectProgressMono.set(Monotonic.now())
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            executeConnect()
        }
    }

    /** Backoff-scheduled reconnect. [cause] is the WHY of the current episode and rides onto the
     *  state it publishes, so the notification, the log line and the retry countdown can never
     *  describe three different outages. */
    private fun scheduleReconnectWithBackoff(cause: OfflineCause) {
        // Schedule the timer regardless of validation; the socket is only created when the
        // network is validated at fire time, so a genuinely dead network never spins.
        if (!isNetworkValidated.get()) {
            handleNetworkLost(OfflineCause.NoNetwork)
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
                reconnectStartMillis = now,
                cause = cause,
                attempt = attempt
            ))

            delay(delayMs)
            if (isRunning.get() && isNetworkValidated.get()) {
                executeConnect()
            }
        }
    }

    private fun executeConnect() {
        closeCurrentSocket()
        lastReconnectProgressMono.set(Monotonic.now())
        val gen = connectionGeneration.incrementAndGet()
        updateConnectionState(ConnectionState.Connecting(
            generation = gen,
            attempt = reconnectAttempts.get(),
            nextRetryAtMs = 0L,
            networkValidated = isNetworkValidated.get(),
            reconnectStartMillis = System.currentTimeMillis()
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
                lastIncomingByteAwake.set(watchdogAwakeMs.get())
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
                lastIncomingByteAwake.set(watchdogAwakeMs.get())
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
                if (disconnectHandledGen.getAndSet(gen) == gen) return
                activeWebSocket = null
                val cause = OfflineCause.from(t, endpointHost)
                recordEvent(ConnEventKind.CONNECTION_LOST, detail = t.message)
                scheduleReconnectWithBackoff(cause)
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
                if (isRunning.get()) {
                    scheduleReconnectWithBackoff(OfflineCause.SocketFailure("closed ($code)"))
                }
            }
        })
    }

    /** Closes the socket without minting a generation — only executeConnect mints, so an
     *  in-flight failure can never race a fresh connect into a stranded Offline. The current
     *  generation's disconnect is claimed here, so the socket's own onFailure/onClosed can
     *  never double-schedule behind an intentional close. */
    private fun closeCurrentSocket() {
        try {
            disconnectHandledGen.set(connectionGeneration.get())
            activeWebSocket?.cancel()
            activeWebSocket = null
            okHttpClient.dispatcher.cancelAll()
        } catch (_: Exception) {}
    }

    fun retryNow() {
        reconnectAttempts.set(0)
        _retryState.value = null
        // A manual retry must not inherit a stale gate: after an overnight pause the OS may have
        // validated the link without our callback observing it, and a socket built while the flag
        // reads false fails straight back to Offline — the "retry does nothing" report. The refresh
        // is skipped while stopped (nothing to reconnect), but the audit row is still recorded.
        if (isRunning.get()) refreshNetworkValidation()
        recordEvent(ConnEventKind.RETRY_MANUAL)
        triggerReconnect()
    }

    fun onForeground() {
        if (!isRunning.get()) return
        // Same stale-gate hazard: returning to the app is exactly when the user expects it to come
        // back, and the OS re-emits nothing for a link it already considers valid.
        refreshNetworkValidation()
        val cs = _connectionState.value
        if (cs.isOffline || cs is ConnectionState.Connecting) {
            triggerReconnect()
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

/** What the byte-silence watchdog should do on one tick, decided by
 *  [ResilientConnectionSupervisor.silenceWatchdogAction]. */
internal enum class SilenceWatchdogAction { NONE, DEGRADE, TEAR_DOWN }
