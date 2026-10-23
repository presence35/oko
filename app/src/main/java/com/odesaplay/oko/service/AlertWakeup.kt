package com.odesaplay.oko.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.odesaplay.oko.AlertService
import com.odesaplay.oko.UserPrefs
import com.odesaplay.oko.WakeEventLog
import com.odesaplay.oko.WakeKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The Doze-honoured wakeup, which the WorkManager watchdog cannot be.
 *
 * A periodic job is deferred to the next maintenance window — measured on an SM-A505FN as a
 * 45-minute hole with the process still alive, which is blindness, not death. `WorkManager`'s
 * deferral is the same deferral the tick loop suffers, so the watchdog could never have closed
 * the gap it was scheduled for. `setExactAndAllowWhileIdle` is the documented way to fire anyway,
 * and it also drops the app onto the system's **temporary power exemption list for ~10 seconds** —
 * the very list that permits network — which is why the wakeup is worth having at all: Doze
 * suspends network access, so an alarm that fired without it would only prove the socket is still
 * there.
 *
 * Armed at 15 minutes, the cadence the watchdog already claims; the platform will not dispatch two
 * allow-while-idle alarms closer than ~9 minutes apart, so this is the honest floor. Self-rearming:
 * one missed alarm must never end the chain, and every service start re-arms it too, so no path
 * (boot, package replace, cold launch, resurrection) can leave a device with no wakeup.
 *
 * Where the permission is refused (Android 12+ without `SCHEDULE_EXACT_ALARM` and off the
 * battery-optimization list), it degrades to the inexact `setAndAllowWhileIdle` — still Doze-fired,
 * just later — rather than leaving the device with nothing. Both test devices take the exact path
 * for free: the SM-A505FN is API 30, where the S+ gate does not exist, and the Pixel is on the
 * device's power-save exemption list, which always permits exact alarms.
 */
object AlertWakeup {

    /** Stable id so every arm replaces the one outstanding alarm instead of stacking them. */
    private const val REQUEST_CODE = 4711

    internal const val INTERVAL_MS = 15 * 60_000L

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        val pendingIntent = pendingIntent(context)
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            // The grant can be revoked between the check and the call. Degrade instead of ending
            // the chain: an inexact allow-while-idle alarm still fires in Doze, just later.
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AlertWakeupReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Ends the chain. Called when the package is paused: a paused app has no monitoring left for a
     * wakeup to thaw, and an alarm left armed would only serve to start a process that must not
     * run. Un-pausing re-arms it.
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
    }
}

/**
 * Delivering this broadcast is already half the fix: an exact alarm wakes the process, so a
 * service that Doze suspended without killing it resumes its tick loop — feed refresh included —
 * inside the alarm's temporary exemption window, with no code having to detect the freeze. The
 * other half is the killed case, handled under the same policy as `AlertWatchdog` and
 * `EmergencyResurrectionWorker` so every resurrection path answers to one setting.
 */
class AlertWakeupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WakeEventLog.record(WakeKind.ALARM)
        val appContext = context.applicationContext
        if (MonitoringStatus.isServiceUp()) {
            // Alive: the thaw is the whole point, so only the chain needs attention.
            AlertWakeup.schedule(appContext)
            return
        }
        if (!runBlocking { UserPrefs(appContext).preferences.first().bootRestartEnabled }) {
            // Auto-restart switched off: stay stopped and let the chain end with the intent.
            return
        }
        AlertWakeup.schedule(appContext)
        AlertService.startResilient(appContext)
    }
}
