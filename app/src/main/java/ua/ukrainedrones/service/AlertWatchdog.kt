package ua.ukrainedrones.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import ua.ukrainedrones.AlertService
import ua.ukrainedrones.UserPrefs

class AlertWatchdog(ctx: Context, params: androidx.work.WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (MonitoringStatus.running.value) return Result.success()
        val bootRestart = UserPrefs(applicationContext).bootRestartEnabled().first()
        if (!bootRestart) return Result.success()
        try {
            AlertService.start(applicationContext)
        } catch (_: Exception) {
            // best-effort; will retry next cycle
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "oko_alert_watchdog"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AlertWatchdog>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}
