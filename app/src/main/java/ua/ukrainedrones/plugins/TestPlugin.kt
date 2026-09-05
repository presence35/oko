package ua.ukrainedrones.plugins

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ua.ukrainedrones.LocationTracker
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.UPDATE_BASE_URL
import ua.ukrainedrones.UA_TIGHT_MIN_LAT
import ua.ukrainedrones.UA_TIGHT_MAX_LAT
import ua.ukrainedrones.UA_TIGHT_MIN_LON
import ua.ukrainedrones.UA_TIGHT_MAX_LON
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OperationalMode
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.SourceTestResult
import ua.ukrainedrones.engine.SourceType
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource
import ua.ukrainedrones.engine.bearingHaversine

/**
 * Peace-time simulator: a normal [ThreatSource] that, while enabled, plays a server-defined
 * script (`testplugin.json` on the update server) of timed threat/alert events. The JSON is
 * the single source of truth — timings, counts, types and regions can change without an APK.
 *
 * Behaviour mirrors a real source (mirror rule): emitted threats merge with the registry feed
 * by concatenation, alerts by the usual oblast-key takeover; disabling the source clears its
 * output. It is independent of NEPTUN — never injected into another plugin's feed.
 */
class TestPlugin : ThreatSource {

    override val id = "test"
    override val name = "Test"
    override val sourceType = SourceType.WS
    override val typeCatalog: Map<String, ThreatProps> = emptyMap()

    private val _operationalMode = MutableStateFlow(OperationalMode.STANDBY)
    override val operationalMode: StateFlow<OperationalMode> = _operationalMode.asStateFlow()

    private val _threats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _connectionState = MutableStateFlow(PluginConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var scope: CoroutineScope? = null
    private var scriptJob: Job? = null
    private var lastError: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    override fun stop() {
        scope = null
        scriptJob?.cancel()
        scriptJob = null
        _enabled.value = false
        _threats.value = emptyList()
        _alerts.value = emptyList()
        _connectionState.value = PluginConnectionState.DISCONNECTED
        _operationalMode.value = OperationalMode.STANDBY
    }

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        val activeScope = scope ?: return
        if (enabled) {
            if (scriptJob?.isActive == true) return
            lastError = null
            scriptJob = activeScope.launch { runScript() }
        } else {
            scriptJob?.cancel()
            scriptJob = null
            _threats.value = emptyList()
            _alerts.value = emptyList()
            _connectionState.value = PluginConnectionState.DISCONNECTED
            _operationalMode.value = OperationalMode.STANDBY
        }
    }

    override suspend fun testConnection(): SourceTestResult {
        val running = _enabled.value
        if (!running) return SourceTestResult(true, "disabled — enable on the Sources tab")
        val state = _connectionState.value
        return if (state == PluginConnectionState.OFFLINE) {
            SourceTestResult(false, lastError ?: "script fetch failed")
        } else {
            SourceTestResult(
                true,
                "running · ${_threats.value.size} threats · ${_alerts.value.size} alerts"
            )
        }
    }

    private suspend fun runScript() {
        _operationalMode.value = OperationalMode.STREAMING
        _connectionState.value = PluginConnectionState.CONNECTED
        try {
            val events = fetchScript() ?: return
            val start = System.currentTimeMillis()
            var index = 0
            while (coroutineContext.isActive && _enabled.value) {
                if (index >= events.size) {
                    delay(1_000)
                    continue
                }
                val (atMs, action) = events[index]
                val remaining = atMs - (System.currentTimeMillis() - start)
                if (remaining > 0) delay(remaining)
                if (!coroutineContext.isActive || !_enabled.value) return
                action()
                index++
            }
        } finally {
            if (!_enabled.value) {
                _threats.value = emptyList()
                _alerts.value = emptyList()
                _connectionState.value = PluginConnectionState.DISCONNECTED
                _operationalMode.value = OperationalMode.STANDBY
            }
        }
    }

    /** Parses `testplugin.json` from the update server into a timed event list; null on failure. */
    private suspend fun fetchScript(): List<Pair<Long, () -> Unit>>? {
        return try {
            val request = Request.Builder().url(UPDATE_BASE_URL + "testplugin.json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code}"
                    _connectionState.value = PluginConnectionState.OFFLINE
                    return null
                }
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val arr = root.optJSONArray("script") ?: JSONArray()
                val parsed = ArrayList<Pair<Long, () -> Unit>>(arr.length())
                for (i in 0 until arr.length()) {
                    val ev = arr.getJSONObject(i)
                    val at = ev.optLong("at", 0L)
                    val action = parseAction(ev) ?: continue
                    parsed.add(at to action)
                }
                parsed.sortedBy { it.first }
            }
        } catch (e: Exception) {
            lastError = e.message
            _connectionState.value = PluginConnectionState.OFFLINE
            null
        }
    }

    private fun parseAction(ev: JSONObject): (() -> Unit)? {
        if (ev.has("burst")) return { spawnBurst(ev.getJSONObject("burst")) }
        if (ev.has("aviation")) return { spawnAviation(ev.getJSONObject("aviation")) }
        if (ev.has("alerts")) return { spawnAlerts(ev.getJSONArray("alerts")) }
        if (ev.has("threats")) return { spawnExplicitThreats(ev.getJSONArray("threats")) }
        if (ev.optBoolean("clearThreats", false)) return { _threats.value = emptyList() }
        if (ev.optBoolean("clearAlerts", false)) return { _alerts.value = emptyList() }
        return null
    }

    private fun spawnBurst(spec: JSONObject) {
        val types = spec.optJSONArray("types")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: listOf("shahed", "cruise")
        val count = spec.optInt("count", 3).coerceIn(1, 40)
        val minKm = spec.optDouble("minKm", 30.0)
        val maxKm = spec.optDouble("maxKm", 300.0)
        val aim = spec.optBoolean("aimAtFocus", true)
        val now = System.currentTimeMillis()
        val focus = focusOrNull()
        val spawned = (0 until count).map { i ->
            val type = types[i % types.size]
            val (lat, lon) = pointAtDistance(focus, minKm, maxKm)
            NormalizedThreat(
                id = nextId("t"),
                type = type,
                title = "Test $type",
                region = null,
                district = null,
                locality = null,
                lat = lat,
                lon = lon,
                heading = null,
                bearingDeg = if (aim && focus != null) {
                    bearingHaversine(lat, lon, focus.lat, focus.lon)
                } else {
                    ThreadLocalRandom.current().nextDouble(0.0, 360.0)
                },
                status = "active",
                advisory = false,
                areaOnly = false,
                confirmations = 1,
                reliability = "high",
                count = 1,
                explanationShort = null,
                speedKmh = speedFor(type),
                uncertaintyKm = null,
                positionQuality = "confirmed",
                confirmedAtMillis = now,
                updatedAtMillis = now,
                trail = emptyList()
            )
        }
        _threats.update { it + spawned }
    }

    private fun spawnAviation(spec: JSONObject) {
        val km = spec.optDouble("km", 150.0)
        val now = System.currentTimeMillis()
        val focus = focusOrNull()
        val (lat, lon) = pointAtDistance(focus, km, km)
        _threats.update {
            it + NormalizedThreat(
                id = nextId("av"),
                type = "aviation",
                title = "Test MiG-31K",
                region = null,
                district = null,
                locality = null,
                lat = lat,
                lon = lon,
                heading = null,
                bearingDeg = ThreadLocalRandom.current().nextDouble(0.0, 360.0),
                status = "active",
                advisory = false,
                areaOnly = false,
                confirmations = 1,
                reliability = "high",
                count = 1,
                explanationShort = null,
                speedKmh = 900.0,
                uncertaintyKm = null,
                positionQuality = "confirmed",
                confirmedAtMillis = now,
                updatedAtMillis = now,
                trail = emptyList()
            )
        }
    }

    private fun spawnAlerts(arr: JSONArray) {
        val list = ArrayList<OblastAlert>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                OblastAlert(
                    key = o.optString("key", o.optString("name", "")),
                    name = o.optString("name", ""),
                    oblast = o.optString("oblast", o.optString("name", "")),
                    since = o.optString("since").takeIf { it.isNotBlank() }
                )
            )
        }
        _alerts.value = list
    }

    private fun spawnExplicitThreats(arr: JSONArray) {
        val now = System.currentTimeMillis()
        val list = ArrayList<NormalizedThreat>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val type = o.optString("type", "shahed")
            list.add(
                NormalizedThreat(
                    id = nextId("x"),
                    type = type,
                    title = "Test $type",
                    region = o.optString("region").takeIf { it.isNotBlank() },
                    district = o.optString("district").takeIf { it.isNotBlank() },
                    locality = o.optString("locality").takeIf { it.isNotBlank() },
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    heading = null,
                    bearingDeg = if (o.has("bearing")) o.getDouble("bearing") else null,
                    status = "active",
                    advisory = false,
                    areaOnly = false,
                    confirmations = 1,
                    reliability = "high",
                    count = 1,
                    explanationShort = null,
                    speedKmh = if (o.has("speedKmh")) o.getDouble("speedKmh") else speedFor(type),
                    uncertaintyKm = null,
                    positionQuality = "confirmed",
                    confirmedAtMillis = now,
                    updatedAtMillis = now,
                    trail = emptyList()
                )
            )
        }
        _threats.update { it + list }
    }

    private fun focusOrNull(): LatLng? = LocationTracker.location.value

    /** Random point at a distance in [minKm, maxKm] from [focus]; a Ukraine-bbox point when null. */
    private fun pointAtDistance(focus: LatLng?, minKm: Double, maxKm: Double): Pair<Double, Double> {
        val rnd = ThreadLocalRandom.current()
        if (focus == null) {
            val lat = rnd.nextDouble(UA_TIGHT_MIN_LAT, UA_TIGHT_MAX_LAT)
            val lon = rnd.nextDouble(UA_TIGHT_MIN_LON, UA_TIGHT_MAX_LON)
            return lat to lon
        }
        val bearingRad = Math.toRadians(rnd.nextDouble(0.0, 360.0))
        val distKm = rnd.nextDouble(minKm.coerceAtLeast(1.0), maxKm.coerceAtLeast(minKm))
        val dLat = distKm / 111.0
        val dLon = distKm / (111.0 * kotlin.math.cos(Math.toRadians(focus.lat)).coerceAtLeast(0.01))
        return (focus.lat + dLat * kotlin.math.sin(bearingRad)) to
            (focus.lon + dLon * kotlin.math.cos(bearingRad))
    }

    private var idCounter = 0L

    private fun nextId(prefix: String): String = "test-$prefix-${idCounter++}"

    private fun speedFor(type: String): Double = when (type) {
        "shahed" -> 180.0
        "fpv" -> 120.0
        "cruise" -> 850.0
        "ballistic" -> 3300.0
        "kab" -> 900.0
        "recon" -> 80.0
        "aviation" -> 900.0
        else -> 120.0
    }
}