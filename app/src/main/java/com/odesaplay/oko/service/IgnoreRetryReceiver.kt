package com.odesaplay.oko.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.odesaplay.oko.AlertNotificationManager
import com.odesaplay.oko.AppLanguage
import com.odesaplay.oko.AppSources
import com.odesaplay.oko.DebugLog
import com.odesaplay.oko.DebugLogReason
import com.odesaplay.oko.Strings
import com.odesaplay.oko.connection.ConnEventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The shade's "Ignore 30 min" tap, served without starting the service. A tap that has to
 * cold-start the foreground service first reads as a ~2s dead button; this receiver dismisses
 * the rows and records the mute in milliseconds, and the audit bookkeeping follows off the
 * tap path. The service still honours [OfflineIgnore] on its ticks and still handles the
 * action for taps already posted before this receiver existed.
 */
class IgnoreRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        OfflineIgnore.ignoreFor(IGNORE_MUTE_MS)
        val appContext = context.applicationContext
        NotificationManagerCompat.from(appContext).cancel(AlertNotificationManager.NOTIF_MILESTONE)
        NotificationManagerCompat.from(appContext).cancel(AlertNotificationManager.NOTIF_OFFLINE_CRITICAL)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppSources.registry.annotateConnectionLog(
                    ConnEventKind.IGNORE_MUTED,
                    detail = "${IGNORE_MUTE_MS / 60_000} min"
                )
                DebugLog.recordAction(
                    DebugLogReason.MUTED,
                    Strings.get(AppLanguage.EN).actionLog.ignoreOffline,
                    "${IGNORE_MUTE_MS / 60_000} min",
                    System.currentTimeMillis()
                )
            } catch (_: Exception) {
                // Best-effort audit: the user's contract (dismiss + mute) already holds above.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val IGNORE_MUTE_MS = 30 * 60_000L
    }
}
