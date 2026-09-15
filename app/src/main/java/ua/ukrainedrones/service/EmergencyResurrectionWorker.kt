package ua.ukrainedrones.service

import android.app.ActivityManager
import android.content.Context
import androidx.work.*
import ua.ukrainedrones.AlertService
import ua.ukrainedrones.engine.MonitorCoreImpl
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
            try {
                AlertService.start(applicationContext)
            } catch (_: Exception) {}
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
