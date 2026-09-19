package ua.ukrainedrones.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ua.ukrainedrones.connection.Monotonic
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe authoritative state machine implementing [MonitorCore].
 *
 * Enforces core safety invariants:
 * 1. "Disconnected != All Clear": Network loss flags isInformationStale without wiping active alarms.
 * 2. Reboot Resurrection Persistence: Writes active threat / alert states to disk asynchronously so
 *    EmergencyResurrectionWorker and BootReceiver can resurrect alarm state after phone reboot.
 */
class MonitorCoreImpl(
    private val context: Context,
    private val scope: CoroutineScope
) : MonitorCore {

    companion object {
        const val PREFS_NAME = "neptun_engine_state"
        const val KEY_HAD_ACTIVE_ALERT = "had_active_alert"
        const val KEY_LAST_ALERT_TIME = "last_alert_time"
        const val USER_SHOT_GRACE_MS = 6_500L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _threats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()

    private val _alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()

    private val _isInformationStale = MutableStateFlow(false)
    override val isInformationStale: StateFlow<Boolean> = _isInformationStale.asStateFlow()

    private val _lastUpdateEpochMs = MutableStateFlow(0L)
    override val lastUpdateEpochMs: StateFlow<Long> = _lastUpdateEpochMs.asStateFlow()

    private val threatLock = Any()
    private val rawThreatMap = ConcurrentHashMap<String, NormalizedThreat>()
    private val userShotAt = ConcurrentHashMap<String, Long>()

    override fun onBaselineSyncRequired() {
        // Authoritative resync requested
        _isInformationStale.value = true
    }

    override fun onStreamFrameReceived() {
        _isInformationStale.value = false
        _lastUpdateEpochMs.value = System.currentTimeMillis()
    }

    override fun onNetworkDisconnected(reason: String) {
        // Critical Invariant: Disconnected != All Clear!
        // We do NOT clear alerts or threats. We mark information as stale.
        _isInformationStale.value = true
    }

    override fun onNetworkReconnected() {
        // Remains stale until first authoritative frame arrives
    }

    override fun updateThreats(threats: List<NormalizedThreat>) {
        val now = System.currentTimeMillis()
        val nowMono = Monotonic.now()

        val nextMap = mutableMapOf<String, NormalizedThreat>()
        for (t in threats) {
            nextMap[t.id] = t
        }

        synchronized(threatLock) {
            rawThreatMap.clear()
            rawThreatMap.putAll(nextMap)
            userShotAt.entries.removeIf { nowMono - it.value > USER_SHOT_GRACE_MS }
            _threats.value = rawThreatMap.values.toList()
        }

        _lastUpdateEpochMs.value = now
        _isInformationStale.value = false

        persistActiveAlertState()
    }

    override fun upsertThreat(threat: NormalizedThreat) {
        val now = System.currentTimeMillis()

        synchronized(threatLock) {
            rawThreatMap[threat.id] = threat
            _threats.value = rawThreatMap.values.toList()
        }

        _lastUpdateEpochMs.value = now
        _isInformationStale.value = false

        persistActiveAlertState()
    }

    override fun removeThreat(threatId: String) {
        synchronized(threatLock) {
            rawThreatMap.remove(threatId)
            _threats.value = rawThreatMap.values.toList()
        }
        persistActiveAlertState()
    }

    override fun updateAlerts(alerts: List<OblastAlert>) {
        _alerts.value = alerts
        _lastUpdateEpochMs.value = System.currentTimeMillis()
        _isInformationStale.value = false
        persistActiveAlertState()
    }

    override fun markUserShot(id: String) {
        if (id.isNotBlank()) {
            userShotAt[id] = Monotonic.now()
        }
    }

    override fun wasUserShotRecently(id: String): Boolean {
        val shotAt = userShotAt[id] ?: return false
        return Monotonic.now() - shotAt <= USER_SHOT_GRACE_MS
    }

    private fun persistActiveAlertState() {
        val hasActiveThreats = _threats.value.isNotEmpty()
        val hasActiveAlerts = _alerts.value.any { it.level == "red" || it.isOblastWide() }
        val isActive = hasActiveThreats || hasActiveAlerts

        prefs.edit()
            .putBoolean(KEY_HAD_ACTIVE_ALERT, isActive)
            .putLong(KEY_LAST_ALERT_TIME, System.currentTimeMillis())
            .apply()
    }
}
