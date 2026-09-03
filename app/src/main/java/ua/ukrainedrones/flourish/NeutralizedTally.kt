package ua.ukrainedrones

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.ukrainedrones.connection.ThreatRemoved

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
        const val ACTION_NEUTRALIZED_DISMISS = "ua.ukrainedrones.NEUTRALIZED_DISMISS"
        const val EXTRA_FLOURISH_LATS = "flourish_lats"
        const val EXTRA_FLOURISH_LONS = "flourish_lons"
        const val EXTRA_FLOURISH_TYPES = "flourish_types"
        const val CHANNEL_NEUTRALIZED = "neutralized"
        private const val NOTIF_NEUTRALIZED = 6
    }

    private var neutralizedCount = 0
    private val perTypeCounts = mutableMapOf<ThreatType, Int>()
    // Running memory of resolved threats (position + type) so tapping the tally notification can
    // replay a shot-down show. Capped at 21; flourish survives alerts and background.
    private data class ResolvedRecord(val lat: Double, val lon: Double, val type: ThreatType)
    private val resolvedMemory = ArrayDeque<ResolvedRecord>()

    // NEPTUN re-sends resolutions (the same re-send the map's dud mechanism guards against) —
    // remember recent removal ids so a duplicate frame neither inflates the count nor plants
    // two memory records at the same spot ("two bullets, one threat").
    private val seenRemovalIds = ArrayDeque<String>()

    /** A server-driven resolution just arrived: count it into the tally and remember it for the
     *  replay. Keeps the last 21 (the tally count itself can run much higher after a long
     *  absence — the replay only needs enough to be fun, not exhaustive). */
    fun onResolved(removed: ThreatRemoved, lang: AppLanguage) {
        if (seenRemovalIds.contains(removed.id)) return
        seenRemovalIds.addLast(removed.id)
        while (seenRemovalIds.size > 64) seenRemovalIds.removeFirst()
        neutralizedCount++
        perTypeCounts[removed.type] = (perTypeCounts[removed.type] ?: 0) + 1
        resolvedMemory.addLast(ResolvedRecord(removed.lat, removed.lon, removed.type))
        while (resolvedMemory.size > 21) resolvedMemory.removeFirst()
        postNeutralizedTally(lang)
    }

    fun eject() {}

    /** The tally notification was swiped away (or its replay consumed in the app) — reset the
     *  count and memory so any later neutralizations start a fresh tally, and drop the
     *  notification itself. */
    fun reset() {
        neutralizedCount = 0
        perTypeCounts.clear()
        resolvedMemory.clear()
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_NEUTRALIZED)
        } catch (_: SecurityException) {}
    }

    /** Silent, dismissible running tally of resolved threats near the focus. Re-posted on the
     *  same id with an incremented count each time; swiping it away (delete intent) resets the
     *  count so it stays gone until the next resolution starts a fresh tally. */
    private fun postNeutralizedTally(lang: AppLanguage) {
        scope.launch {
            val allUkraine = runCatching { UserPrefs(context).neutralizedTallyAllUkraine().first() }.getOrDefault(false)
            val badge = if (allUkraine) "🇺🇦" else ""
            val breakdown = perTypeCounts.entries
                .sortedWith(compareByDescending<Map.Entry<ThreatType, Int>> { it.value }.thenBy { it.key.ordinal })
                .joinToString(" · ") { (type, count) ->
                    val info = ThreatTypeCatalog.INFO[type]
                    val label = if (info != null && lang == AppLanguage.UA) info.labelUa else info?.labelEn ?: type.name
                    "$label $count"
                }
            val builder = NotificationCompat.Builder(context, CHANNEL_NEUTRALIZED)
                .setSmallIcon(R.drawable.ic_trident)
                .setContentTitle("$badge ${resolvedThreatsPhrase(neutralizedCount, lang)}")
                .setContentText(breakdown)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(neutralizedTapPendingIntent())
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
    private fun neutralizedTapPendingIntent(): PendingIntent {
        val latArr = resolvedMemory.map { it.lat }.toDoubleArray()
        val lonArr = resolvedMemory.map { it.lon }.toDoubleArray()
        val typeArr = resolvedMemory.map { it.type.name }.toTypedArray()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FLOURISH_LATS, latArr)
            putExtra(EXTRA_FLOURISH_LONS, lonArr)
            putExtra(EXTRA_FLOURISH_TYPES, typeArr)
        }
        return PendingIntent.getActivity(
            context, 3, intent,
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