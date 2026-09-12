package ua.ukrainedrones.connection

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import ua.ukrainedrones.ThreatType
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.showToast
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.fallbackCourse
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.normalizedThreatFromJson
import ua.ukrainedrones.source.ThreatRemoved
import java.util.concurrent.ConcurrentHashMap

/**
 * Decodes NEPTUN stream frames into source-agnostic feed currency ([NormalizedThreat] /
 * [OblastAlert]) and owns the feed bookkeeping that the transport must not touch:
 *
 *  - snapshot / upsert / remove / alerts / heart beat parsing (via `normalizedThreatFromJson`);
 *  - the user-shot grace (a shot track is preserved across snapshots so the map doesn't flicker);
 *  - recently-removed tombstones and the [removedThreats] resolution feed;
 *  - the 30s **alert-clear debounce** ([ALERT_CLEAR_CONFIRM_MS]): an empty alerts frame holds the
 *    last-known list until the clear persists, so a momentary feed gap can't flip an alert OFF
 *    and back on ([flushPendingAlertClear] — driven by the owner's watchdog tick);
 *  - rate-limited "new threat type" toasts + ApiMonitor records.
 *
 * Pure data: it has no socket knowledge and takes explicit wall/monotonic `now` stamps, so its
 * behavior is deterministic and testable. No coroutines — the owner serializes frame delivery.
 */
class NeptunDecoder {

    companion object {
        const val USER_SHOT_GRACE_MS = 3_000L
        const val RECENT_REMOVED_GRACE_MS = 60_000L
        const val ALERT_CLEAR_CONFIRM_MS = 30_000L
        private const val UNKNOWN_TYPE_TOAST_COOLDOWN_MS = 60_000L
    }

    private val _threats = MutableStateFlow<Map<String, NormalizedThreat>>(emptyMap())
    val threats: StateFlow<Map<String, NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _removedThreats = MutableSharedFlow<ThreatRemoved>(extraBufferCapacity = 16)
    val removedThreats: SharedFlow<ThreatRemoved> = _removedThreats.asSharedFlow()

    private val _lastValidThreatUpdate = MutableStateFlow(0L)
    val lastValidThreatUpdate: StateFlow<Long> = _lastValidThreatUpdate.asStateFlow()

    @Volatile private var lastValidThreatUpdateMono = 0L

    /** Pending (debounced) official-alert clear: the alert list is held while a clear is being
     *  confirmed, so an active alert never flips OFF for a single empty frame. */
    @Volatile private var alertsPendingClear: List<OblastAlert>? = null
    @Volatile private var alertsPendingClearSince = 0L

    // User-shot grace tracking and recently removed tombstone tracking
    private val userShotAt = ConcurrentHashMap<String, Long>()
    private val recentlyRemovedThreats = ConcurrentHashMap<String, Long>()

    // Mutex to serialize _threats.value mutations across WS + UI paths
    private val threatsMutex = Mutex()

    private val knownTypeKeys = ThreatType.entries.map { it.apiKey }.toSet() +
        setOf("uav", "drone", "lancet", "molniya", "loitering", "missile", "cruise_missile", "mig31", "mig31k", "kinzhal")
    private val unknownTypeLastSeen = ConcurrentHashMap<String, Long>()

    fun wasUserShotRecently(threatId: String): Boolean {
        val shotAt = userShotAt[threatId] ?: return false
        return Monotonic.now() - shotAt <= USER_SHOT_GRACE_MS
    }

    fun markUserShot(id: String) {
        if (id.isNotBlank()) {
            userShotAt[id] = Monotonic.now()
        }
    }

    /** A transport drop voids an unconfirmed pending clear — a stale observation must never
     *  flush later without fresh data (the held list stays until re-sent). */
    fun handleTransportDrop() {
        alertsPendingClear = null
    }

    /** Publish the debounced official-alert clear once it has persisted ALERT_CLEAR_CONFIRM_MS.
     *  No-op when nothing is pending or the window hasn't elapsed. [now] is a monotonic stamp. */
    fun flushPendingAlertClear(now: Long) {
        val pending = alertsPendingClear ?: return
        if (now - alertsPendingClearSince < ALERT_CLEAR_CONFIRM_MS) return
        _alerts.value = pending
        alertsPendingClear = null
    }

    /** Apply one stream frame. [now] is wall-clock, [nowMono] monotonic (both taken at arrival). */
    suspend fun handleFrame(text: String, now: Long, nowMono: Long) {
        try {
            val env = JSONObject(text)
            val frameType = env.optString("type")

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
                                Log.w("NeptunDecoder", "Malformed WS threat at index $i", e)
                            }
                        }
                        val prev = _threats.value
                        // Preserve user-shot drones within grace window
                        for (id in prev.keys) {
                            if (id in map) continue
                            val shotAt = userShotAt[id] ?: continue
                            if (nowMono - shotAt <= USER_SHOT_GRACE_MS) {
                                map[id] = prev.getValue(id)
                            }
                        }
                        userShotAt.entries.removeIf { nowMono - it.value > USER_SHOT_GRACE_MS }
                        recentlyRemovedThreats.entries.removeIf { nowMono - it.value > RECENT_REMOVED_GRACE_MS }

                        _threats.value = map
                        _lastValidThreatUpdate.value = now
                        lastValidThreatUpdateMono = nowMono
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
                            recentlyRemovedThreats[t.id] = nowMono
                            _removedThreats.tryEmit(
                                ThreatRemoved(t.id, t.lat, t.lon, t.type.toThreatType(), t.bearingDeg ?: t.heading ?: fallbackCourse(t.id), t.region, t.district, t.locality)
                            )
                            updated.remove(t.id)
                        } else {
                            val existing = updated[t.id]
                            val existingTime = existing?.updatedAtMillis ?: existing?.confirmedAtMillis ?: 0L
                            val newTime = t.updatedAtMillis ?: t.confirmedAtMillis ?: 0L
                            if (existing == null || newTime >= existingTime) {
                                updated[t.id] = t
                                recentlyRemovedThreats.remove(t.id)
                            }
                        }
                        _threats.value = updated
                        _lastValidThreatUpdate.value = now
                        lastValidThreatUpdateMono = nowMono
                    }
                }
                "remove" -> {
                    val data = env.optJSONObject("data") ?: return
                    val id = data.optString("id")
                    threatsMutex.withLock {
                        val updated = _threats.value.toMutableMap()
                        recentlyRemovedThreats[id] = nowMono
                        updated.remove(id)?.let { gone ->
                            _removedThreats.tryEmit(
                                ThreatRemoved(gone.id, gone.lat, gone.lon, gone.type.toThreatType(), gone.bearingDeg ?: gone.heading ?: fallbackCourse(gone.id), gone.region, gone.district, gone.locality)
                            )
                        }
                        _threats.value = updated
                        _lastValidThreatUpdate.value = now
                        lastValidThreatUpdateMono = nowMono
                    }
                }
                "alerts" -> {
                    val data = env.optJSONObject("data") ?: return
                    val list = mutableListOf<OblastAlert>()
                    // NEPTUN splits whole-oblast (`oblasts`) from region/city (`raions`) entries —
                    // tag each so map coloring is region-precise instead of guessing.
                    val wideByArray = mapOf("raions" to false, "oblasts" to true)
                    for ((arrName, wide) in wideByArray) {
                        val arr = data.optJSONArray(arrName) ?: continue
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(
                                OblastAlert(
                                    key = o.optString("key"),
                                    name = o.optString("name"),
                                    oblast = o.optString("oblast"),
                                    since = o.optString("since", null),
                                    wide = wide,
                                    level = o.optString("level", "red")
                                )
                            )
                        }
                    }
                    // A clear (empty list) must be confirmed before it goes live: NEPTUN re-sends
                    // the alert list regularly, so a momentary empty frame would otherwise flip
                    // the official alert OFF then back ON. Hold the last-known list until the
                    // clear persists ALERT_CLEAR_CONFIRM_MS (flushPendingAlertClear).
                    if (list.isEmpty() && _alerts.value.isNotEmpty()) {
                        if (alertsPendingClear == null) {
                            alertsPendingClear = list
                            alertsPendingClearSince = nowMono
                        }
                    } else {
                        _alerts.value = list
                        alertsPendingClear = null
                    }
                }
                "heartbeat" -> {
                    // Socket frame keep-alive; does not update threat freshness
                }
            }
        } catch (e: Exception) {
            Log.w("NeptunDecoder", "Malformed frame", e)
            ApiMonitor.record(SystemEntry(
                atMillis = System.currentTimeMillis(),
                kind = SystemEntryKind.MALFORMED_FRAME,
                detail = text.take(200)
            ))
        }
    }

    private fun recordUnknownType(rawType: String) {
        val nowMono = Monotonic.now()
        val lastSeen = unknownTypeLastSeen[rawType] ?: 0L
        if (nowMono - lastSeen > UNKNOWN_TYPE_TOAST_COOLDOWN_MS) {
            unknownTypeLastSeen[rawType] = nowMono
            showToast("New threat type reported: $rawType")
            ApiMonitor.record(SystemEntry(
                atMillis = System.currentTimeMillis(),
                kind = SystemEntryKind.UNKNOWN_TYPE_DETECTED,
                detail = rawType
            ))
        }
    }
}