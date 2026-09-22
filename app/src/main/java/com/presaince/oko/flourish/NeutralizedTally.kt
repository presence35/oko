package com.presaince.oko

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.presaince.oko.source.ThreatRemoved

/**
 * Service-side flourish facade: owns the shoot-down tally (count + running memory of resolved
 * threats) and its notification. The monitoring loop keeps only the thin gates (enabled pref +
 * focus-scope filter) and delegates every tally mechanic here, so the critical zone/alert loop
 * stays clean.
 */
class NeutralizedTally(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        const val ACTION_NEUTRALIZED_DISMISS = "com.presaince.oko.NEUTRALIZED_DISMISS"
        const val EXTRA_FLOURISH_LATS = "flourish_lats"
        const val EXTRA_FLOURISH_LONS = "flourish_lons"
        const val EXTRA_FLOURISH_TYPES = "flourish_types"
        const val EXTRA_FLOURISH_REGIONS = "flourish_regions"
        const val EXTRA_FLOURISH_SOURCE = "flourish_source"
        const val SOURCE_TALLY = "tally"
        const val SOURCE_EPISODE = "episode"
        const val SOURCE_ALLCLEAR = "allclear"
        const val CHANNEL_NEUTRALIZED = "neutralized"
        private const val NOTIF_NEUTRALIZED = 6

        /**
         * Shared tap target for every flourish notification (running tally, episode summary,
         * official all-clear): opens the app straight onto the shot-down replay with the given
         * records baked in. One builder so all three stay parseable by the same parser.
         */
        fun flourishTapIntent(
            context: Context,
            requestCode: Int,
            records: List<FlourishRecord>,
            source: String
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_FLOURISH_LATS, records.map { it.lat }.toDoubleArray())
                putExtra(EXTRA_FLOURISH_LONS, records.map { it.lon }.toDoubleArray())
                putExtra(EXTRA_FLOURISH_TYPES, records.map { it.type.name }.toTypedArray())
                putExtra(EXTRA_FLOURISH_REGIONS, records.map { it.region }.toTypedArray())
                putExtra(EXTRA_FLOURISH_SOURCE, source)
            }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    private val tallyLock = Any()
    private var neutralizedCount = 0
    private val perTypeCounts = mutableMapOf<ThreatType, Int>()

    /** Master "Morale" gate: live mirror of the master pref. [onResolved] no-ops while it's
     *  off, and flipping it off resets the running tally so nothing fun survives. */
    private val moraleEnabled = MutableStateFlow(false)

    init {
        scope.launch {
            UserPrefs(context).preferences
                .map { it.moraleMasterEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    moraleEnabled.value = enabled
                    if (!enabled) reset()
                }
        }
    }

    // Running memory of resolved threats (position + type) so tapping the tally notification can
    // replay a shot-down show. flourish survives alerts and background.
    private data class ResolvedRecord(
        val lat: Double,
        val lon: Double,
        val type: ThreatType,
        val region: String?
    )
    private val resolvedMemory = ArrayDeque<ResolvedRecord>()

    // NEPTUN re-sends resolutions (the same re-send the map's dud mechanism guards against) —
    // remember recent removal ids so a duplicate frame neither inflates the count nor plants
    // two memory records at the same spot ("two bullets, one threat").
    private val seenRemovalIds = ArrayDeque<String>()

    private data class TallySnapshot(
        val count: Int,
        val typeCounts: Map<ThreatType, Int>,
        val memory: List<ResolvedRecord>
    )

    /** A server-driven resolution just arrived: count it into the tally and remember it for the
     *  replay.  */
    fun onResolved(removed: ThreatRemoved, lang: AppLanguage) {
        if (!moraleEnabled.value) return
        val snapshot = synchronized(tallyLock) {
            if (seenRemovalIds.contains(removed.id)) return
            seenRemovalIds.addLast(removed.id)
            while (seenRemovalIds.size > 64) seenRemovalIds.removeFirst()
            neutralizedCount++
            perTypeCounts[removed.type] = (perTypeCounts[removed.type] ?: 0) + 1
            resolvedMemory.addLast(ResolvedRecord(removed.lat, removed.lon, removed.type, removed.region))
            TallySnapshot(neutralizedCount, perTypeCounts.toMap(), resolvedMemory.toList())
        }

        postNeutralizedTally(snapshot, lang)
    }

    fun eject() {}

    /** The tally notification was swiped away (or its replay consumed in the app) — reset the
     *  count and memory so any later neutralizations start a fresh tally, and drop the
     *  notification itself. The recent-id ring is KEPT: NEPTUN re-sends resolutions within its
     *  60s grace window, so without it the same threats would re-count and re-post the tally
     *  (~1 min after the user dismissed it) and the map would replay what was already seen. */
    fun reset() {
        synchronized(tallyLock) {
            neutralizedCount = 0
            perTypeCounts.clear()
            resolvedMemory.clear()
        }
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_NEUTRALIZED)
        } catch (_: SecurityException) {}
    }

    /** Silent, dismissible running tally of resolved threats near the focus. Re-posted on the
     *  same id with an incremented count each time; swiping it away (delete intent) resets the
     *  count so it stays gone until the next resolution starts a fresh tally. */
    private fun postNeutralizedTally(snapshot: TallySnapshot, lang: AppLanguage) {
        scope.launch {
            val allUkraine = runCatching { UserPrefs(context).preferences.first().neutralizedTallyAllUkraine }.getOrDefault(false)
            val badge = if (allUkraine) "🇺🇦" else ""
            val breakdown = snapshot.typeCounts.entries
                .sortedWith(compareByDescending<Map.Entry<ThreatType, Int>> { it.value }.thenBy { it.key.ordinal })
                .joinToString(" · ") { (type, count) ->
                    val info = ThreatTypeCatalog.INFO[type]
                    val label = info?.label(lang) ?: type.name
                    "$label $count"
                }
            val builder = NotificationCompat.Builder(context, CHANNEL_NEUTRALIZED)
                .setSmallIcon(R.drawable.ic_trident)
                .setContentTitle("$badge ${resolvedThreatsPhrase(snapshot.count, lang)}")
                .setContentText(breakdown)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(neutralizedTapPendingIntent(snapshot.memory))
                .setDeleteIntent(neutralizedDismissPendingIntent())
            safeNotify(NOTIF_NEUTRALIZED, builder.build())
        }
    }

    /** Reset the tally when the user swipes the notification away. */
    private fun neutralizedDismissPendingIntent(): PendingIntent {
        val intent = Intent(context, NeutralizedDismissReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Tap target for the tally notification: opens the app straight onto the shot-down replay.
     * Built as a direct Activity intent (no service trampoline — Android 12+ blocks starting an
     * activity from a notification-launched service) with the remembered resolutions baked in
     * right now, so the tap always replays the latest show.
     */
    private fun neutralizedTapPendingIntent(memory: List<ResolvedRecord>): PendingIntent {
        return flourishTapIntent(
            context, 3,
            memory.map { FlourishRecord(it.lat, it.lon, it.type, it.region) },
            SOURCE_TALLY
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