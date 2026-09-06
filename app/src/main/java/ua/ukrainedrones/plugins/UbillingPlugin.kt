package ua.ukrainedrones.plugins

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.ManifestResult
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.data.TelegramNotifier
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OperationalMode
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.SourceTestResult
import ua.ukrainedrones.engine.SourceType
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource
import java.util.concurrent.TimeUnit

/**
 * Ubilling aerial-alerts REST source — a siren-capable fallback that engages as soon as the
 * primary WS source stops delivering (degraded), regardless of the offline-notification grace.
 * Battery-aware: it is idle whenever the primary is healthy, and its polling interval adapts
 * to whether the app is in the foreground. See interval table in [computeIntervalMs].
 *
 * Merge policy (takeover): while this plugin is actively polling (primary down past grace),
 * its full oblast snapshot is authoritative for the regions it reports. On recovery it stops
 * and clears, handing ownership back to the primary.
 */
class UbillingPlugin(
    private val context: Context? = null,
    private val primaryHealthy: Flow<Boolean>,
    private val appForeground: Flow<Boolean>
) : ThreatSource {

    override val id = "ubilling"
    override val name = "Ubilling"
    override val sourceType = SourceType.REST
    override val typeCatalog: Map<String, ThreatProps> = emptyMap()

    private val _threats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    private val _operationalMode = MutableStateFlow(OperationalMode.STANDBY)
    override val operationalMode: StateFlow<OperationalMode> = _operationalMode.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null

    /** When the primary last went unhealthy (null = healthy). Drives engagement timing. */
    private var offlineSince: Long? = null

    /** Exponential backoff applied after consecutive poll failures (2s → 4s → 8s cap). */
    private var backoffMs = 0L

    override fun start(scope: CoroutineScope) {
        pollJob = scope.launch {
            var foreground = false
            launch {
                appForeground.collect { fg -> foreground = fg }
            }
            var primaryHealthyState = true
            launch {
                primaryHealthy.collect { healthy ->
                    primaryHealthyState = healthy
                    offlineSince = if (healthy) null else (offlineSince ?: System.currentTimeMillis())
                    // Drop to STANDBY immediately when the primary recovers.
                    if (healthy) {
                        _operationalMode.value = OperationalMode.STANDBY
                        _alerts.value = emptyList()
                        _connectionState.value = PluginConnectionState.DISCONNECTED
                        backoffMs = 0L
                    }
                }
            }
            while (isActive) {
                val interval = computeIntervalMs(offlineSince, foreground, primaryHealthyState, _enabled.value)
                if (interval == null) {
                    // Idle: no polling. Re-evaluate frequently enough to catch a state change.
                    delay(5_000)
                    continue
                }
                val succeeded = pollOnce()
                if (succeeded) backoffMs = 0L
                else backoffMs = if (backoffMs == 0L) BACKOFF_INITIAL else (backoffMs * 2).coerceAtMost(BACKOFF_MAX)
                // After a poll, wait the computed interval (plus any backoff from a failure).
                delay(interval + backoffMs)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        _alerts.value = emptyList()
        _connectionState.value = PluginConnectionState.DISCONNECTED
        _operationalMode.value = OperationalMode.STANDBY
        offlineSince = null
        _enabled.value = true
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) {
            _alerts.value = emptyList()
            _connectionState.value = PluginConnectionState.DISCONNECTED
            _operationalMode.value = OperationalMode.STANDBY
            backoffMs = 0L
        }
    }

    /** One-shot live fetch for the Sources tab Test button — independent of the polling loop. */
    override suspend fun testConnection(): SourceTestResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(API_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SourceTestResult(false, "HTTP ${response.code}")
                } else {
                    val alerts = parseStates(response.body?.string().orEmpty())
                    SourceTestResult(true, "OK · ${alerts.size} oblasts alerting")
                }
            }
        } catch (e: Exception) {
            SourceTestResult(false, e.message ?: "fetch failed")
        }
    }

    /**
     * Interval in ms, or null when the plugin should be idle (no polling).
     * Primary CONNECTED → idle. Any degradation (off, silent, down) → poll; faster in the
     * foreground, and never faster than [POLL_FAST_MS]. The loop's own idle re-check cadence
     * (~5s) debounces short blips, so no separate grace is needed here.
     */
    internal fun computeIntervalMs(
        offlineSince: Long?,
        foreground: Boolean,
        healthy: Boolean,
        enabled: Boolean
    ): Long? {
        if (!enabled) return null
        if (healthy || offlineSince == null) return null
        return if (foreground) POLL_FAST_MS else POLL_BG_MS
    }

    private suspend fun pollOnce(): Boolean {
        _operationalMode.value = OperationalMode.POLLING
        val success = fetch()
        _connectionState.value = if (success) PluginConnectionState.CONNECTED else PluginConnectionState.OFFLINE
        return success
    }

    /** Fetch and parse the ubilling snapshot into [alerts]. Returns false on any failure. */
    private suspend fun fetch(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(API_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                _alerts.value = parseStates(body)
                checkSchema(body)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun checkSchema(body: String) {
        val ctx = context ?: return
        val result = ApiMonitor.checkUbillingSchema(ctx, body)
        when (result) {
            is ManifestResult.Changed -> {
                ApiMonitor.record(
                    SystemEntry(System.currentTimeMillis(), SystemEntryKind.UBILLING_SCHEMA_CHANGED,
                        "SHA256: ${result.oldHash.take(16)} -> ${result.newHash.take(16)}")
                )
                TelegramNotifier.sendUbillingSchemaChanged(result.oldHash, result.newHash)
            }
            is ManifestResult.Failed -> {
                Log.w(TAG, "Ubilling schema check failed: ${result.message}")
            }
            ManifestResult.Unchanged -> { /* no-op */ }
        }
    }

    /** Parse a ubilling `states` snapshot into [OblastAlert] entries where `alertnow == true`. */
    internal fun parseStates(body: String): List<OblastAlert> {
        val states = JSONObject(body).optJSONObject("states") ?: return emptyList()
        val list = ArrayList<OblastAlert>()
        val keys = states.keys()
        while (keys.hasNext()) {
            val oblast = keys.next()
            val state = states.optJSONObject(oblast) ?: continue
            if (state.optBoolean("alertnow")) {
                list.add(OblastAlert(key = "ubilling:$oblast", name = oblast, oblast = oblast, since = null))
            }
        }
        return list
    }

    companion object {
        private const val TAG = "UbillingPlugin"
        private const val API_URL = "https://ubilling.net.ua/aerialalerts/"
        internal const val POLL_FAST_MS = 15_000L
        internal const val POLL_BG_MS = 30_000L
        internal const val BACKOFF_INITIAL = 2_000L
        internal const val BACKOFF_MAX = 8_000L
    }
}
