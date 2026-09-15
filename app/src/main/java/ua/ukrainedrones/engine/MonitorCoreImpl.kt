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
 * 2. Ingestion Dead Reckoning: Continuously computes velocity projections across active tracks.
 * 3. Reboot Resurrection Persistence: Writes active threat / alert states to disk asynchronously so
 *    EmergencyResurrectionWorker and BootReceiver can resurrect alarm state after phone reboot.
 */
class MonitorCoreImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val kinematicsEngine: DeadReckoningEngine = DeadReckoningEngine()
) : MonitorCore {

    companion object {
        const val PREFS_NAME = "neptun_engine_state"
        const val KEY_HAD_ACTIVE_ALERT = "had_active_alert"
        const val KEY_LAST_ALERT_TIME = "last_alert_time"
        const val USER_SHOT_GRACE_MS = 3_000L
        const val PROJECTION_TICK_MS = 1_000L
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

    private val rawThreatMap = ConcurrentHashMap<String, NormalizedThreat>()
    private val userShotAt = ConcurrentHashMap<String, Long>()

    private var projectionJob: Job? = null

    init {
        startProjectionLoop()
    }

    private fun startProjectionLoop() {
        projectionJob?.cancel()
        projectionJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(PROJECTION_TICK_MS)
                if (rawThreatMap.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val projected = kinematicsEngine.evaluateSnapshot(now)
                    _threats.value = projected
                    val activeIds = kinematicsEngine.getActiveTrackIds()
                    rawThreatMap.keys.retainAll(activeIds)
                    persistActiveAlertState()
                }
            }
        }
    }

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

        rawThreatMap.clear()
        kinematicsEngine.clear()

        for (t in threats) {
            val shotAt = userShotAt[t.id]
            if (shotAt != null && nowMono - shotAt <= USER_SHOT_GRACE_MS) {
                // Keep suppressed
                continue
            }
            rawThreatMap[t.id] = t
            kinematicsEngine.ingestThreat(t, now)
        }

        userShotAt.entries.removeIf { nowMono - it.value > USER_SHOT_GRACE_MS }

        _threats.value = kinematicsEngine.evaluateSnapshot(now)
        _lastUpdateEpochMs.value = now
        _isInformationStale.value = false

        persistActiveAlertState()
    }

    override fun upsertThreat(threat: NormalizedThreat) {
        val now = System.currentTimeMillis()
        val nowMono = Monotonic.now()

        val shotAt = userShotAt[threat.id]
        if (shotAt != null && nowMono - shotAt <= USER_SHOT_GRACE_MS) {
            return
        }

        rawThreatMap[threat.id] = threat
        kinematicsEngine.ingestThreat(threat, now)
        _threats.value = kinematicsEngine.evaluateSnapshot(now)
        _lastUpdateEpochMs.value = now
        _isInformationStale.value = false

        persistActiveAlertState()
    }

    override fun removeThreat(threatId: String) {
        rawThreatMap.remove(threatId)
        kinematicsEngine.removeThreat(threatId)
        _threats.value = kinematicsEngine.evaluateSnapshot(System.currentTimeMillis())
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
            removeThreat(id)
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
