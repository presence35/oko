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
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.first
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
import com.odesaplay.oko.service.SuspendState

/**
 * The alert notification's first button: silence every alert of one threat type for good, the
 * same fact as the type toggle in Settings. Null when the alert is not attributable to a type
 * (a plain region red, any yellow), and the row falls back to [AlertActions.ok].
 */
data class MuteTypeAction(val label: String, val type: ThreatType)

/** Alert-notification action-button labels. Persona lives in the title/body, never here. */
data class AlertActions(
    val ok: String,
    val muteType: MuteTypeAction?,
    val muteRaid: String,
    val mute10: String
)

/**
 * Handles notification channels, notification building, and dispatching for [AlertService].
 */
class AlertNotificationManager(private val context: Context) {

    /** How a notification id is posted in the shade: the one place an id gets this, so a
     *  producer can never invent a role and two ids can never share one by accident.
     *
     *  A group key alone separates nothing. The app posts no group summary anywhere, and
     *  Android folds a summary-less group into its app-level auto-bundle
     *  (`GroupHelper.isGroupChildWithoutSummary` → `addToUngroupedAndMaybeAggregate`) — the
     *  same bundle this app's ungrouped rows land in. So a summary-less child and an
     *  ungrouped row still meet inside one bundle, which is how the resolved tally
     *  ("1 Drone resolved") came to share a bundle with the always-on trident.
     *
     *  [Summary] is therefore the only role the platform cannot collect: a group summary is
     *  never a bundle child, so a childless one renders as a standalone row whatever the
     *  OEM's bundling heuristics do. [Standalone] is the weaker claim — no key at all, so no
     *  explicit group can claim it — and is what the monitoring family gets.
     *
     *  An unmapped id throws rather than inheriting some group's bundle. */
    sealed interface Posting {
        /** A plain row: no group key. */
        data object Standalone : Posting
        /** A child of [key]'s group. */
        data class Child(val key: String) : Posting
        /** The group itself: immune to the platform's app-level bundle. */
        data class Summary(val key: String) : Posting
    }

    companion object {
        private val NotifRed = AppPalette.AlertRed.toInt()
        private val NotifYellow = AppPalette.AlertYellow.toInt()
        const val ACTION_RETRY = "com.odesaplay.oko.RETRY"
        const val ACTION_IGNORE_RETRY = "com.odesaplay.oko.IGNORE_RETRY"
        const val ACTION_ALLCLEAR_DISMISSED = "com.odesaplay.oko.ALLCLEAR_DISMISSED"
        const val ACTION_ALERT_OK = "com.odesaplay.oko.ALERT_OK"
        const val ACTION_MUTE_RAID = "com.odesaplay.oko.MUTE_RAID"
        const val ACTION_MUTE_10 = "com.odesaplay.oko.MUTE_10"
        const val ACTION_MUTE_TYPE = "com.odesaplay.oko.MUTE_TYPE"

        const val EXTRA_REVEAL_ID = "reveal_threat_id"
        const val EXTRA_REVEAL_LAT = "reveal_threat_lat"
        const val EXTRA_REVEAL_LON = "reveal_threat_lon"
        const val EXTRA_SHOW_UPDATE = "show_update"
        const val EXTRA_SHOW_MAP = "show_map"
        const val EXTRA_MUTE_TYPE = "mute_threat_type"

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
        const val NOTIF_NEUTRALIZED = 4
        const val NOTIF_MILESTONE = 5
        const val NOTIF_OFFLINE_CRITICAL = 6
        const val NOTIF_UPDATE = 7
        const val NOTIF_ALARM_EPISODE = 8
const val NOTIF_MONITORING_PAUSED = 9
        /** The "we were asleep" notice. One id on purpose: a re-post REPLACES it, so a night of
         *  Doze windows updates a single shade row instead of stacking dozens. */
        const val NOTIF_SLEEP = 10

        /** Bump to delete + recreate all managed channels (sound/importance/attrs are
         *  frozen by Android at creation — this is the only way a change takes effect). */
        const val CHANNEL_SCHEMA_VERSION = 6

        /** Vibration reaches the user ONLY through a channel: a per-notification
         *  `setVibrate` has been ignored since API 26, which is our minSdk — so a
         *  per-alert level could never have worked, and the level scale is gone rather
         *  than left as decoration. Normal alerts buzz lightly, alarm-bypass ones hard. */
        private val VIBRATION_LIGHT = longArrayOf(0, 200, 100, 200)
        private val VIBRATION_STRONG = longArrayOf(0, 600, 100, 600, 100, 600)

        const val GROUP_ALERT = "oko_alert"
        const val GROUP_OFFLINE = "oko_offline"
        const val GROUP_TALLY = "oko_tally"
        const val GROUP_EPISODE = "oko_episode"
        const val GROUP_UPDATE = "oko_update"

        /** See [Posting]: ids map to a posting role here, never to a bare group key. */
        fun postingFor(id: Int): Posting = when (id) {
            NOTIF_MONITOR, NOTIF_MONITORING_PAUSED, NOTIF_SLEEP -> Posting.Standalone
            NOTIF_ALERT, NOTIF_ALLCLEAR -> Posting.Child(GROUP_ALERT)
            NOTIF_MILESTONE, NOTIF_OFFLINE_CRITICAL -> Posting.Child(GROUP_OFFLINE)
            NOTIF_UPDATE -> Posting.Child(GROUP_UPDATE)
            NOTIF_NEUTRALIZED -> Posting.Summary(GROUP_TALLY)
            NOTIF_ALARM_EPISODE -> Posting.Child(GROUP_EPISODE)
            else -> error("no notification posting role mapped for id $id")
        }

        fun areNotificationsEnabled(context: Context): Boolean {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        /**
         * Channels whose silencing costs the user an alert they asked for. One list, read by the
         * log bundle and the settings conflict check, so neither can drift from the definitions
         * above. Informational channels (monitor, tally, all-clear, the offline milestone) are
         * deliberately absent: muting one of those loses no alert.
         */
        val alertChannelIds = listOf(
            CHANNEL_ALERTS_INNER,
            CHANNEL_ALERTS_OUTER,
            CHANNEL_ALERTS_INNER_ALARM,
            CHANNEL_ALERTS_OUTER_ALARM,
            CHANNEL_OFFLINE_CRITICAL
        )

        /**
         * Which channel an alert takes: the alarm pair when the user asked to be woken, the plain
         * pair otherwise; a zone alert carries its own zone, an official alert (no zone) takes one
         * from its level. Pure, and the single owner of this decision — the poster and the audit
         * log both ask here rather than each deciding for itself.
         */
        fun alertChannelId(zone: ThreatZone?, level: String, sirenOverride: Boolean): String {
            val target = zone ?: if (level.lowercase() == "yellow") ThreatZone.OUTER else ThreatZone.INNER
            return when {
                target == ThreatZone.INNER && sirenOverride -> CHANNEL_ALERTS_INNER_ALARM
                target == ThreatZone.INNER -> CHANNEL_ALERTS_INNER
                sirenOverride -> CHANNEL_ALERTS_OUTER_ALARM
                else -> CHANNEL_ALERTS_OUTER
            }
        }

        /**
         * The OS's own view of what we may deliver. One reader for both consumers (log bundle,
         * settings conflicts), so a diagnostic and a warning can never disagree about the same fact.
         */
        fun notificationState(context: Context): NotificationState {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val policyAccess = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)
            return NotificationState(
                enabled = areNotificationsEnabled(context),
                channels = alertChannelIds.map { id ->
                    val channel = runCatching { nm.getNotificationChannel(id) }.getOrNull()
                    // A channel that does not exist yet is not "muted" — it is created on the next
                    // start, and reporting it as muted would warn about a state the user never set.
                    NotifChannel(
                        id = id,
                        importance = channel?.importance ?: NotificationManager.IMPORTANCE_DEFAULT,
                        hasSound = channel?.sound != null
                    )
                },
                policyAccess = policyAccess,
                interruptionFilter = if (policyAccess) {
                    runCatching { nm.currentInterruptionFilter }
                        .getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
                } else {
                    NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                },
                ringerMode = am.ringerMode,
                suspended = SuspendState.isSuspended(context)
            )
        }

        /**
         * Deep-link to this app's OS notification page — the only place the enabled toggle
         * lives, so it is the sole correct destination for a reason whose cause is there.
         */
        fun openNotificationSettings(context: Context) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    /** Suspend: the schema check reads DataStore, and a caller on the main thread would park
     *  there (first-install file create is slow enough to ANR). Never call this synchronously. */
    suspend fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        enforceChannelSchema(nm)
        val en = Strings.get(AppLanguage.EN)
        defineChannels(nm, en)

        nm.notificationChannels
            .filter { it.id !in managedChannels }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    private val managedChannels = (alertChannelIds + listOf(
            CHANNEL_MONITOR,
            CHANNEL_ALL_CLEAR,
            CHANNEL_OFFLINE,
            NeutralizedTally.CHANNEL_NEUTRALIZED,
            CHANNEL_ALARM_EPISODE,
            CHANNEL_UPDATE
        )).toSet()

    /** Deletes + recreates managed channels when the schema (or the bypass-silent config
     *  baked into the critical channel's audio attrs) changed since last applied. */
    private suspend fun enforceChannelSchema(nm: NotificationManager) {
        val svc = ServiceState(context.applicationContext)
        val appliedSchema = svc.channelSchemaVersion().first()
        val bypassSilent = UserPrefs(context).preferences.first().criticalOfflineBypassSilent
        val bypassApplied = svc.criticalChannelBypassApplied().first()
        if (appliedSchema != CHANNEL_SCHEMA_VERSION) {
            managedChannels.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        } else if (bypassApplied == null || bypassApplied != bypassSilent) {
            runCatching { nm.deleteNotificationChannel(CHANNEL_OFFLINE_CRITICAL) }
        } else {
            return
        }
        svc.setChannelSchemaVersion(CHANNEL_SCHEMA_VERSION)
        svc.setCriticalChannelBypassApplied(bypassSilent)
    }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    suspend fun updateChannels(s: Strings.StringSet) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        defineChannels(nm, s)
    }

    /** Blocking-safe, I/O-free: the platform *rejects* a startForeground notification whose
     *  channel doesn't exist yet (crash on a fresh install), and this is the one channel the
     *  monitor notification needs. Everything else waits for [createChannels]. */
    fun ensureMonitorChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        defineMonitorChannel(nm, Strings.get(AppLanguage.EN))
    }

    /** DEFAULT, not LOW: importance is the shade's section classifier (`GroupHelper.getSections`
     *  puts `importance < IMPORTANCE_DEFAULT` in the collapsed "Silent notifications" bucket),
     *  so a LOW monitor row shared that collapsed section with the resolved tally and the trident's
     *  alert-level colour cost a tap. DEFAULT + no sound/vibration still makes no noise — silence
     *  comes from the sound attr, section placement from the level. */
    private fun defineMonitorChannel(nm: NotificationManager, s: Strings.StringSet) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, s.notifChannelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = s.notifChannelDesc
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private suspend fun defineChannels(nm: NotificationManager, s: Strings.StringSet) {
        defineMonitorChannel(nm, s)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_INNER, s.alertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.alertChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_LIGHT
                setSound(sirenUri("air_raid_siren"), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_OUTER, s.outerAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.outerAlertChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_LIGHT
                setSound(sirenUri("zone_outer"), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            // DEFAULT, not HIGH: the all-clear sounds (as CHANNEL_ALARM_EPISODE already does with
            // the same sample) but must never pop over the screen — a heads-up "all clear" is an
            // interruption, not information.
            NotificationChannel(CHANNEL_ALL_CLEAR, s.allClearChannelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = s.allClearChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_LIGHT
                setSound(sirenUri("all_clear"), notificationAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_INNER_ALARM, s.alarmAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.alarmAlertChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_STRONG
                setSound(sirenUri("air_raid_siren"), alarmAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS_OUTER_ALARM, s.outerAlarmAlertChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.outerAlarmAlertChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_STRONG
                setSound(sirenUri("zone_outer"), alarmAttributes())
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFLINE, s.offlineChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.offlineChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_LIGHT
                setSound(sirenUri("critical_offline"), notificationAttributes())
            }
        )

        val bypassSilent = UserPrefs(context).preferences.first().criticalOfflineBypassSilent
        val criticalAttrs = if (bypassSilent) alarmAttributes() else notificationAttributes()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFLINE_CRITICAL, s.offlineCriticalChannelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = s.offlineCriticalChannelDesc
                enableVibration(true)
                vibrationPattern = VIBRATION_STRONG
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
                vibrationPattern = VIBRATION_LIGHT
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
        val b = buildNotification(NOTIF_MONITOR, CHANNEL_MONITOR)
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

    suspend fun postAlertNotification(
        channel: String,
        title: String,
        body: String,
        revealThreat: NormalizedThreat? = null,
        silent: Boolean = false,
        muted: Boolean = false,
        actions: AlertActions? = null
    ) {
        val idSuffix = revealThreat?.let { t ->
            if (UserPrefs(context).preferences.first().showThreatIdsOnMap) " · #${t.id.takeLast(4)}" else ""
        }.orEmpty()
        val b = buildNotification(NOTIF_ALERT, channel)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(body + idSuffix)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(revealThreat))
            .setOnlyAlertOnce(silent)
            // Full silence (channel sound + vibration); setOnlyAlertOnce is a repost knob.
            .setSilent(muted)
        actions?.let { a ->
            val muteType = a.muteType
            if (muteType != null) b.addAction(0, muteType.label, muteTypeAction(muteType.type))
            else b.addAction(0, a.ok, alertAction(ACTION_ALERT_OK, 21))
            b.addAction(0, a.muteRaid, alertAction(ACTION_MUTE_RAID, 22))
            b.addAction(0, a.mute10, alertAction(ACTION_MUTE_10, 23))
        }
        safeNotify(NOTIF_ALERT, b.build())
    }

    fun postAllClearNotification(
        title: String,
        body: String,
        silent: Boolean = false,
        muted: Boolean = false,
        replay: List<FlourishRecord> = emptyList()
    ) {
        val tap = if (replay.isEmpty()) openAppIntent()
        else NeutralizedTally.flourishTapIntent(context, 7, replay, NeutralizedTally.SOURCE_ALLCLEAR)
        val notif = buildNotification(NOTIF_ALLCLEAR, CHANNEL_ALL_CLEAR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setDeleteIntent(allClearDeleteIntent())
            .setOnlyAlertOnce(silent)
            .setSilent(muted)
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

    /**
     * Offline escalation, one-shot. Deliberately action-free — the Retry/Ignore buttons live on
     * the ongoing monitor row alone. Three offline rows each carrying their own Retry meant one
     * fact with three action owners: the tap could not say which row it came from, the three
     * PendingIntents aliased, and feedback landed on a row the user had not touched.
     */
    fun postOfflineNotification(title: String, text: String) {
        val notif = buildNotification(NOTIF_MILESTONE, CHANNEL_OFFLINE)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        safeNotify(NOTIF_MILESTONE, notif.build())
    }

    /** One-shot critical escalation. Action-free for the same reason as [postOfflineNotification]:
     *  it escalates, the ongoing monitor row is where the user acts. */
    fun postCriticalOfflineNotification(title: String, text: String) {
        val notif = buildNotification(NOTIF_OFFLINE_CRITICAL, CHANNEL_OFFLINE_CRITICAL)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        safeNotify(NOTIF_OFFLINE_CRITICAL, notif.build())
    }

    fun postUpdateNotification(title: String, text: String) {
        val notif = buildNotification(NOTIF_UPDATE, CHANNEL_UPDATE)
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

    /** Build a notification in the role [postingFor] assigns its id. The single entrance for
     *  grouped notifications: callers pass the notification id and never a group key. */
    fun buildNotification(id: Int, channelId: String): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(context, channelId)
        return when (val posting = postingFor(id)) {
            is Posting.Standalone -> b
            is Posting.Child -> b.setGroup(posting.key)
            // A summary is the group itself, so it needs its key — and it is never swept into
            // the platform's app bundle the way a summary-less child is.
            is Posting.Summary -> b.setGroup(posting.key).setGroupSummary(true)
        }
    }

    /**
     * Background monitoring could not be restarted (Android 12+ blocks starting a foreground
     * service from the background). Surface a tap-to-resume prompt: the tap brings the app to
     * the foreground, where [AlertService.start] is legal.
     *
     * Two callers, two truths: [postMonitoringPaused] is the reboot case (auto-restart is off),
     * [postRestartBlocked] is a resurrection path the OS refused. The old single copy claimed a
     * reboot that never happened. `setOnlyAlertOnce` is load-bearing: three 15-minute paths fire
     * this, and without it every re-post re-alerts — the "notification every few minutes" report.
     */
    fun postMonitoringPaused() {
        postPaused(Strings.get(AppLanguage.EN).bootRestartPaused)
    }

    fun postRestartBlocked() {
        postPaused(Strings.get(AppLanguage.EN).monitoringRestartBlocked)
    }

    private fun postPaused(title: String) {
        val notif = buildNotification(NOTIF_MONITORING_PAUSED, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        safeNotify(NOTIF_MONITORING_PAUSED, notif)
    }

    /**
     * Announces a sleep that was worth reporting, and carries the one action that can reduce the
     * next one. Reuses [NOTIF_SLEEP] deliberately: a later sleep replaces this row instead of adding
     * another, so a night of Doze windows is one updating line rather than thirty. That is the whole
     * throttle — no timestamp pref, no boolean, no rate-limit code.
     *
     * Rides [CHANNEL_OFFLINE_CRITICAL] because it is the same message to the user — "Oko could not
     * warn you" — and that channel is already `IMPORTANCE_HIGH`, so the notice is actually seen
     * instead of sitting mute in the shade. It does NOT reuse the notification id: critical-offline
     * is a state that ends when the network returns and is cancelled on recovery, so sharing an id
     * would let a reconnect wipe this row, and an offline alert would overwrite it.
     *
     * Retires itself by [SleepPolicy.NOTICE_TTL_MS] via `setTimeoutAfter`, so nothing has to
     * remember a deadline, tick a timer, or hook the Activity lifecycle. `setAutoCancel` also lets
     * the user swipe it. [allowLabel] is the exemption button.
     */
    fun postSleepNotice(title: String, text: String, allowLabel: String) {
        val notif = buildNotification(NOTIF_SLEEP, CHANNEL_OFFLINE_CRITICAL)
            .setSmallIcon(R.drawable.ic_trident)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(SleepPolicy.NOTICE_TTL_MS)
            .addAction(R.drawable.ic_trident, allowLabel, exemptionIntent())
            .setContentIntent(exemptionIntent())
            .build()
        safeNotify(NOTIF_SLEEP, notif)
    }

    /** Takes the sleep row down early — used when the exemption lands, so the ask outlives its reason. */
    fun clearSleepNotice() {
        NotificationManagerCompat.from(context).cancel(NOTIF_SLEEP)
    }

    /**
     * The battery-exemption screen as a PendingIntent — the OEM-specific one when the device
     * resolves it, the system "allow unrestricted background use?" dialog otherwise.
     *
     * A `getActivity` intent rather than a service action on purpose: if the service received this
     * tap and then called `startActivity`, Android 10+ would block the background launch. Handing
     * the activity to the platform as a PendingIntent keeps the user's tap as the grant — which is
     * also why `BatteryOptimization.exemptionIntent` pre-resolves instead of relying on the
     * try/catch fallback that [BatteryOptimization.requestExemptionOemAware] can afford.
     */
    private fun exemptionIntent(): PendingIntent {
        val intent = BatteryOptimization.exemptionIntent(context)
        return PendingIntent.getActivity(
            context,
            13,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
        // Served by IgnoreRetryReceiver, not the service: the tap dismisses the rows without
        // paying for a (possibly cold) foreground-service start first.
        val intent = Intent(context, com.odesaplay.oko.service.IgnoreRetryReceiver::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, 2, intent, flags)
    }

    /** Alert action button -> [AlertService] (OK / Mute raid / Mute 10 min / Mute type). */
    private fun alertAction(action: String, requestCode: Int, extra: Pair<String, String>? = null): PendingIntent {
        val intent = Intent(context, AlertService::class.java).setAction(action)
        extra?.let { intent.putExtra(it.first, it.second) }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    /** "Mute <type>" -> [AlertService]. The request code is per type, so two types never share a
     *  PendingIntent (they carry different extras and would overwrite each other). */
    private fun muteTypeAction(type: ThreatType): PendingIntent =
        alertAction(ACTION_MUTE_TYPE, 30 + type.ordinal, EXTRA_MUTE_TYPE to type.name)

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
