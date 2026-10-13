package com.odesaplay.oko

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.odesaplay.oko.engine.MonitorCoreImpl
import com.odesaplay.oko.service.AlertWatchdog
import com.odesaplay.oko.service.EmergencyResurrectionWorker
import com.odesaplay.oko.service.SuspendState
import com.odesaplay.oko.WakeEventLog
import com.odesaplay.oko.WakeKind

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
                WakeEventLog.record(WakeKind.BOOT)
                val appContext = context.applicationContext
                // A paused app has nothing to resume and no visible place to say so — the OS hides
                // its notifications — so a "paused after reboot" row queued here would only surface
                // later, as a stale claim, when the user un-pauses. Un-pausing re-schedules what
                // this skips.
                if (SuspendState.isSuspended(appContext)) return
                AlertWatchdog.schedule(appContext)
                EmergencyResurrectionWorker.schedule(appContext)

                // Check safety-asymmetric resurrection invariant
                val prefs = appContext.getSharedPreferences(MonitorCoreImpl.PREFS_NAME, Context.MODE_PRIVATE)
                val hadActiveAlert = prefs.getBoolean(MonitorCoreImpl.KEY_HAD_ACTIVE_ALERT, false)

                val bootRestart = runBlocking {
                    UserPrefs(appContext).preferences.first().bootRestartEnabled
                }

                if (hadActiveAlert || bootRestart) {
                    AlertService.startResilient(context)
                } else {
                    AlertNotificationManager(appContext).postMonitoringPaused()
                }
            }
        }
    }
}
