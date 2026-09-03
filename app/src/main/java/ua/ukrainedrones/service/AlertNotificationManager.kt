package ua.ukrainedrones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import ua.ukrainedrones.AppLanguage
import ua.ukrainedrones.MainActivity
import ua.ukrainedrones.R
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.Strings
import ua.ukrainedrones.UserPrefs
import ua.ukrainedrones.NeutralizedTally

/**
 * Handles notification channels, notification building, and dispatching for [AlertService].
 */
class AlertNotificationManager(private val context: Context) {

    companion object {
        const val ACTION_RETRY = "ua.ukrainedrones.RETRY"
        const val ACTION_IGNORE_RETRY = "ua.ukrainedrones.IGNORE_RETRY"
        const val EXTRA_REVEAL_ID = "reveal_threat_id"
        const val EXTRA_REVEAL_LAT = "reveal_threat_lat"
        const val EXTRA_REVEAL_LON = "reveal_threat_lon"
        const val EXTRA_SHOW_UPDATE = "show_update"
        const val EXTRA_SHOW_MAP = "show_map"

        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERTS = "alerts_siren2"
        const val CHANNEL_ALERTS_OUTER = "alerts_siren_outer2"
        const val CHANNEL_ALLCLEAR = "alerts_all_clear2"
        const val CHANNEL_ALERTS_ALARM = "alerts_siren_alarm"
        const val CHANNEL_ALERTS_OUTER_ALARM = "alerts_siren_outer_alarm"
        const val CHANNEL_OFFLINE = "offline"
        const val CHANNEL_OFFLINE_CRITICAL = "offline_critical"
        const val CHANNEL_UPDATE = "updates"

        const val NOTIF_MONITOR = 1
        const val NOTIF_ALERT = 2
        const val NOTIF_ALLCLEAR = 3
        const val NOTIF_MILESTONE = 5
        const val NOTIF_OFFLINE_CRITICAL = 6
        const val NOTIF_UPDATE = 7

        fun areNotificationsEnabled(context: Context): Boolean {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val en = Strings.get(AppLanguage.EN)
        defineChannels(nm, en)

        val keep = setOf(
            CHANNEL_MONITOR,
            CHANNEL_ALERTS,
            CHANNEL_ALERTS_OUTER,
            CHANNEL_ALLCLEAR,
            CHANNEL_ALERTS_ALARM,
            CHANNEL_ALERTS_OUTER_ALARM,
            CHANNEL_OFFLINE,
            CHANNEL_OFFLINE_CRITICAL,
            NeutralizedTally.CHANNEL_NEUTRALIZED,
            CHANNEL_UPDATE
        )
        nm.notificationChannels
            .filter { it.id !in keep }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun updateChannels(s: Strings.StringSet) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        defineChannels(nm, s)
    }

    private fun defineChannels(nm: NotificationManager, s: Strings.StringSet) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, s.notifChannelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = s.notifChannelDesc
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, s.alertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.alertChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.air_raid_siren), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_OUTER, s.outerAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.outerAlertChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.zone_outer), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALLCLEAR, s.allClearChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.allClearChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.all_clear), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_ALARM, s.alarmAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.alarmAlertChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.air_raid_siren), alarmAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_OUTER_ALARM, s.outerAlarmAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.outerAlarmAlertChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.zone_outer), alarmAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFLINE, s.offlineChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.offlineChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.critical_offline), notificationAttributes())
            }
        )

        val bypassSilent = runBlocking(Dispatchers.IO) {
            UserPrefs(context).criticalOfflineBypassSilent().first()
        }
        val criticalAttrs = if (bypassSilent) alarmAttributes() else notificationAttributes()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFLINE_CRITICAL, s.offlineCriticalChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.offlineCriticalChannelDesc
                enableVibration(true)
                setSound(sirenUri(R.raw.critical_offline), criticalAttrs)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(NeutralizedTally.CHANNEL_NEUTRALIZED, s.neutralizedNotifChannelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = s.neutralizedChannelDesc
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, s.notifUpdateChannelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = s.notifUpdateChannelDesc
                setSound(null, null)
            }
        )
    }

    fun buildMonitorNotification(
        title: String,
        text: String,
        retryLabel: String? = null,
        progressMax: Int? = null,
        progressNow: Int? = null,
        ignoreLabel: String? = null
    ): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (retryLabel != null) {
            b.addAction(R.drawable.ic_trident, retryLabel, retryPendingIntent())
        }
        if (progressMax != null && progressNow != null) {
            b.setProgress(progressMax, progressNow.coerceIn(0, progressMax), false)
        }
        if (ignoreLabel != null) {
            b.addAction(R.drawable.ic_trident, ignoreLabel, ignoreRetryPendingIntent())
        }
        return b.build()
    }

    fun postAlertNotification(
        zone: ThreatZone,
        title: String,
        body: String,
        sirenOverride: Boolean,
        revealThreat: NormalizedThreat? = null,
        vibrationLevel: Int = 3
    ) {
        val channel = when {
            zone == ThreatZone.INNER && sirenOverride -> CHANNEL_ALERTS_ALARM
            zone == ThreatZone.INNER -> CHANNEL_ALERTS
            sirenOverride -> CHANNEL_ALERTS_OUTER_ALARM
            else -> CHANNEL_ALERTS_OUTER
        }
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(vibrationPattern(vibrationLevel))
            .setContentIntent(openAppIntent(revealThreat))
            .build()
        safeNotify(NOTIF_ALERT, notif)
    }

    fun postAllClearNotification(title: String, body: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ALLCLEAR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        safeNotify(NOTIF_ALLCLEAR, notif)
    }

    fun postOfflineNotification(title: String, text: String, retryLabel: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_OFFLINE)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_trident, retryLabel, retryPendingIntent())
            .setContentIntent(openAppIntent())
            .build()
        safeNotify(NOTIF_MILESTONE, notif)
    }

    fun postCriticalOfflineNotification(title: String, text: String, retryLabel: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_OFFLINE_CRITICAL)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_trident, retryLabel, retryPendingIntent())
            .setContentIntent(openAppIntent())
            .build()
        safeNotify(NOTIF_OFFLINE_CRITICAL, notif)
    }

    fun postUpdateNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(updatePendingIntent())
            .build()
        safeNotify(NOTIF_UPDATE, notif)
    }

    fun cancelNotification(id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: SecurityException) {
        }
    }

    fun safeNotify(id: Int, notif: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — silently skip
        }
    }

    private fun sirenUri(resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    private fun notificationAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun openAppIntent(revealThreat: NormalizedThreat? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_MAP, true)
            if (revealThreat != null) {
                putExtra(EXTRA_REVEAL_ID, revealThreat.id)
                putExtra(EXTRA_REVEAL_LAT, revealThreat.lat)
                putExtra(EXTRA_REVEAL_LON, revealThreat.lon)
            }
        }
        return PendingIntent.getActivity(
            context, if (revealThreat != null) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun retryPendingIntent(): PendingIntent {
        val intent = Intent(context, AlertService::class.java).setAction(ACTION_RETRY)
        return PendingIntent.getForegroundService(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun ignoreRetryPendingIntent(): PendingIntent {
        val intent = Intent(context, AlertService::class.java).setAction(ACTION_IGNORE_RETRY)
        return PendingIntent.getForegroundService(
            context, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updatePendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_UPDATE, true)
        }
        return PendingIntent.getActivity(
            context, 4, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
