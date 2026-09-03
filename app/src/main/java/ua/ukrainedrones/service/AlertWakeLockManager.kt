package ua.ukrainedrones

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Manages CPU wake locks with strict timeouts to prevent battery drain while
 * guaranteeing CPU execution during active alerts and critical milestones.
 */
class AlertWakeLockManager(private val context: Context) {

    companion object {
        /**
         * Safety timeout to keep the CPU awake during alert dispatch and audio/vibration initiation.
         * 10 seconds provides ample headroom for notification/intent delivery while letting the device
         * return to Doze mode quickly.
         */
        const val ALERT_WAKELOCK_TIMEOUT_MS = 10_000L
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var alertWakeLock: PowerManager.WakeLock? = null

    init {
        alertWakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UkraineDrones:AlertWakeLock"
        )?.apply {
            setReferenceCounted(false)
        }
    }

    /**
     * Acquires a temporary wake lock for the duration of alert sounding / processing.
     * Uses [ALERT_WAKELOCK_TIMEOUT_MS] to ensure completion without holding the lock permanently.
     */
    fun acquireForAlert() {
        try {
            alertWakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire(ALERT_WAKELOCK_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
            Log.w("AlertWakeLock", "Failed to acquire alert wakelock", e)
        }
    }

    /**
     * Releases the wake lock if currently held.
     */
    fun release() {
        try {
            alertWakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                }
            }
        } catch (e: Exception) {
            Log.w("AlertWakeLock", "Failed to release alert wakelock", e)
        }
    }
}
