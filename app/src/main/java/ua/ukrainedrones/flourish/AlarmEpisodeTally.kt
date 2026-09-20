package ua.ukrainedrones

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ua.ukrainedrones.source.ThreatRemoved

/**
 * Morale-only per-alarm summary: buffers resolutions while the focus oblast's official
 * alarm (red or yellow) is live, then posts one summary when it ends. Fully independent
 * of [NeutralizedTally] (own count/memory/dedup-ring/notification) and of the official
 * all-clear path, so it also fires when official notifications are off.
 */
class AlarmEpisodeTally(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        const val ACTION_ALARM_EPISODE_DISMISS = "ua.ukrainedrones.ALARM_EPISODE_DISMISS"
        private const val NOTIF_ALARM_EPISODE = AlertNotificationManager.NOTIF_ALARM_EPISODE
    }

    private val tallyLock = Any()
    private var episodeCount = 0
    private val perTypeCounts = mutableMapOf<ThreatType, Int>()

    private val justFunEnabled = MutableStateFlow(false)
    private val episodeEnabled = MutableStateFlow(false)

    init {
        scope.launch {
            UserPrefs(context).preferences
                .map { it.justFunMasterEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    justFunEnabled.value = enabled
                    if (!enabled) reset()
                }
        }
        scope.launch {
            UserPrefs(context).preferences
                .map { it.alarmEpisodeTallyEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    episodeEnabled.value = enabled
                    if (!enabled) reset()
                }
        }
    }

    private data class ResolvedRecord(
        val lat: Double,
        val lon: Double,
        val type: ThreatType,
        val region: String?
    )
    private val episodeMemory = ArrayDeque<ResolvedRecord>()

    // NEPTUN re-sends resolutions — same guard as the running tally so a duplicate frame
    // neither inflates the episode nor plants twin replay records.
    private val seenRemovalIds = ArrayDeque<String>()

    private data class EpisodeSnapshot(
        val count: Int,
        val typeCounts: Map<ThreatType, Int>,
        val memory: List<ResolvedRecord>
    )

    /** A new alarm window opened on the focus: drop the previous window, keep the dedup ring. */
    fun begin() {
        synchronized(tallyLock) {
            episodeCount = 0
            perTypeCounts.clear()
            episodeMemory.clear()
        }
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ALARM_EPISODE)
        } catch (_: SecurityException) {}
    }

    /** Buffer a resolution into the open window. Never notifies — [finish] owns that. */
    fun onResolved(removed: ThreatRemoved) {
        if (!justFunEnabled.value || !episodeEnabled.value) return
        synchronized(tallyLock) {
            if (seenRemovalIds.contains(removed.id)) return
            seenRemovalIds.addLast(removed.id)
            while (seenRemovalIds.size > 64) seenRemovalIds.removeFirst()
            episodeCount++
            perTypeCounts[removed.type] = (perTypeCounts[removed.type] ?: 0) + 1
            episodeMemory.addLast(ResolvedRecord(removed.lat, removed.lon, removed.type, removed.region))
        }
    }

    /** The alarm window closed: post the one summary for it, replacing any previous window. */
    fun finish(city: String, lang: AppLanguage, officialAlertsEnabled: Boolean) {
        if (!justFunEnabled.value || !episodeEnabled.value) {
            synchronized(tallyLock) {
                episodeCount = 0
                perTypeCounts.clear()
                episodeMemory.clear()
            }
            return
        }
        val snapshot = synchronized(tallyLock) {
            EpisodeSnapshot(episodeCount, perTypeCounts.toMap(), episodeMemory.toList())
        }
        if (snapshot.count == 0) {
            // Users who disabled official alerts don't care about alarms — spare them the nothingness.
            if (!officialAlertsEnabled) return
            postQuiet(city, lang)
            return
        }
        postSummary(snapshot, city, lang)
    }

    fun reset() {
        synchronized(tallyLock) {
            episodeCount = 0
            perTypeCounts.clear()
            episodeMemory.clear()
        }
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ALARM_EPISODE)
        } catch (_: SecurityException) {}
    }

    private fun postSummary(snapshot: EpisodeSnapshot, city: String, lang: AppLanguage) {
        scope.launch {
            val s = Strings.get(lang)
            val breakdown = snapshot.typeCounts.entries
                .sortedWith(compareByDescending<Map.Entry<ThreatType, Int>> { it.value }.thenBy { it.key.ordinal })
                .joinToString(" · ") { (type, count) ->
                    val info = ThreatTypeCatalog.INFO[type]
                    val label = if (info != null && lang == AppLanguage.UA) info.labelUa else info?.labelEn ?: type.name
                    "$label $count"
                }
            val builder = NotificationCompat.Builder(context, AlertNotificationManager.CHANNEL_ALARM_EPISODE)
                .setSmallIcon(R.drawable.ic_trident)
                .setContentTitle(String.format(s.alarmEpisodeTitleFormat, city))
                .setContentText(String.format(s.alarmEpisodeBodyFormat, resolvedThreatsPhrase(snapshot.count, lang)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(episodeTapPendingIntent(snapshot.memory))
                .setDeleteIntent(episodeDismissPendingIntent())
            if (breakdown.isNotBlank()) builder.setSubText(breakdown)
            safeNotify(NOTIF_ALARM_EPISODE, builder.build())
        }
    }

    private fun postQuiet(city: String, lang: AppLanguage) {
        scope.launch {
            val s = Strings.get(lang)
            val builder = NotificationCompat.Builder(context, AlertNotificationManager.CHANNEL_ALARM_EPISODE)
                .setSmallIcon(R.drawable.ic_trident)
                .setContentTitle(String.format(s.alarmEpisodeTitleFormat, city))
                .setContentText(s.alarmEpisodeQuietText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setAutoCancel(true)
                .setDeleteIntent(episodeDismissPendingIntent())
            safeNotify(NOTIF_ALARM_EPISODE, builder.build())
        }
    }

    private fun episodeDismissPendingIntent(): PendingIntent {
        val intent = Intent(context, NeutralizedDismissReceiver::class.java).apply {
            action = ACTION_ALARM_EPISODE_DISMISS
        }
        return PendingIntent.getBroadcast(
            context, 5, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Tap replays the window's interceptions on the map. Reuses NeutralizedTally's
     * flourish extra keys so MainActivity's existing replay parser picks them up as-is.
     */
    private fun episodeTapPendingIntent(memory: List<ResolvedRecord>): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NeutralizedTally.EXTRA_FLOURISH_LATS, memory.map { it.lat }.toDoubleArray())
            putExtra(NeutralizedTally.EXTRA_FLOURISH_LONS, memory.map { it.lon }.toDoubleArray())
            putExtra(NeutralizedTally.EXTRA_FLOURISH_TYPES, memory.map { it.type.name }.toTypedArray())
            putExtra(NeutralizedTally.EXTRA_FLOURISH_REGIONS, memory.map { it.region }.toTypedArray())
        }
        return PendingIntent.getActivity(
            context, 6, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun safeNotify(id: Int, notif: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — silently skip.
        }
    }

}
