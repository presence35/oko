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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import ua.ukrainedrones.OblastAlert
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.BatteryOptimization
import ua.ukrainedrones.showToast
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.fallbackCourse
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.normalizedThreatFromJson
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-reliability WebSocket client for the NEPTUN telemetry feed.
 *
 * Architecture:
 * 1. Strict generation-based WebSocket lifecycle ([connectionGeneration]) to eliminate socket identity races.
 * 2. Unambiguous [ConnectionState] state machine.
 * 3. Sequential frame processing via [Channel] to guarantee FIFO order across frames.
 * 4. Prevents watchdog pacification by never resetting [lastFrameAtMs] on UI foreground.
 * 5. Gated reconnect on validated network changes (via [NetworkMonitor]) preventing captive-portal hammer.
 * 6. Isolated threat data freshness ([threatDataStale]) vs socket connection freshness tracking.
 * 7. Isolated test harness seam for test MiG injection and offline simulation.
 */
class NeptunConnectionClient(
    private val context: Context,
    private val client: OkHttpClient = defaultHttpClient()
) {

    companion object {
        const val OFFLINE_GRACE_MS = 5_000L
        const val DEGRADED_STALE_MS = 30_000L
        const val WATCHDOG_STALE_MS = 45_000L
        const val THREAT_DATA_STALE_MS = 120_000L
        const val USER_SHOT_GRACE_MS = 3_000L
        const val RECENT_REMOVED_GRACE_MS = 60_000L
        const val NO_NETWORK_RECONNECT_MS = 60_000L

        const val NEPTUN_DOMAIN = "neptun.in.ua"
        internal const val NEPTUN_SITE_URL = "https://$NEPTUN_DOMAIN/"

        fun calculateBackoffMs(attempt: Int): Long = when {
            attempt <= 1 -> 1000L + (0..2000).random()
            else -> minOf(15_000L, 1000L * (1 shl (attempt - 1))) + (0..400).random()
        }

        private const val WS_URL = "wss://neptun.in.ua/api/v1/stream"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkMonitor = NetworkMonitor(context)
    private val frameChannel = Channel<String>(capacity = Channel.UNLIMITED)

    // Generation counter for all socket instances
    private val connectionGeneration = AtomicInteger(0)
    private var activeWebSocket: WebSocket? = null

    // State Flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _threats = MutableStateFlow<Map<String, NormalizedThreat>>(emptyMap())
    val threats: StateFlow<Map<String, NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _removedThreats = MutableSharedFlow<ThreatRemoved>(extraBufferCapacity = 16)
    val removedThreats: SharedFlow<ThreatRemoved> = _removedThreats.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Explicit Separated Freshness Tracking
    private val _lastSocketFrame = MutableStateFlow(0L)
    val lastSocketFrame: StateFlow<Long> = _lastSocketFrame.asStateFlow()

    private val _lastValidThreatUpdate = MutableStateFlow(0L)
    val lastValidThreatUpdate: StateFlow<Long> = _lastValidThreatUpdate.asStateFlow()

    private val _lastValidSnapshot = MutableStateFlow(0L)
    val lastValidSnapshot: StateFlow<Long> = _lastValidSnapshot.asStateFlow()

    private val _threatDataStale = MutableStateFlow(false)
    val threatDataStale: StateFlow<Boolean> = _threatDataStale.asStateFlow()

    // Tracking
    private var openedAtMs = 0L
    @Volatile private var lastFrameAtMs = 0L
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var isManuallyStopped = false

    // User-shot grace tracking and recently removed tombstone tracking
    private val userShotAt = ConcurrentHashMap<String, Long>()
    private val recentlyRemovedThreats = ConcurrentHashMap<String, Long>()

    // Mutex to serialize _threats.value mutations from WS paths
    private val threatsMutex = Mutex()

    // Pause / Ignore state
    private var ignoreUntilMs = 0L
    private var persistedReconnectStartMs = 0L

    init {
        startNetworkObserver()
        startFrameProcessor()
        startWatchdog()
    }

    private fun startFrameProcessor() {
        scope.launch {
            for (text in frameChannel) {
                handleFrame(text)
            }
        }
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
        connect()
    }

    fun wasUserShotRecently(threatId: String): Boolean {
        val shotAt = userShotAt[threatId] ?: return false
        return System.currentTimeMillis() - shotAt <= USER_SHOT_GRACE_MS
    }

    fun stop() {
        isManuallyStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        val gen = connectionGeneration.incrementAndGet()
        activeWebSocket?.close(1000, "client stop")
        activeWebSocket = null
        _connectionState.value = ConnectionState.Disconnected
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

    fun markUserShot(id: String) {
        if (id.isNotBlank()) {
            userShotAt[id] = System.currentTimeMillis()
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

    fun registerUserShot(threatId: String) {
        userShotAt[threatId] = System.currentTimeMillis()
    }

    private fun connect(gen: Int = connectionGeneration.incrementAndGet()) {
        if (isManuallyStopped) return
        val request = Request.Builder().url(WS_URL).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionGeneration.get() != gen || isManuallyStopped) {
                    webSocket.close(1000, "superseded")
                    return
                }
                activeWebSocket = webSocket
                val now = System.currentTimeMillis()
                reconnectAttempt = 0
                openedAtMs = now
                lastFrameAtMs = now
                _lastSocketFrame.value = now
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

                if (_connectionState.value is ConnectionState.Degraded) {
                    _connectionState.value = ConnectionState.Connected(
                        generation = gen,
                        openedAtMs = openedAtMs,
                        lastFrameAtMs = now
                    )
                }
                frameChannel.trySend(text)
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
                activeWebSocket?.close(1001, t.message)
                _lastError.value = t.message
                handleDisconnect(gen, reason = t.message)
            }
        })
    }

    private fun handleDisconnect(gen: Int, reason: String?) {
        if (isManuallyStopped || connectionGeneration.get() != gen) return
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
        _threatDataStale.value = false
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
        if (openedAtMs > 0L && System.currentTimeMillis() - openedAtMs > 60_000L) {
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

    private val knownTypeKeys = ThreatType.entries.map { it.apiKey }.toSet() +
        setOf("uav", "drone", "lancet", "molniya", "loitering", "missile", "cruise_missile", "mig31", "mig31k", "kinzhal")
    private val unknownTypeLastSeen = ConcurrentHashMap<String, Long>()
    private val UNKNOWN_TYPE_TOAST_COOLDOWN_MS = 60_000L

    private fun startWatchdog() {
        // Watchdog & Degraded State transition check
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (isManuallyStopped) return@launch
                val now = System.currentTimeMillis()
                val socketQuietFor = now - _lastSocketFrame.value
                val threatQuietFor = if (_lastValidThreatUpdate.value > 0L) now - _lastValidThreatUpdate.value else 0L

                val currentState = _connectionState.value
                if (currentState.isConnected && _lastSocketFrame.value > 0L && socketQuietFor > DEGRADED_STALE_MS && currentState !is ConnectionState.Degraded) {
                    _connectionState.value = ConnectionState.Degraded(
                        generation = (currentState as? ConnectionState.Connected)?.generation ?: 0,
                        openedAtMs = openedAtMs,
                        lastFrameAtMs = _lastSocketFrame.value,
                        quietDurationMs = socketQuietFor
                    )
                }

                if (currentState.isConnected && _lastValidThreatUpdate.value > 0L && threatQuietFor >= THREAT_DATA_STALE_MS) {
                    _threatDataStale.value = true
                } else if (!currentState.isConnected) {
                    _threatDataStale.value = false
                }

                if (currentState.isConnected && _lastSocketFrame.value > 0L && socketQuietFor > WATCHDOG_STALE_MS) {
                    // Trigger watchdog reconnect
                    activeWebSocket?.close(1001, "watchdog stale")
                }
            }
        }
    }

    private suspend fun handleFrame(text: String) {
        try {
            val env = JSONObject(text)
            val frameType = env.optString("type")
            val now = System.currentTimeMillis()

            when (frameType) {
                "snapshot" -> {
                    val data = env.optJSONObject("data") ?: return
                    val arr = data.optJSONArray("threats") ?: return
                    threatsMutex.withLock {
                        val map = LinkedHashMap<String, NormalizedThreat>()
                        for (i in 0 until arr.length()) {
                            try {
                                val obj = arr.getJSONObject(i)
                                val rawType = if (obj.has("type") && !obj.isNull("type")) obj.optString("type") else null
                                if (rawType != null && rawType !in knownTypeKeys) {
                                    recordUnknownType(rawType)
                                }
                                val t = normalizedThreatFromJson(obj) ?: continue
                                map[t.id] = t
                            } catch (e: Exception) {
                                Log.w("NeptunClient", "Malformed WS threat at index $i", e)
                            }
                        }
                        val prev = _threats.value
                        // Preserve user-shot drones within grace window
                        for (id in prev.keys) {
                            if (id in map) continue
                            val shotAt = userShotAt[id] ?: continue
                            if (now - shotAt <= USER_SHOT_GRACE_MS) {
                                map[id] = prev.getValue(id)
                            }
                        }
                        userShotAt.entries.removeIf { now - it.value > USER_SHOT_GRACE_MS }
                        recentlyRemovedThreats.entries.removeIf { now - it.value > RECENT_REMOVED_GRACE_MS }

                        _threats.value = map
                        _lastValidThreatUpdate.value = now
                        _lastValidSnapshot.value = now
                        _threatDataStale.value = false
                    }
                }
                "upsert" -> {
                    val data = env.optJSONObject("data") ?: return
                    val rawType = if (data.has("type") && !data.isNull("type")) data.optString("type") else null
                    if (rawType != null && rawType !in knownTypeKeys) {
                        recordUnknownType(rawType)
                    }
                    val t = normalizedThreatFromJson(data) ?: return
                    threatsMutex.withLock {
                        val updated = _threats.value.toMutableMap()
                        if (t.status == "resolved") {
                            recentlyRemovedThreats[t.id] = now
                            _removedThreats.tryEmit(
                                ThreatRemoved(t.id, t.lat, t.lon, t.type.toThreatType(), t.bearingDeg ?: t.heading ?: fallbackCourse(t.id), t.region, t.district, t.locality)
                            )
                            updated.remove(t.id)
                        } else {
                            val existing = updated[t.id]
                            val existingTime = existing?.updatedAtMillis ?: existing?.confirmedAtMillis ?: 0L
                            val newTime = t.updatedAtMillis ?: t.confirmedAtMillis ?: now
                            if (existing == null || newTime >= existingTime) {
                                updated[t.id] = t
                                recentlyRemovedThreats.remove(t.id)
                            }
                        }
                        _threats.value = updated
                        _lastValidThreatUpdate.value = now
                        _threatDataStale.value = false
                    }
                }
                "remove" -> {
                    val data = env.optJSONObject("data") ?: return
                    val id = data.optString("id")
                    threatsMutex.withLock {
                        val updated = _threats.value.toMutableMap()
                        recentlyRemovedThreats[id] = now
                        updated.remove(id)?.let { gone ->
                            _removedThreats.tryEmit(
                                ThreatRemoved(gone.id, gone.lat, gone.lon, gone.type.toThreatType(), gone.bearingDeg ?: gone.heading ?: fallbackCourse(gone.id), gone.region, gone.district, gone.locality)
                            )
                        }
                        _threats.value = updated
                        _lastValidThreatUpdate.value = now
                        _threatDataStale.value = false
                    }
                }
                "alerts" -> {
                    val data = env.optJSONObject("data") ?: return
                    val list = mutableListOf<OblastAlert>()
                    for (arrName in listOf("raions", "oblasts")) {
                        val arr = data.optJSONArray(arrName) ?: continue
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(
                                OblastAlert(
                                    key = o.optString("key"),
                                    name = o.optString("name"),
                                    oblast = o.optString("oblast"),
                                    since = o.optString("since", null)
                                )
                            )
                        }
                    }
                    _alerts.value = list
                }
                "heartbeat" -> {
                    // Socket frame keep-alive; does not update threat freshness
                }
            }
        } catch (e: Exception) {
            Log.w("NeptunClient", "Malformed frame", e)
            ApiMonitor.record(SystemEntry(
                atMillis = System.currentTimeMillis(),
                kind = SystemEntryKind.MALFORMED_FRAME,
                detail = text.take(200)
            ))
        }
    }

    private fun recordUnknownType(rawType: String) {
        val now = System.currentTimeMillis()
        val lastSeen = unknownTypeLastSeen[rawType] ?: 0L
        if (now - lastSeen > UNKNOWN_TYPE_TOAST_COOLDOWN_MS) {
            unknownTypeLastSeen[rawType] = now
            showToast(context, "New threat type reported: $rawType")
            ApiMonitor.record(SystemEntry(
                atMillis = now,
                kind = SystemEntryKind.UNKNOWN_TYPE_DETECTED,
                detail = rawType
            ))
        }
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
