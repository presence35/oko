package com.presaince.oko.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import com.presaince.oko.AlertService
import com.presaince.oko.UserPrefs

class AlertWatchdog(ctx: Context, params: androidx.work.WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (MonitoringStatus.running.value) return Result.success()
        val bootRestart = UserPrefs(applicationContext).preferences.first().bootRestartEnabled
        if (!bootRestart) return Result.success()
        // Background-safe: a blocked FGS start (Android 12+) posts a resume prompt instead.
        AlertService.startResilient(applicationContext)
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
