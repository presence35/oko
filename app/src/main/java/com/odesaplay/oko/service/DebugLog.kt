package com.odesaplay.oko
import com.odesaplay.oko.engine.distanceHaversine

import android.content.Context
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
import com.odesaplay.oko.engine.ThreatZone
import com.odesaplay.oko.engine.ThreatEngine
import com.odesaplay.oko.engine.ThreatProps
import com.odesaplay.oko.engine.NormalizedThreat
import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.toThreatType
import com.odesaplay.oko.engine.threatOblastId
import com.odesaplay.oko.engine.inOblast
import com.odesaplay.oko.engine.resolveOblastId
import com.odesaplay.oko.engine.AlertLevel

/**
 * The settings worth remembering in the audit trail: the ones that decide WHAT you are told
 * (bells, official channels, city scope, the night preset, the debris delay, the notification
 * policy). Presentation preferences (map style, card size, icon set, follow-me, tally scope) are
 * deliberately absent — they never change whether a raid reaches you, and [DebugLog] is the
 * ring that explains raids.
 *
 * Ordinal-indexed into `ActionLog.settingLabels`, so the vocabulary and its copy cannot drift.
 */
enum class LoggedSetting {
    ZoneBells, SlowRedBell, SlowYellowBell, FastRedBell, FastYellowBell,
    OfficialRed, OfficialYellow, CityScope, SirenOverride, NightSleep,
    DebrisDelay, AutoDismissAllClear, NotifyPolicy, CriticalOffline
}

/** Event kinds shown in the Debug log screen. */
enum class DebugLogKind {
    OFFICIAL_ON, OFFICIAL_OFF, OFFICIAL_LEVEL, ZONE_ENTER, REGION_THREAT, FLOURISH, NOTIF, ACTION
}

/**
 * Why a decision landed the way it did. [FIRED] = a notification was actually posted
 * (zone siren or official alert / all-clear); every other value is a "why not".
 * The RATE_LIMITED / ONCE_PER_THREAT / ONCE_PER_TYPE trio mirrors [PolicyReason]:
 * the NotifyPlugin swallowed a would-be sound.
 */
enum class DebugLogReason {
    FIRED, BELL_MUTED, ALREADY_NOTIFIED, COALESCED, TYPE_OFF, ADVISORY, STALE,
    OUTSIDE_ZONES, TOGGLE_OFF, RATE_LIMITED, ONCE_PER_THREAT, ONCE_PER_TYPE, NOTIF_EXPIRED,
    LEVEL_CHANGED, MUTED, UNMUTED, SETTING_CHANGED, USER_RETRY, TALLY_POSTED, PAUSED
}

/**
 * One debug decision row. Every entry carries the day/night flag and the effective siren
 * override (which picks the notification channel, and with it the alert's sound and
 * vibration), plus whether a notification fired and why not ([reason]). [tier] is the
 * effective zone for zone alerts (what actually
 * rang), null for official alerts / region sweeps; [level] is the official alert severity
 * (RED/YELLOW) for OFFICIAL_ON rows, null otherwise. [distanceKm] is from the focus point.
 */
/**
 * Where a threat was when its decision was logged — the feed's own reported fix, never a
 * dead-reckoned guess: the log records what the source said, and prediction stays where it
 * already lives (the map, through the engine). Carried so a row can be tapped and flown to
 * long after the track is gone. Null for rows that name no threat.
 */
data class LogPosition(val lat: Double, val lon: Double)

data class DebugLogEntry(
    val atMillis: Long,
    val kind: DebugLogKind,
    val night: Boolean,
    val sirenOverride: Boolean,
    val notified: Boolean,
    val reason: DebugLogReason,
    val threatId: String?,
    val threatType: ThreatType?,
    val tier: ThreatZone?,
    val distanceKm: Double?,
    val locality: String?,
    val level: AlertLevel? = null,
    /** Canonical oblast id of the EVENT (from its locality: the alerted oblast for official
     *  rows, the threat's place for threat rows). Null when unresolvable. */
    val scopeOblastId: String? = null,
    /** True when the event was evaluated AGAINST THE USER — the only honest "about me" test.
     *  Set at write time, where the service already knows the focus: in the focus oblast, or
     *  it rang, or it was declined for a reason that only applies to the user (bells off, type
     *  off, in-zone-coalesced). Never inferred from the threat's own place at read time. */
    val aboutMe: Boolean = false,
    /** The threat's reported position at decision time; null for rows naming no threat. */
    val position: LogPosition? = null
)

/**
 * Per-tick snapshot of every input the Debug log needs to reconstruct WHY a notification
 * did or didn't fire. Built by [AlertService] from its already-computed state — the log
 * never recomputes decisions, it just describes the service's own ones.
 */
data class DebugLogContext(
    val threats: Map<String, NormalizedThreat>,
    val focus: LatLng?,
    val token: String?,
    val enabledTypes: Set<ThreatType>,
    val zoneThreats: Map<String, ThreatZone>,
    val alertable: Map<String, ThreatZone>,
    /** This tick's per-threat plugin verdicts (alertable ids only). */
    val verdicts: Map<String, PluginVerdict>,
    /** Threat id driving the shown notification (the post winner), if any. */
    val winnerId: String?,
    /** A raid mute is live, so the winner was deliberately NOT delivered. The sweep must not
     *  report a FIRED row for a notification that was intentionally suppressed. */
    val muted: Boolean = false,
    val night: Boolean,
    val sirenOverride: Boolean,
    val now: Long,
    /** Focus oblast token for this tick — the write-time "about me" test. */
    val focusToken: String? = null,
    /** Merged per-type props from SourceRegistry.typeCatalog — never a concrete source map. */
    val typeCatalog: Map<String, ThreatProps> = emptyMap()
)

/**
 * Persisted ring buffer of alert/threat decisions, written by [AlertService] every tick,
 * read by the Debug log screen. A pure audit trail: entries are append-only snapshots of
 * the service's own evaluation, never inputs back into it, so removing the feature is just
 * deleting the write hooks.
 */
object DebugLog {

    internal const val MAX_ENTRIES = 4269

    /** Entries older than this are pruned on load/append (24 hours — a debug trail, not history). */
    internal const val AUTO_CLEAR_AGE_MS = 24L * 60 * 60 * 1000

    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries.asStateFlow()

    /** Threat-id → transition fingerprint; only logged when it changes (no per-tick spam). */
    private val verdictsLock = Any()
    private val verdicts = mutableMapOf<String, String>()

    @Volatile private var attached = false
    private var appContext: Context? = null
    private val attachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attachDone = CompletableDeferred<Unit>()

    /** Restore persisted entries from DataStore. Call once at startup (idempotent). */
    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        attachScope.launch {
            attachLoaded()
            attachDone.complete(Unit)
        }
    }

    /** Wait for [attach]'s async restore to finish (before writes race it). */
    suspend fun awaitAttached() = attachDone.await()

    /** Wipe the whole log (Debug log screen "Clear" button). */
    fun clear() {
        synchronized(verdictsLock) { verdicts.clear() }
        _entries.value = emptyList()
        persist()
    }

    /** Append a single event (official transitions and zone fires/exits go through here). */
    fun record(entry: DebugLogEntry) {
        append(entry)
        persist()
    }

    /**
     * Convenience for official-alert on/off events. [reason] FIRED when the alert/all-clear
     * notification was actually posted, COALESCED when a zone alert won the tick,
     * TOGGLE_OFF when official-alert notifications were switched off.
     */
    fun recordOfficial(
        kind: DebugLogKind,
        night: Boolean,
        sirenOverride: Boolean,
        notified: Boolean,
        reason: DebugLogReason,
        threatId: String?,
        threatType: ThreatType?,
        locality: String?,
        distanceKm: Double?,
        /** Canonical oblast id of the OFFICIAL area. Required rather than derived here: [locality]
         *  is a LOCALIZED display name, and re-stemming one back into an id is exactly how raion
         *  alerts ("Dniprovskyi district") silently lost their oblast. The caller holds the
         *  alert, so the caller states the id. */
        scopeOblastId: String?,
        level: AlertLevel? = null,
        now: Long
    ) {
        record(
            DebugLogEntry(
                now, kind, night, sirenOverride, notified, reason,
                threatId, threatType, null, distanceKm, locality, level,
                scopeOblastId = scopeOblastId, aboutMe = true
            )
        )
    }

    /**
     * Notification-lifecycle row: the all-clear notification was retired by its TTL. Deliberately
     * NOT an alert decision — the episode's own end is already logged by [recordOfficial] — so it
     * carries no outcome ([notifyOutcome] -> INFO) and never enters the three tallies.
     */
    fun recordAllClearExpired(locality: String?, now: Long) {
        record(
            DebugLogEntry(
                now, DebugLogKind.NOTIF, night = false, sirenOverride = false,
                notified = false, reason = DebugLogReason.NOTIF_EXPIRED,
                threatId = null, threatType = null, tier = null, distanceKm = null,
                locality = locality, scopeOblastId = resolveOblastId(locality), aboutMe = true
            )
        )
    }

    /**
     * Notification-lifecycle row: the all-clear was posted again while one was already in the
     * shade. Same INFO bucket as [recordAllClearExpired] — not a second alert decision, so the
     * episode still reads as exactly one clear. Exists because a repeated post is the reason a
     * 20-min TTL can look like it never fired.
     */
    fun recordAllClearReposted(locality: String?, now: Long) {
        record(
            DebugLogEntry(
                now, DebugLogKind.NOTIF, night = false, sirenOverride = false,
                notified = false, reason = DebugLogReason.ALREADY_NOTIFIED,
                threatId = null, threatType = null, tier = null, distanceKm = null,
                locality = locality, scopeOblastId = resolveOblastId(locality), aboutMe = true
            )
        )
    }

    /**
     * The severity of a live official episode changed (red → yellow or back) with no new
     * episode and no second siren. Logged because the notification is silently rewritten and
     * the story card otherwise shows an unbroken RED for an hour that was half yellow. INFO,
     * not a decision: the episode's own onset and end are already recorded.
     */
    fun recordOfficialLevel(
        level: AlertLevel,
        locality: String?,
        scopeOblastId: String?,
        now: Long
    ) {
        record(
            DebugLogEntry(
                now, DebugLogKind.OFFICIAL_LEVEL, night = false, sirenOverride = false,
                notified = false, reason = DebugLogReason.LEVEL_CHANGED,
                threatId = null, threatType = null, tier = null, distanceKm = null,
                locality = locality, level = level, scopeOblastId = scopeOblastId, aboutMe = true
            )
        )
    }

    /**
     * A user action worth remembering: the bells were muted, a setting was switched, a retry
     * was tapped. High-signal only — never a per-tap firehose, because this ring is the alert
     * audit trail and a tap stream would evict the decisions it exists to explain. [label] is
     * the localized sentence shown as the row (the [recordFlourish] convention for a row with
     * no place), [detail] an optional value suffix.
     */
    fun recordAction(
        reason: DebugLogReason,
        label: String,
        detail: String? = null,
        now: Long
    ) {
        record(
            DebugLogEntry(
                now, DebugLogKind.ACTION, night = false, sirenOverride = false,
                notified = false, reason = reason,
                threatId = null, threatType = null, tier = null, distanceKm = null,
                locality = if (detail.isNullOrBlank()) label else "$label · $detail",
                aboutMe = true
            )
        )
    }

    /**
     * The monitor loop slept through a doze (or the process was frozen) and woke up [gapMs]
     * later, so that tick's decisions were skipped and every state re-baselined instead. INFO:
     * it is the honest marker for a hole in the timeline — the gap a beta tester is looking for
     * when an overnight run "updated wrong".
     */
    fun recordPaused(window: SleepWindow, now: Long) {
        val minutes = (window.durationMs / 60_000L).coerceAtLeast(1L)
        record(
            DebugLogEntry(
                now, DebugLogKind.NOTIF, night = false, sirenOverride = false,
                notified = false, reason = DebugLogReason.PAUSED,
                threatId = null, threatType = null, tier = null, distanceKm = null,
                locality = "paused ${minutes}m", aboutMe = false
            )
        )
    }

    /**
     * The neutralized tally posted its own notification. It is a real notification the user
     * saw, so it belongs in the audit trail, but it is not an alert decision: it stays INFO
     * and never moves the notified / covered / not-notified tallies.
     */
    fun recordTallyPosted(locality: String?, count: Int, now: Long) {
        record(
            DebugLogEntry(
                now, DebugLogKind.NOTIF, night = false, sirenOverride = false,
                notified = false, reason = DebugLogReason.TALLY_POSTED,
                threatId = null, threatType = null, tier = null, distanceKm = null,
                locality = locality?.let { if (count > 0) "$it · $count" else it },
                aboutMe = false
            )
        )
    }

    /**
     * Audit trail for the shoot-down flourish replay — tap OUTCOMES only (started /
     * blocked by the animation toggle), never per-bullet spam. [FIRED] = the show actually
     * started; every other reason is a "why not". [detail] is a short locale-neutral
     * suffix (e.g. "7x2" = records×groups) shown as grey text on the row.
     */
    fun recordFlourish(reason: DebugLogReason, detail: String? = null, now: Long) {
        record(
            DebugLogEntry(
                now, DebugLogKind.FLOURISH, night = false, sirenOverride = false,
                notified = reason == DebugLogReason.FIRED,
                reason = reason, threatId = null, threatType = null, tier = null,
                distanceKm = null, locality = detail
            )
        )
    }

    /**
     * Synchronous FIRED row, recorded at post time by AlertService when it actually
     * shows the zone notification. Seeds the verdict fingerprint so the next sweep
     * dedups the steady state instead of logging a follow-up ALREADY_NOTIFIED row.
     */
    fun recordZoneFired(
        threatId: String,
        threatType: ThreatType?,
        tier: ThreatZone,
        night: Boolean,
        sirenOverride: Boolean,
        distanceKm: Double?,
        locality: String?,
        scopeOblastId: String?,
        position: LogPosition?,
        now: Long
    ) {
        val entry = DebugLogEntry(
            now, DebugLogKind.ZONE_ENTER, night, sirenOverride,
            true, DebugLogReason.FIRED, threatId, threatType, tier, distanceKm, locality,
            scopeOblastId = scopeOblastId, aboutMe = true, position = position
        )
        synchronized(verdictsLock) { verdicts[threatId] = fingerprintOf(entry) }
        record(entry)
    }

    /**
     * Per-tick verdict sweep over every threat in the active region (within the type's
     * reach of the focus point, or in the focus oblast). Logs each threat's lifecycle on
     * transition only: entering a zone (coalesced / bell muted / already notified),
     * being in the region but outside the zones (or excluded: stale / advisory / type off),
     * and leaving the region. FIRED rows are never produced here — they are recorded
     * synchronously at post time via [recordZoneFired]. Uses the service's own computed
     * maps ([DebugLogContext]) — never re-derives decision formulas.
     */
    fun sweep(ctx: DebugLogContext) {
        val newEntries = synchronized(verdictsLock) {
            val (entries, nextVerdicts) = computeSweep(ctx, verdicts)
            verdicts.clear()
            verdicts.putAll(nextVerdicts)
            entries
        }
        newEntries.forEach { record(it) }
    }

    internal fun zoneEntry(
        t: NormalizedThreat,
        spatial: ThreatZone,
        distKm: Double,
        ctx: DebugLogContext
    ): DebugLogEntry {
        val effective = ctx.alertable[t.id]
        val notified: Boolean
        val reason: DebugLogReason
        when {
            effective == null -> {
                notified = false
                reason = DebugLogReason.BELL_MUTED
            }
            ctx.verdicts[t.id]?.kind == VerdictKind.SOUND -> {
                // Already recorded synchronously at post time; the shared SIREN
                // fingerprint dedups this, so it never double-logs.
                notified = true
                reason = DebugLogReason.FIRED
            }
            ctx.verdicts[t.id]?.kind == VerdictKind.SUPPRESS -> {
                notified = false
                reason = when (ctx.verdicts[t.id]?.reason) {
                    PolicyReason.RATE_LIMITED -> DebugLogReason.RATE_LIMITED
                    PolicyReason.ONCE_PER_TYPE -> DebugLogReason.ONCE_PER_TYPE
                    else -> DebugLogReason.ONCE_PER_THREAT
                }
            }
            ctx.muted -> {
                // The user muted the raid: the zone was reached and the policy said SOUND, but
                // nothing was delivered on purpose. BELL_MUTED is exactly that fact, and it is
                // the only honest row for a winner that was suppressed rather than shown.
                notified = false
                reason = DebugLogReason.BELL_MUTED
            }
            ctx.winnerId == t.id -> {
                notified = false
                reason = DebugLogReason.ALREADY_NOTIFIED
            }
            else -> {
                notified = false
                reason = DebugLogReason.COALESCED
            }
        }
        // The tier shown is what actually rang (effective after arming toggles) — mirrors
        // AlertService, which posts with the effective tier, never the raw spatial one.
        val tier = effective ?: spatial
        val place = t.locality ?: t.district ?: t.region
        val mine = aboutUser(reason, notified = notified, inFocusOblast = inFocusOblast(t, ctx))
        return DebugLogEntry(
            ctx.now, DebugLogKind.ZONE_ENTER, ctx.night, ctx.sirenOverride,
            notified, reason, t.id, t.type.toThreatType(), tier, distKm,
            place, scopeOblastId = threatOblastId(t), aboutMe = mine,
            position = LogPosition(t.lat, t.lon)
        )
    }

    internal fun regionEntry(t: NormalizedThreat, distKm: Double, ctx: DebugLogContext): DebugLogEntry {
        val engine = ThreatEngine(ctx.typeCatalog)
        val reason = when {
            engine.isStale(t, engine.propsFor(t.type), ctx.now) -> DebugLogReason.STALE
            t.advisory -> DebugLogReason.ADVISORY
            t.type.toThreatType() !in ctx.enabledTypes -> DebugLogReason.TYPE_OFF
            else -> DebugLogReason.OUTSIDE_ZONES
        }
        val place = t.locality ?: t.district ?: t.region
        val mine = aboutUser(reason, notified = false, inFocusOblast = inFocusOblast(t, ctx))
        return DebugLogEntry(
            ctx.now, DebugLogKind.REGION_THREAT, ctx.night, ctx.sirenOverride,
            false, reason, t.id, t.type.toThreatType(), null, distKm,
            place, scopeOblastId = threatOblastId(t), aboutMe = mine,
            position = LogPosition(t.lat, t.lon)
        )
    }

    /**
     * Zone rows that differ only by who-won-the-tick share one fingerprint: FIRED
     * (recorded at post time), COALESCED (lost the tick) and ALREADY_NOTIFIED (steady)
     * all mean "in zone, handled" — so a new threat logs once and the steady state
     * stays silent. Tier/night changes still log. BELL_MUTED stays distinct (actionable).
     */
    internal fun fingerprintOf(entry: DebugLogEntry): String {
        val state = when (entry.reason) {
            DebugLogReason.FIRED, DebugLogReason.ALREADY_NOTIFIED, DebugLogReason.COALESCED,
            DebugLogReason.RATE_LIMITED, DebugLogReason.ONCE_PER_THREAT, DebugLogReason.ONCE_PER_TYPE -> "SIREN"
            else -> entry.reason.name
        }
        return "${entry.kind.name}|${entry.tier?.name}|$state|${entry.night}"
    }

    private fun append(entry: DebugLogEntry) {
        val now = System.currentTimeMillis()
        _entries.value =
            pruneDebugEntries(_entries.value + entry, now, AUTO_CLEAR_AGE_MS).takeLast(MAX_ENTRIES)
    }

    private fun persist() {
        val context = appContext ?: return
        attachScope.launch { ServiceState(context).setDebugLog(serializeDebugLog(_entries.value)) }
    }

    private suspend fun attachLoaded() {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val loaded = parseDebugLog(ServiceState(context).debugLog().first())
        val pruned = pruneDebugEntries(loaded, now, AUTO_CLEAR_AGE_MS)
        _entries.value = pruned
        if (pruned != loaded) persist()
    }
}

    /** Drop entries older than [maxAgeMs] — the 24-hour auto-clear. Pure, unit-tested. */
internal fun pruneDebugEntries(
    entries: List<DebugLogEntry>,
    now: Long,
    maxAgeMs: Long
): List<DebugLogEntry> = entries.filter { now - it.atMillis < maxAgeMs }

/**
 * The one "is this about the user?" rule, applied at write time. In the focus oblast counts,
 * and so does anything the user would have heard — it rang, or it was declined for a reason
 * that is personal (bells off, type off, in-zone coalesced). A threat sitting in someone
 * else's oblast, outside the zones, is not about the user. Pure, unit-tested.
 */
internal fun aboutUser(
    reason: DebugLogReason,
    notified: Boolean,
    inFocusOblast: Boolean
): Boolean = when {
    inFocusOblast -> true
    notified -> true
    reason == DebugLogReason.BELL_MUTED -> true
    reason == DebugLogReason.TYPE_OFF -> true
    reason == DebugLogReason.COALESCED -> true
    reason == DebugLogReason.RATE_LIMITED -> true
    reason == DebugLogReason.ONCE_PER_THREAT -> true
    reason == DebugLogReason.ONCE_PER_TYPE -> true
    else -> false
}

/** Whether a threat's own place sits in the user's focus oblast. */
private fun inFocusOblast(t: NormalizedThreat, ctx: DebugLogContext): Boolean =
    inOblast(t.region, t.district, t.locality, ctx.token)

/**
 * Pure verdict sweep: given the per-tick context and the current threat-id → fingerprint
 * map, returns the entries to log this tick and the next fingerprint map. Transition-only —
 * an unchanged fingerprint (including the FIRED/ALREADY_NOTIFIED steady "SIREN" state)
 * produces nothing. Extracted from [DebugLog.sweep] so the rules are unit-testable.
 */
internal fun computeSweep(
    ctx: DebugLogContext,
    verdicts: Map<String, String>
): Pair<List<DebugLogEntry>, Map<String, String>> {
    val focus = ctx.focus ?: return emptyList<DebugLogEntry>() to verdicts
    val nextVerdicts = verdicts.toMutableMap()
    val newEntries = mutableListOf<DebugLogEntry>()
    val regionIds = mutableSetOf<String>()
    for (t in ctx.threats.values) {
        if (t.status == "resolved" || t.areaOnly) continue
        val distKm = distanceHaversine(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
        if (distKm > (ctx.typeCatalog[t.type]?.reachKm ?: 1500.0) &&
            !inOblast(t.region, t.district, t.locality, ctx.token)
        ) continue
        regionIds.add(t.id)

        val entry = ctx.zoneThreats[t.id]?.let { DebugLog.zoneEntry(t, it, distKm, ctx) }
            ?: DebugLog.regionEntry(t, distKm, ctx)
        val fingerprint = DebugLog.fingerprintOf(entry)
        if (nextVerdicts[t.id] == fingerprint) continue
        nextVerdicts[t.id] = fingerprint
        newEntries.add(entry)
    }
    nextVerdicts.keys.filterNot { it in regionIds }.toList().forEach { id ->
        nextVerdicts.remove(id)
    }
    return newEntries to nextVerdicts
}

/**
 * Serialized form of the log — pipe-delimited lines, one per event:
 * "at|kind|night|siren|notified|reason|threatId|type|tier|distance|locality|level".
 * Blank fields mean null. Pure, so persistence is unit-testable without DataStore.
 */
internal fun serializeDebugLog(entries: List<DebugLogEntry>): String =
    entries.joinToString("\n") { entry ->
        listOf(
            entry.atMillis,
            entry.kind.name,
            entry.night,
            entry.sirenOverride,
            entry.notified,
            entry.reason.name,
            entry.threatId ?: "",
            entry.threatType?.name ?: "",
            entry.tier?.name ?: "",
            entry.distanceKm?.toString() ?: "",
            entry.locality ?: "",
            entry.level?.name ?: "",
            entry.scopeOblastId ?: "",
            entry.aboutMe,
            entry.position?.lat?.toString() ?: "",
            entry.position?.lon?.toString() ?: ""
        ).joinToString("|")
    }

/** Reverse of [serializeDebugLog]; skips malformed lines and caps the ring buffer. */
internal fun parseDebugLog(raw: String, maxEntries: Int = DebugLog.MAX_ENTRIES): List<DebugLogEntry> =
    raw.split('\n').mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 12) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = DebugLogKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        val night = parts[2].toBooleanStrictOrNull() ?: return@mapNotNull null
        val siren = parts[3].toBooleanStrictOrNull() ?: return@mapNotNull null
        val notified = parts[4].toBooleanStrictOrNull() ?: return@mapNotNull null
        val reason = DebugLogReason.entries.firstOrNull { it.name == parts[5] } ?: return@mapNotNull null
        val threatId = parts[6].takeIf { it.isNotEmpty() }
        val type = ThreatType.entries.firstOrNull { it.name == parts[7] }
        val tier = ThreatZone.entries.firstOrNull { it.name == parts[8] }
        val dist = parts[9].toDoubleOrNull()
        val locality = parts[10].takeIf { it.isNotEmpty() }
        val level = parts.getOrNull(11)?.let { name -> AlertLevel.entries.firstOrNull { it.name == name } }
        val scope = parts.getOrNull(12)?.takeIf { it.isNotEmpty() }
        // Backward compatible: older rows predate aboutMe; the field's absence is not a
        // failure, so it defaults to false rather than dropping the whole line.
        val aboutMe = parts.getOrNull(13)?.toBooleanStrictOrNull() ?: false
        // Position arrived after the first persisted rows; a half-written or non-finite pair is
        // no position at all, so the row simply stays un-tappable instead of flying to 0,0.
        val lat = parts.getOrNull(14)?.toDoubleOrNull()
        val lon = parts.getOrNull(15)?.toDoubleOrNull()
        val position = if (lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
        ) LogPosition(lat, lon) else null
        DebugLogEntry(at, kind, night, siren, notified, reason, threatId, type, tier, dist, locality, level, scope, aboutMe, position)
    }.takeLast(maxEntries)