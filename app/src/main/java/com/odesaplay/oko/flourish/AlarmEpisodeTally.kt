package com.odesaplay.oko

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
import com.odesaplay.oko.source.ThreatRemoved

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
        const val ACTION_ALARM_EPISODE_DISMISS = "com.odesaplay.oko.ALARM_EPISODE_DISMISS"
        private const val NOTIF_ALARM_EPISODE = AlertNotificationManager.NOTIF_ALARM_EPISODE

        /**
         * How long a posted row stays mergeable: one night. Past that the next alarm starts its own
         * row rather than summing into yesterday's. Also the row's own `setTimeoutAfter`, so the
         * platform retires it even when no new alarm ever re-posts the id — the rule lives in the
         * notification, not just in our memory of it.
         */
        const val MERGE_WINDOW_MS = 12 * 60 * 60_000L

                /** The replay buffer a merged row may carry. Two shows' worth of bullets is plenty; past
         *  that the oldest groups go, so a restless night cannot grow the row without bound. */
        const val MERGED_MEMORY_CAP = 128

        /**
         * Folds one finished alarm into the shade row. Pure, so the merge rules are JVM-tested without
         * a notification manager: same city and still inside the window merges, anything else starts
         * fresh — and one unmeasured alarm zeroes the total while the count survives.
         */
        internal fun mergeInto(
            prev: PostedRow?,
            city: String,
            snapshot: EpisodeSnapshot,
            durationMin: Int,
            measured: Boolean,
            nowMs: Long
        ): PostedRow {
            val mergeable = prev != null && prev.city == city && nowMs - prev.postedAtMs < MERGE_WINDOW_MS
            if (mergeable) {
                val mergedCounts = (prev.typeCounts.keys + snapshot.typeCounts.keys)
                    .associateWith { (prev.typeCounts[it] ?: 0) + (snapshot.typeCounts[it] ?: 0) }
                return PostedRow(
                    city = city,
                    alarmCount = prev.alarmCount + 1,
                    totalMin = if (prev.allMeasured && measured) prev.totalMin + durationMin else 0,
                    allMeasured = prev.allMeasured && measured,
                    count = prev.count + snapshot.count,
                    typeCounts = mergedCounts,
                    memory = (prev.memory + snapshot.memory).takeLast(MERGED_MEMORY_CAP),
                    postedAtMs = nowMs
                )
            }
            return PostedRow(
                city = city,
                alarmCount = 1,
                totalMin = if (measured) durationMin else 0,
                allMeasured = measured,
                count = snapshot.count,
                typeCounts = snapshot.typeCounts,
                memory = snapshot.memory.takeLast(MERGED_MEMORY_CAP),
                postedAtMs = nowMs
            )
        }
    }

    private val tallyLock = Any()
    private var episodeCount = 0
    private val perTypeCounts = mutableMapOf<ThreatType, Int>()
    private var episodeCity: String? = null
    private var episodeStartMs: Long = 0L
    /** Whether this process watched this episode begin — the only basis for stating a duration. */
    private var episodeFromOnset: Boolean = false

    // Master "Morale" gate is the shared [moraleGate]; the episode sub-toggle stays local.
    private val episodeEnabled = MutableStateFlow(false)

    init {
        moraleGate.ejectWhenOff(scope) { reset() }
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

    internal data class ResolvedRecord(
        val lat: Double,
        val lon: Double,
        val type: ThreatType,
        val region: String?
    )
    private val episodeMemory = ArrayDeque<ResolvedRecord>()

    // NEPTUN re-sends resolutions — same guard as the running tally so a duplicate frame
    // neither inflates the episode nor plants twin replay records.
    private val seenRemovalIds = ArrayDeque<String>()

    internal data class EpisodeSnapshot(
        val count: Int,
        val typeCounts: Map<ThreatType, Int>,
        val memory: List<ResolvedRecord>
    )

    /**
     * The row currently in the shade: everything [finish] has posted, so the next alarm for the
     * same city adds to it instead of evicting it. A quiet five-minute blip no longer deletes the
     * report of the two-hour night it follows — it becomes one more alarm in that row.
     *
     * [totalMin] is 0 when any merged alarm was never honestly measured: a duration is stated only
     * when every alarm in the row was watched from its onset, so the row keeps the alarm count and
     * drops the minutes instead of summing a guess.
     */
    internal data class PostedRow(
        val city: String,
        val alarmCount: Int,
        val totalMin: Int,
        val allMeasured: Boolean,
        val count: Int,
        val typeCounts: Map<ThreatType, Int>,
        val memory: List<ResolvedRecord>,
        val postedAtMs: Long
    )
    private var postedRow: PostedRow? = null

    /**
     * A new alarm window opened on the focus: drop the previous window, keep the dedup ring.
     *
     * [fromOnset] says this process watched the episode begin, and defaults to false: a caller that
     * cannot prove it gets the dateless summary rather than a duration measured from whenever it
     * happened to start running.
     */
    fun begin(city: String, startMs: Long = System.currentTimeMillis(), fromOnset: Boolean = false) {
        synchronized(tallyLock) {
            episodeCount = 0
            perTypeCounts.clear()
            episodeMemory.clear()
            episodeCity = city
            episodeStartMs = startMs
            episodeFromOnset = fromOnset
        }
        // The posted row is left alone on purpose: it is the night's accumulated report, and the
        // alarm that just opened will merge into it on finish rather than replace it.
    }

    /** Buffer a resolution into the open window. Never notifies — [finish] owns that. */
    fun onResolved(removed: ThreatRemoved) {
        if (!moraleGate.isOn || !episodeEnabled.value) return
        synchronized(tallyLock) {
            if (seenRemovalIds.contains(removed.id)) return
            seenRemovalIds.addLast(removed.id)
            while (seenRemovalIds.size > 64) seenRemovalIds.removeFirst()
            episodeCount++
            perTypeCounts[removed.type] = (perTypeCounts[removed.type] ?: 0) + 1
            episodeMemory.addLast(ResolvedRecord(removed.lat, removed.lon, removed.type, removed.region))
        }
    }

    /** The alarm window closed: fold it into the shade row and post the row. */
    fun finish(currentCity: String, lang: AppLanguage, officialAlertsEnabled: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (!moraleGate.isOn || !episodeEnabled.value) {
            clearWindow()
            return
        }
        val row = synchronized(tallyLock) {
            val c = episodeCity ?: currentCity
            // A duration is a measurement, not an arithmetic convenience: an episode this process
            // never watched begin has no honest start, so it states none and takes the dateless
            // title — the rule the all-clear already applies to a restored latch
            // (`RestoredResolution.EXPIRE_SILENTLY`).
            val measured = episodeFromOnset
            val dur = if (measured) ((nowMs - episodeStartMs) / 60_000L).toInt().coerceAtLeast(1) else 0
            val snapshot = EpisodeSnapshot(episodeCount, perTypeCounts.toMap(), episodeMemory.toList())
            clearWindow()
            mergeInto(postedRow, c, snapshot, dur, measured, nowMs).also { postedRow = it }
        }
        if (row.count == 0) {
            if (!officialAlertsEnabled) return
            postQuiet(row, lang)
            return
        }
        postSummary(row, lang)
    }

    /**
     * The window stopped being ours to judge: our focus left the region, so the latch that could
     * have ended it was dropped with it. Not an ending — nothing is posted, and a summary already
     * in the shade is someone else's history and survives.
     */
    fun abandon() = clearWindow()

    /**
     * The user dismissed the row, or the feature went off: the shade row comes down with the
     * window, and the next alarm starts the night over rather than merging into a report nobody
     * can see anymore.
     */
    fun reset() {
        synchronized(tallyLock) { postedRow = null }
        clearWindow()
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ALARM_EPISODE)
        } catch (_: SecurityException) {}
    }

    /** Empties the window and drops its identity. Leaves the posted notification alone: [reset]
     *  and [abandon] differ only in whether that survives. */
    private fun clearWindow() = synchronized(tallyLock) {
        episodeCount = 0
        perTypeCounts.clear()
        episodeMemory.clear()
        episodeCity = null
        episodeStartMs = 0L
        episodeFromOnset = false
    }

    /** Synchronous read of the window's buffered records for the official all-clear tap,
     *  so it replays the same show as this summary. After [finish] the window is empty, so this
     *  reads the posted row instead — the tap must replay what the text claims. */
    fun snapshot(): List<FlourishRecord> {
        synchronized(tallyLock) {
            val records = if (episodeCity != null) episodeMemory.toList() else postedRow?.memory.orEmpty()
            return records.map { FlourishRecord(it.lat, it.lon, it.type, it.region) }
        }
    }

    private fun postSummary(row: PostedRow, lang: AppLanguage) {
        scope.launch {
            val s = Strings.get(lang)
            val breakdown = row.typeCounts.entries
                .sortedWith(compareByDescending<Map.Entry<ThreatType, Int>> { it.value }.thenBy { it.key.ordinal })
                .joinToString(" · ") { (type, count) ->
                    val info = ThreatTypeCatalog.INFO[type]
                    val label = info?.label(lang) ?: type.name
                    "$label $count"
                }
            val title = alarmEpisodeTitle(s, row.city, row.totalMin, row.alarmCount)
            val builder = AlertNotificationManager(context).buildNotification(NOTIF_ALARM_EPISODE, AlertNotificationManager.CHANNEL_ALARM_EPISODE)
                .setSmallIcon(R.drawable.ic_trident)
                .setContentTitle(title)
                .setContentText(String.format(s.alarmEpisodeBodyFormat, resolvedThreatsPhrase(row.count, lang)))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setTimeoutAfter(MERGE_WINDOW_MS)
                .setContentIntent(episodeTapPendingIntent(row.memory))
                .setDeleteIntent(episodeDismissPendingIntent())
            if (breakdown.isNotBlank()) builder.setSubText(breakdown)
            safeNotify(NOTIF_ALARM_EPISODE, builder.build())
        }
    }

    private fun postQuiet(row: PostedRow, lang: AppLanguage) {
        scope.launch {
            val s = Strings.get(lang)
            val title = alarmEpisodeTitle(s, row.city, row.totalMin, row.alarmCount)
            val builder = AlertNotificationManager(context).buildNotification(NOTIF_ALARM_EPISODE, AlertNotificationManager.CHANNEL_ALARM_EPISODE)
                .setSmallIcon(R.drawable.ic_trident)
                .setContentTitle(title)
                .setContentText(s.alarmEpisodeQuietText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(MERGE_WINDOW_MS)
                .setContentIntent(quietOpenIntent())
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
        return NeutralizedTally.flourishTapIntent(
            context, 6,
            memory.map { FlourishRecord(it.lat, it.lon, it.type, it.region) },
            NeutralizedTally.SOURCE_EPISODE
        )
    }

    /** Zero-count summary has no show to replay — its tap just opens the map. */
    private fun quietOpenIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlertNotificationManager.EXTRA_SHOW_MAP, true)
        }
        return PendingIntent.getActivity(
            context, 9, intent,
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
