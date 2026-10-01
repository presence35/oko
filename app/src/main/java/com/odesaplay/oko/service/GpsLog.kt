package com.odesaplay.oko

import android.content.Context
import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.distanceHaversine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.odesaplay.oko.service.ServiceState

/**
 * How far we can vouch for the position the alert engine is evaluating right now.
 *
 * The distinction this type exists for: **silence from the location system is not proof that the
 * phone cannot be located.** Our own subscriptions are screen-gated and distance-gated
 * (LocationTracker), so a still phone legitimately hears nothing for as long as it likes. Reporting
 * that as "GPS missing" is what the old single `gpsUnreliable` flag did, and it was a lie. So:
 *
 * - [NOT_VERIFIED] — we asked and could not confirm. Age alone, no access failure.
 * - [ACCESS_BLOCKED] — a definitive, directly-read fact: no permission, or no provider enabled.
 * - [NONE] — verified within the check interval.
 */
enum class GpsIssue { NONE, NOT_VERIFIED, ACCESS_BLOCKED }

/**
 * Pure derivation of [GpsIssue] from the facts LocationTracker publishes, on the caller's `now`
 * (never a wall-clock read inside, so it stays deterministic and testable). Deliberately blind to
 * *why* a fix is old: [staleAfterMs] is the verify interval, and the verify loop is what keeps
 * [ageMs] honest in the first place.
 */
internal fun resolveGpsIssue(
    followMe: Boolean,
    hasPosition: Boolean,
    ageMs: Long?,
    accessBlocked: Boolean,
    staleAfterMs: Long = GpsLog.STALE_AFTER_MS
): GpsIssue = when {
    !followMe -> GpsIssue.NONE
    accessBlocked -> GpsIssue.ACCESS_BLOCKED
    !hasPosition -> GpsIssue.NOT_VERIFIED
    ageMs == null || ageMs >= staleAfterMs -> GpsIssue.NOT_VERIFIED
    else -> GpsIssue.NONE
}

/** What changed in the location feed, as a persisted log row. */
enum class GpsEventKind {
    /** We could not confirm the position for [durationSec]; the last known one stayed in use. */
    UNVERIFIED,

    /** A position landed again. [detailKm] = how far we had actually drifted while blind, null
     *  when we hadn't moved (the honest, common case). [accuracyM] = the fix's own accuracy. */
    VERIFIED,

    /** Definitive: location permission revoked or every provider disabled. */
    BLOCKED
}

/** One row of the GPS health log. Append-only audit trail, never an input back into the engine. */
data class GpsLogEntry(
    val atMillis: Long,
    val kind: GpsEventKind,
    val durationSec: Long? = null,
    val detailKm: Double? = null,
    val accuracyM: Int? = null
)

/**
 * Persisted ring buffer of location-health episodes, shown in the Logs screen next to the
 * connection log. Fed one observation per service tick; writes only on transition, so a long
 * quiet stretch costs a single in-progress row rather than a row per tick.
 *
 * Like [ConnectionLog] this is a sibling engine, not an extension of it: connection episodes and
 * location episodes have different shapes (no network transport, a drift distance instead of a
 * duration+source pair) and merging them would make both harder to reason about. They are merged
 * only in the UI, chronologically, by LogsScreen.
 */
object GpsLog {

    internal const val MAX_ENTRIES = 50

    /** A position is "verified" if something fed it within one verify interval. */
    internal const val STALE_AFTER_MS = 15 * 60 * 1000L

    /** Below this, an unverified blip is a tunnel, not an event — it never hits the log. */
    internal const val GRACE_MS = 60_000L

    /** Drift smaller than this is indistinguishable from a coarse fix's own error. */
    internal const val DRIFT_REPORTED_KM = 25.0

    private val _entries = MutableStateFlow<List<GpsLogEntry>>(emptyList())
    val entries: StateFlow<List<GpsLogEntry>> = _entries.asStateFlow()

    @Volatile private var pending: GpsLogEntry? = null
    @Volatile private var issue: GpsIssue? = null

    /** Where the held position was when the current blind episode started, for drift. */
    @Volatile private var blindPos: LatLng? = null

    @Volatile private var attached = false
    private var appContext: Context? = null
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachDone = CompletableDeferred<Unit>()

    /** Restore persisted entries. Idempotent; call once from the service. */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        attachScope.launch {
            _entries.value = parseGpsLog(ServiceState(context.applicationContext).gpsLog().first())
            attachDone.complete(Unit)
        }
    }

    suspend fun awaitAttached() = attachDone.await()

    /**
     * One observation from the service tick. [now] is threaded in (never read from the clock) so
     * the transition rules are testable. [accuracyM] is the last fix's own accuracy, [pos] the
     * position currently in use — kept so a recovery can report how far we had actually drifted.
     */
    fun observe(issue: GpsIssue, now: Long, accuracyM: Int?, pos: LatLng?) {
        val prev = this.issue
        // Drift is only knowable across a blind episode — the position we held when it started vs
        // the one that ended it — and "blind" includes a denied permission: a phone with location
        // switched off moves just as far as one that is merely stale. Sub-threshold values are
        // dropped here rather than in the caller, so no caller can report a coarse fix's own
        // jitter as travel.
        val driftKm = if (issue == GpsIssue.NONE && prev != null && prev != GpsIssue.NONE &&
            blindPos != null && pos != null) {
            val km = distanceHaversine(blindPos!!.lat, blindPos!!.lon, pos.lat, pos.lon) / 1000.0
            if (km >= DRIFT_REPORTED_KM) km else null
        } else null
        val t = commitGpsLogState(
            prevIssue = prev,
            issue = issue,
            now = now,
            pending = pending,
            entries = _entries.value,
            maxEntries = MAX_ENTRIES,
            graceMs = GRACE_MS,
            accuracyM = accuracyM,
            driftKm = driftKm
        )
        if (issue != GpsIssue.NONE && (prev == null || prev == GpsIssue.NONE)) blindPos = pos
        if (t == null) {
            this.issue = issue
            return
        }
        _entries.value = t.entries
        pending = t.nextPending
        this.issue = issue
        if (t.persistLog) persist()
    }

    /** The in-progress unverified episode with its running duration, or null when verified. */
    fun currentEpisode(now: Long): GpsLogEntry? =
        pending?.let { GpsLogEntry(it.atMillis, it.kind, (now - it.atMillis) / 1000) }

    private fun persist() {
        val context = appContext ?: return
        attachScope.launch { ServiceState(context).setGpsLog(serializeGpsLog(_entries.value)) }
    }
}

/**
 * Serialized form of the log — pipe-delimited lines, one per event:
 * "at|kind|durationSec|driftKm|accuracyM". Blank fields mean null. Pure, so persistence is
 * unit-testable without DataStore.
 */
internal fun serializeGpsLog(entries: List<GpsLogEntry>): String =
    entries.joinToString("\n") { entry ->
        listOf(
            entry.atMillis,
            entry.kind.name,
            entry.durationSec ?: "",
            entry.detailKm?.toString() ?: "",
            entry.accuracyM ?: ""
        ).joinToString("|")
    }

/** Reverse of [serializeGpsLog]; skips malformed lines and caps the ring buffer. */
internal fun parseGpsLog(raw: String, maxEntries: Int = GpsLog.MAX_ENTRIES): List<GpsLogEntry> =
    raw.split('\n').mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = GpsEventKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        GpsLogEntry(
            at,
            kind,
            parts[2].toLongOrNull(),
            parts.getOrNull(3)?.toDoubleOrNull(),
            parts.getOrNull(4)?.toIntOrNull()
        )
    }.takeLast(maxEntries)

/** Result of one [commitGpsLogState] step. */
internal data class GpsLogTransition(
    val entries: List<GpsLogEntry>,
    val nextPending: GpsLogEntry?,
    val persistLog: Boolean
)

/**
 * Pure transition rules for [GpsLog.observe]. Returns null when the observation changes nothing,
 * so a 1s/30s tick is free. An unverified episode that never lasts [graceMs] is dropped entirely
 * (tunnels, elevators); one that does is committed as a row with its duration, and the recovery
 * that ends it is committed as a second row carrying the accuracy and, when we actually drifted,
 * the distance. [driftKm] is pre-gated by the caller so coarse-fix jitter can never be reported
 * as travel.
 */
internal fun commitGpsLogState(
    prevIssue: GpsIssue?,
    issue: GpsIssue,
    now: Long,
    pending: GpsLogEntry?,
    entries: List<GpsLogEntry>,
    maxEntries: Int,
    graceMs: Long,
    accuracyM: Int? = null,
    driftKm: Double? = null
): GpsLogTransition? {
    if (prevIssue == issue && driftKm == null) return null
    var newEntries = entries
    var dirty = false

    fun append(entry: GpsLogEntry) {
        newEntries = (newEntries + entry).takeLast(maxEntries)
        dirty = true
    }

    when (issue) {
        GpsIssue.ACCESS_BLOCKED -> {
            if (prevIssue != GpsIssue.ACCESS_BLOCKED) append(GpsLogEntry(now, GpsEventKind.BLOCKED))
            return GpsLogTransition(newEntries, null, dirty)
        }
        GpsIssue.NOT_VERIFIED -> {
            if (prevIssue != GpsIssue.NOT_VERIFIED) {
                return GpsLogTransition(newEntries, GpsLogEntry(now, GpsEventKind.UNVERIFIED), dirty)
            }
            return null
        }
        GpsIssue.NONE -> {
            if (pending != null) {
                val durSec = (now - pending.atMillis) / 1000
                if (durSec * 1000 >= graceMs) {
                    append(pending.copy(durationSec = durSec))
                    append(GpsLogEntry(now, GpsEventKind.VERIFIED, accuracyM = accuracyM, detailKm = driftKm))
                }
                return GpsLogTransition(newEntries, null, dirty)
            }
            // Nothing to close, but the exit still deserves an entry when access came back (a
            // denied permission is a user action, and its end is the row they went looking for),
            // or when we measured a real drift while blind.
            if (prevIssue == GpsIssue.ACCESS_BLOCKED || driftKm != null) {
                append(GpsLogEntry(now, GpsEventKind.VERIFIED, accuracyM = accuracyM, detailKm = driftKm))
            }
            return if (dirty) GpsLogTransition(newEntries, null, true) else null
        }
    }
}
