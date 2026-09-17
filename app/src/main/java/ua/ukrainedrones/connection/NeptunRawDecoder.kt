package ua.ukrainedrones.connection

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.engine.MonitorCore
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.fallbackCourse
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.normalizedThreatFromJson
import ua.ukrainedrones.source.ThreatRemoved
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast, robust JSON frame decoder for the NEPTUN stream.
 * Converts raw JSON text payloads into normalized threat/alert objects and invokes [MonitorCore].
 */
class NeptunRawDecoder(
    private val core: MonitorCore
) {
    companion object {
        const val ALERT_CLEAR_CONFIRM_MS = 30_000L
        private const val TAG = "NeptunRawDecoder"
    }

    private val _removedThreats = MutableSharedFlow<ThreatRemoved>(extraBufferCapacity = 16)
    val removedThreats: SharedFlow<ThreatRemoved> = _removedThreats.asSharedFlow()

    @Volatile private var alertsPendingClear: List<OblastAlert>? = null
    @Volatile private var alertsPendingClearSinceMono: Long? = null

    private val knownTypeKeys = ThreatType.entries.map { it.apiKey }.toSet() +
            setOf("uav", "drone", "lancet", "molniya", "loitering", "missile", "cruise_missile", "mig31", "mig31k", "kinzhal")

    /**
     * Handles an incoming text frame from WebSocket.
     */
    fun handleFrame(text: String, nowWall: Long = System.currentTimeMillis(), nowMono: Long = Monotonic.now()) {
        try {
            val env = JSONObject(text)
            core.onStreamFrameReceived()

            val frameType = env.optString("type")
            when (frameType) {
                "snapshot" -> {
                    val data = env.optJSONObject("data") ?: return
                    val arr = data.optJSONArray("threats") ?: return
                    val list = mutableListOf<NormalizedThreat>()
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val t = normalizedThreatFromJson(obj) ?: continue
                            list.add(t)
                        } catch (e: Exception) {
                            Log.w(TAG, "Malformed threat in snapshot at index $i", e)
                        }
                    }
                    core.updateThreats(list)
                }
                "upsert" -> {
                    val data = env.optJSONObject("data") ?: return
                    val t = normalizedThreatFromJson(data) ?: return
                    if (t.status == "resolved") {
                        core.removeThreat(t.id)
                        _removedThreats.tryEmit(
                            ThreatRemoved(
                                t.id, t.lat, t.lon, t.type.toThreatType(),
                                t.bearingDeg ?: t.heading ?: fallbackCourse(t.id),
                                t.region, t.district, t.locality
                            )
                        )
                    } else {
                        core.upsertThreat(t)
                    }
                }
                "remove" -> {
                    val data = env.optJSONObject("data") ?: return
                    val id = data.optString("id")
                    if (id.isNotBlank()) {
                        val existing = core.threats.value.firstOrNull { it.id == id }
                        core.removeThreat(id)
                        if (existing != null) {
                            _removedThreats.tryEmit(
                                ThreatRemoved(
                                    existing.id, existing.lat, existing.lon, existing.type.toThreatType(),
                                    existing.bearingDeg ?: existing.heading ?: fallbackCourse(existing.id),
                                    existing.region, existing.district, existing.locality
                                )
                            )
                        }
                    }
                }
                "alerts" -> {
                    val dataObj = env.optJSONObject("data")
                    val dataArr = env.optJSONArray("data")
                        ?: env.optJSONArray("alerts")
                        ?: dataObj?.optJSONArray("alerts")
                    val hasAlertPayload = dataArr != null ||
                        dataObj?.let {
                            it.optJSONArray("raions") != null ||
                                it.optJSONArray("oblasts") != null ||
                                it.optJSONArray("alerts") != null
                        } == true
                    if (!hasAlertPayload) return

                    val list = mutableListOf<OblastAlert>()

                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            try {
                                val o = dataArr.getJSONObject(i)
                                val key = o.optString("key", o.optString("name", "")).trim()
                                if (key.isEmpty()) continue
                                val name = o.optString("name", key).trim()
                                val oblast = o.optString("oblast", name).trim()
                                val since = if (o.has("since") && !o.isNull("since")) o.optString("since") else null
                                val level = o.optString("level", "red").trim().lowercase().ifEmpty { "red" }
                                val wide = when {
                                    o.has("wide") -> o.optBoolean("wide", false)
                                    o.has("isOblastWide") -> o.optBoolean("isOblastWide", false)
                                    else -> null
                                }
                                list.add(OblastAlert(key = key, name = name, oblast = oblast, since = since, wide = wide, level = level))
                            } catch (e: Exception) {
                                Log.w(TAG, "Malformed alert at index $i", e)
                            }
                        }
                    } else if (dataObj != null) {
                        val wideByArray = mapOf("raions" to false, "oblasts" to true)
                        for ((arrName, wide) in wideByArray) {
                            val arr = dataObj.optJSONArray(arrName) ?: continue
                            for (i in 0 until arr.length()) {
                                try {
                                    val o = arr.getJSONObject(i)
                                    val key = o.optString("key", o.optString("name", "")).trim()
                                    if (key.isEmpty()) continue
                                    val name = o.optString("name", key).trim()
                                    val oblast = o.optString("oblast", name).trim()
                                    val since = if (o.has("since") && !o.isNull("since")) o.optString("since") else null
                                    val level = o.optString("level", "red").trim().lowercase().ifEmpty { "red" }
                                    list.add(OblastAlert(key = key, name = name, oblast = oblast, since = since, wide = wide, level = level))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Malformed alert at index $i", e)
                                }
                            }
                        }
                    } else {
                        return
                    }

                    if (list.isEmpty()) {
                        if (alertsPendingClear == null && core.alerts.value.isNotEmpty()) {
                            alertsPendingClear = core.alerts.value
                            alertsPendingClearSinceMono = nowMono
                        }
                        flushPendingAlertClear(nowMono)
                    } else {
                        alertsPendingClear = null
                        alertsPendingClearSinceMono = null
                        core.updateAlerts(list)
                    }
                }
                "heartbeat" -> {
                    // Handled implicitly by onStreamFrameReceived
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stream frame", e)
        }
    }

    fun flushPendingAlertClear(nowMono: Long = Monotonic.now()) {
        val pending = alertsPendingClear ?: return
        val since = alertsPendingClearSinceMono ?: return
        if (nowMono - since >= ALERT_CLEAR_CONFIRM_MS) {
            alertsPendingClear = null
            alertsPendingClearSinceMono = null
            core.updateAlerts(emptyList())
        }
    }

    fun handleTransportDrop() {
        // Drop pending clear on transport disconnection to avoid stale clearing
        alertsPendingClear = null
        alertsPendingClearSinceMono = null
    }
}
