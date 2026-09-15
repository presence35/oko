package ua.ukrainedrones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ua.ukrainedrones.engine.MonitorCoreImpl
import ua.ukrainedrones.service.AlertWatchdog
import ua.ukrainedrones.service.EmergencyResurrectionWorker

/**
 * Boot and Package-Replacement Receiver.
 * Enforces safety-asymmetric alerting:
 * If the device rebooted or crashed while an active threat or siren was live,
 * resurrect AlertService immediately to sound the alert.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val appContext = context.applicationContext
                AlertWatchdog.schedule(appContext)
                EmergencyResurrectionWorker.schedule(appContext)

                // Check safety-asymmetric resurrection invariant
                val prefs = appContext.getSharedPreferences(MonitorCoreImpl.PREFS_NAME, Context.MODE_PRIVATE)
                val hadActiveAlert = prefs.getBoolean(MonitorCoreImpl.KEY_HAD_ACTIVE_ALERT, false)

                val bootRestart = runBlocking {
                    UserPrefs(appContext).preferences.first().bootRestartEnabled
                }

                if (hadActiveAlert || bootRestart) {
                    AlertService.start(context)
                } else {
                    postBootPausedNotification(context)
                }
            }
        }
    }

    private fun postBootPausedNotification(context: Context) {
        val s = Strings.get(AppLanguage.EN)
        val openApp = android.app.PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, AlertNotificationManager.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(s.bootRestartPaused)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_BOOT_PAUSED, notif)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val NOTIF_BOOT_PAUSED = 8
    }
}
