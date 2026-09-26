package com.odesaplay.oko
import com.odesaplay.oko.theme.AppPalette

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.odesaplay.oko.AppLanguage
import com.odesaplay.oko.MainActivity
import com.odesaplay.oko.R
import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.Strings
import com.odesaplay.oko.UserPrefs
import com.odesaplay.oko.NeutralizedTally
import com.odesaplay.oko.service.ServiceState

/**
 * Handles notification channels, notification building, and dispatching for [AlertService].
 */
class AlertNotificationManager(private val context: Context) {

    companion object {
        private val NotifRed = AppPalette.AlertRed.toInt()
        private val NotifYellow = AppPalette.AlertYellow.toInt()
        const val ACTION_RETRY = "com.odesaplay.oko.RETRY"
        const val ACTION_IGNORE_RETRY = "com.odesaplay.oko.IGNORE_RETRY"
        const val ACTION_ALLCLEAR_DISMISSED = "com.odesaplay.oko.ALLCLEAR_DISMISSED"
        const val EXTRA_REVEAL_ID = "reveal_threat_id"
        const val EXTRA_REVEAL_LAT = "reveal_threat_lat"
        const val EXTRA_REVEAL_LON = "reveal_threat_lon"
        const val EXTRA_SHOW_UPDATE = "show_update"
        const val EXTRA_SHOW_MAP = "show_map"

        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERTS_INNER = "alerts_inner"
        const val CHANNEL_ALERTS_OUTER = "alerts_outer"
        const val CHANNEL_ALL_CLEAR = "all_clear"
        const val CHANNEL_ALERTS_INNER_ALARM = "alerts_inner_alarm"
        const val CHANNEL_ALERTS_OUTER_ALARM = "alerts_outer_alarm"
        const val CHANNEL_OFFLINE = "offline"
        const val CHANNEL_OFFLINE_CRITICAL = "offline_critical"
        const val CHANNEL_UPDATE = "updates"
        const val CHANNEL_ALARM_EPISODE = "alarm_episode"

        const val NOTIF_MONITOR = 1
        const val NOTIF_ALERT = 2
        const val NOTIF_ALLCLEAR = 3
        const val NOTIF_MILESTONE = 5
        const val NOTIF_OFFLINE_CRITICAL = 6
        const val NOTIF_UPDATE = 7
        const val NOTIF_ALARM_EPISODE = 8
const val NOTIF_MONITORING_PAUSED = 9

        /** Bump to delete + recreate all managed channels (sound/importance/attrs are
         *  frozen by Android at creation — this is the only way a change takes effect). */
        const val CHANNEL_SCHEMA_VERSION = 3

        fun areNotificationsEnabled(context: Context): Boolean {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        enforceChannelSchema(nm)
        val en = Strings.get(AppLanguage.EN)
        defineChannels(nm, en)

        nm.notificationChannels
            .filter { it.id !in managedChannels }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    private val managedChannels = setOf(
            CHANNEL_MONITOR,
            CHANNEL_ALERTS_INNER,
            CHANNEL_ALERTS_OUTER,
            CHANNEL_ALL_CLEAR,
            CHANNEL_ALERTS_INNER_ALARM,
            CHANNEL_ALERTS_OUTER_ALARM,
            CHANNEL_OFFLINE,
            CHANNEL_OFFLINE_CRITICAL,
            NeutralizedTally.CHANNEL_NEUTRALIZED,
            CHANNEL_ALARM_EPISODE,
            CHANNEL_UPDATE
        )

    /** Deletes + recreates managed channels when the schema (or the bypass-silent config
     *  baked into the critical channel's audio attrs) changed since last applied. */
    private fun enforceChannelSchema(nm: NotificationManager) {
        val svc = ServiceState(context.applicationContext)
        val appliedSchema = runBlocking(Dispatchers.IO) { svc.channelSchemaVersion().first() }
        val bypassSilent = runBlocking(Dispatchers.IO) {
            UserPrefs(context).preferences.first().criticalOfflineBypassSilent
        }
        val bypassApplied = runBlocking(Dispatchers.IO) { svc.criticalChannelBypassApplied().first() }
        if (appliedSchema != CHANNEL_SCHEMA_VERSION) {
            managedChannels.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        } else if (bypassApplied == null || bypassApplied != bypassSilent) {
            runCatching { nm.deleteNotificationChannel(CHANNEL_OFFLINE_CRITICAL) }
        } else {
            return
        }
        runBlocking(Dispatchers.IO) {
            svc.setChannelSchemaVersion(CHANNEL_SCHEMA_VERSION)
            svc.setCriticalChannelBypassApplied(bypassSilent)
        }
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
            NotificationChannel(CHANNEL_ALERTS_INNER, s.alertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.alertChannelDesc
                enableVibration(true)
                setSound(sirenUri("air_raid_siren"), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_OUTER, s.outerAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.outerAlertChannelDesc
                enableVibration(true)
                setSound(sirenUri("zone_outer"), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALL_CLEAR, s.allClearChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.allClearChannelDesc
                enableVibration(true)
                setSound(sirenUri("all_clear"), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_INNER_ALARM, s.alarmAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.alarmAlertChannelDesc
                enableVibration(true)
                setSound(sirenUri("air_raid_siren"), alarmAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_OUTER_ALARM, s.outerAlarmAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.outerAlarmAlertChannelDesc
                enableVibration(true)
                setSound(sirenUri("zone_outer"), alarmAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFLINE, s.offlineChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.offlineChannelDesc
                enableVibration(true)
                setSound(sirenUri("critical_offline"), notificationAttributes())
            }
        )

        val bypassSilent = runBlocking(Dispatchers.IO) {
            UserPrefs(context).preferences.first().criticalOfflineBypassSilent
        }
        val criticalAttrs = if (bypassSilent) alarmAttributes() else notificationAttributes()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFLINE_CRITICAL, s.offlineCriticalChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.offlineCriticalChannelDesc
                enableVibration(true)
                setSound(sirenUri("critical_offline"), criticalAttrs)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(NeutralizedTally.CHANNEL_NEUTRALIZED, s.neutralizedNotifChannelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = s.neutralizedChannelDesc
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM_EPISODE, s.alarmEpisodeChannelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = s.alarmEpisodeChannelDesc
                enableVibration(true)
                setSound(sirenUri("all_clear"), notificationAttributes())
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
        ignoreLabel: String? = null,
        alertLevel: AlertLevel = AlertLevel.NONE
    ): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
        when (alertLevel) {
            AlertLevel.RED -> b.setLargeIcon(redIconBitmap())
            AlertLevel.YELLOW -> b.setLargeIcon(yellowIconBitmap())
            AlertLevel.NONE -> {}
        }

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

    /** Tinted trident as a large-icon bitmap (used to colorize the monitor notification). */
    private fun tintedIconBitmap(color: Int): Bitmap {
        val size = 96
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_trident)?.mutate()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (drawable != null) {
            drawable.setTint(color)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        return bmp
    }

    private fun redIconBitmap(): Bitmap = tintedIconBitmap(NotifRed)

    private fun yellowIconBitmap(): Bitmap = tintedIconBitmap(NotifYellow)

    fun postAlertNotification(
        zone: ThreatZone,
        title: String,
        body: String,
        sirenOverride: Boolean,
        revealThreat: NormalizedThreat? = null,
        vibrationLevel: Int = 3,
        silent: Boolean = false
    ) {
        val channel = when {
            zone == ThreatZone.INNER && sirenOverride -> CHANNEL_ALERTS_INNER_ALARM
            zone == ThreatZone.INNER -> CHANNEL_ALERTS_INNER
            sirenOverride -> CHANNEL_ALERTS_OUTER_ALARM
            else -> CHANNEL_ALERTS_OUTER
        }
        val idSuffix = revealThreat?.let { t ->
            val show = runBlocking(Dispatchers.IO) {
                UserPrefs(context).preferences.first().showThreatIdsOnMap
            }
            if (show) " · #${t.id.takeLast(4)}" else ""
        }.orEmpty()
        val notif = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(body + idSuffix)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(vibrationPattern(vibrationLevel))
            .setContentIntent(openAppIntent(revealThreat))
            .setOnlyAlertOnce(silent)
            .build()
        safeNotify(NOTIF_ALERT, notif)
    }

    fun postAllClearNotification(
        title: String,
        body: String,
        silent: Boolean = false,
        replay: List<FlourishRecord> = emptyList()
    ) {
        val tap = if (replay.isEmpty()) openAppIntent()
        else NeutralizedTally.flourishTapIntent(context, 7, replay, NeutralizedTally.SOURCE_ALLCLEAR)
        val notif = NotificationCompat.Builder(context, CHANNEL_ALL_CLEAR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setDeleteIntent(allClearDeleteIntent())
            .setOnlyAlertOnce(silent)
            .build()
        safeNotify(NOTIF_ALLCLEAR, notif)
    }

    fun isAllClearNotificationActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.activeNotifications?.any { it.id == NOTIF_ALLCLEAR } == true
        } else {
            true
        }
    }

    private fun allClearDeleteIntent(): PendingIntent {
        val intent = Intent(context, AlertService::class.java).apply {
            action = ACTION_ALLCLEAR_DISMISSED
        }
        return PendingIntent.getService(
            context,
            12,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun postOfflineNotification(title: String, text: String, retryLabel: String, ignoreLabel: String? = null) {
        val notif = NotificationCompat.Builder(context, CHANNEL_OFFLINE)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_trident, retryLabel, retryPendingIntent())
            .setContentIntent(openAppIntent())
        if (ignoreLabel != null) {
            notif.addAction(R.drawable.ic_trident, ignoreLabel, ignoreRetryPendingIntent())
        }
        safeNotify(NOTIF_MILESTONE, notif.build())
    }

    fun postCriticalOfflineNotification(title: String, text: String, retryLabel: String, ignoreLabel: String? = null) {
        val notif = NotificationCompat.Builder(context, CHANNEL_OFFLINE_CRITICAL)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_trident, retryLabel, retryPendingIntent())
            .setContentIntent(openAppIntent())
        if (ignoreLabel != null) {
            notif.addAction(R.drawable.ic_trident, ignoreLabel, ignoreRetryPendingIntent())
        }
        safeNotify(NOTIF_OFFLINE_CRITICAL, notif.build())
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

    /**
     * Background monitoring could not be restarted (Android 12+ blocks starting a foreground
     * service from the background). Surface a tap-to-resume prompt: the tap brings the app to
     * the foreground, where [AlertService.start] is legal.
     */
    fun postMonitoringPaused() {
        val s = Strings.get(AppLanguage.EN)
        val notif = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(s.bootRestartPaused)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        safeNotify(NOTIF_MONITORING_PAUSED, notif)
    }

    private fun sirenUri(resName: String): Uri =
        Uri.parse("android.resource://${context.packageName}/raw/$resName")

    private fun notificationAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
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
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 1, intent, flags)
        } else {
            PendingIntent.getService(context, 1, intent, flags)
        }
    }

    private fun ignoreRetryPendingIntent(): PendingIntent {
        val intent = Intent(context, AlertService::class.java).setAction(ACTION_IGNORE_RETRY)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 2, intent, flags)
        } else {
            PendingIntent.getService(context, 2, intent, flags)
        }
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
