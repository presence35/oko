package com.odesaplay.oko.service

import android.app.ActivityManager
import android.content.Context
import androidx.work.*
import com.odesaplay.oko.AlertService
import com.odesaplay.oko.UserPrefs
import com.odesaplay.oko.engine.MonitorCoreImpl
import java.util.concurrent.TimeUnit

/**
 * 15-Minute WorkManager Safety Net & Reboot Resurrection Worker.
 *
 * Checks if the persistent AlertService was killed by the Android OS (or aggressive OEM battery killer).
 * If dead, or if phone recently rebooted while an alert was active, resurrects AlertService immediately.
 */
class EmergencyResurrectionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "oko_emergency_resurrection"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .build()

            val workRequest = PeriodicWorkRequestBuilder<EmergencyResurrectionWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val isServiceRunning = isAlertServiceRunning(applicationContext)
        if (!isServiceRunning) {
            // Kill evidence: the OS stopped background monitoring, so arm the one-shot
            // battery-exemption prompt for the next foreground session.
            try {
                UserPrefs(applicationContext).setServiceResurrected(true)
            } catch (_: Exception) {}
            // Background-safe: Android 12+ blocks the FGS start here, in which case a
            // tap-to-resume prompt is posted instead (never crashes the worker).
            AlertService.startResilient(applicationContext)
        }
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isAlertServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (AlertService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
