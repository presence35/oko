package ua.ukrainedrones
import ua.ukrainedrones.engine.distanceFlat

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
import ua.ukrainedrones.service.ServiceState
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.inOblast
import ua.ukrainedrones.engine.isFastType

/** Event kinds shown in the Debug log screen. */
enum class DebugLogKind { OFFICIAL_ON, OFFICIAL_OFF, ZONE_ENTER, ZONE_EXIT, REGION_THREAT, FLOURISH }

/**
 * Why a decision landed the way it did. [FIRED] = a notification was actually posted
 * (zone siren or official alert / all-clear); every other value is a "why not".
 */
enum class DebugLogReason {
    FIRED, BELL_MUTED, ALREADY_NOTIFIED, COALESCED, TYPE_OFF, ADVISORY, STALE,
    OUTSIDE_ZONES, TOGGLE_OFF, LEFT
}

/**
 * One debug decision row. Every entry carries the day/night flag, the effective siren
 * override and the vibration level that WOULD have been used, plus whether a notification
 * fired and why not ([reason]). [tier] is the effective zone for zone alerts (what actually
 * rang), null for official alerts / region sweeps. [distanceKm] is from the focus point.
 */
data class DebugLogEntry(
    val atMillis: Long,
    val kind: DebugLogKind,
    val night: Boolean,
    val sirenOverride: Boolean,
    val vibrationLevel: Int?,
    val notified: Boolean,
    val reason: DebugLogReason,
    val threatId: String?,
    val threatType: ThreatType?,
    val tier: ThreatZone?,
    val distanceKm: Double?,
    val locality: String?
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
    val knownZones: Map<String, ThreatZone>,
    val postedId: String?,
    val night: Boolean,
    val sirenOverride: Boolean,
    val fastVibrationLevel: Int,
    val slowVibrationLevel: Int,
    val now: Long
)

/**
 * Persisted ring buffer of alert/threat decisions, written by [AlertService] every tick,
 * read by the Debug log screen. A pure audit trail: entries are append-only snapshots of
 * the service's own evaluation, never inputs back into it, so removing the feature is just
 * deleting the write hooks.
 */
object DebugLog {

    private val engine = ThreatEngine(NEPTUN_TYPES)

    internal const val MAX_ENTRIES = 500

    /** Entries older than this are pruned on load/append (24 hours — a debug trail, not history). */
    internal const val AUTO_CLEAR_AGE_MS = 24L * 60 * 60 * 1000

    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries.asStateFlow()

    /** Threat-id → transition fingerprint; only logged when it changes (no per-tick spam). */
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
        verdicts.clear()
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
        vibrationLevel: Int?,
        notified: Boolean,
        reason: DebugLogReason,
        threatId: String?,
        threatType: ThreatType?,
        locality: String?,
        distanceKm: Double?,
        now: Long
    ) {
        record(
            DebugLogEntry(
                now, kind, night, sirenOverride, vibrationLevel, notified, reason,
                threatId, threatType, null, distanceKm, locality
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
                vibrationLevel = null, notified = reason == DebugLogReason.FIRED,
                reason = reason, threatId = null, threatType = null, tier = null,
                distanceKm = null, locality = detail
            )
        )
    }

    /**
     * Per-tick verdict sweep over every threat in the active region (within the type's
     * reach of the focus point, or in the focus oblast). Logs each threat's lifecycle on
     * transition only: entering a zone (fired / coalesced / bell muted / already notified),
     * being in the region but outside the zones (or excluded: stale / advisory / type off),
     * and leaving the region. Uses the service's own computed maps ([DebugLogContext]) —
     * never re-derives decision formulas.
     */
    fun sweep(ctx: DebugLogContext) {
        val (newEntries, nextVerdicts) = computeSweep(ctx, verdicts)
        verdicts.clear()
        verdicts.putAll(nextVerdicts)
        newEntries.forEach { record(it) }
    }

    internal fun zoneEntry(
        t: NormalizedThreat,
        spatial: ThreatZone,
        focus: LatLng,
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
            // FIRED must win over ALREADY_NOTIFIED: AlertService marks the id known BEFORE the
            // sweep runs, so a just-posted siren arrives here with knownZones already updated.
            ctx.postedId == t.id -> {
                notified = true
                reason = DebugLogReason.FIRED
            }
            ctx.knownZones[t.id] == effective -> {
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
        val fast = isFastType(t.type.toThreatType())
        return DebugLogEntry(
            ctx.now, DebugLogKind.ZONE_ENTER, ctx.night, ctx.sirenOverride,
            if (fast) ctx.fastVibrationLevel else ctx.slowVibrationLevel,
            notified, reason, t.id, t.type.toThreatType(), tier, distKm,
            t.locality ?: t.district ?: t.region
        )
    }

    internal fun regionEntry(t: NormalizedThreat, distKm: Double, ctx: DebugLogContext): DebugLogEntry {
        val reason = when {
            engine.isStale(t, engine.propsFor(t.type), ctx.now) -> DebugLogReason.STALE
            t.advisory -> DebugLogReason.ADVISORY
            t.type.toThreatType() !in ctx.enabledTypes -> DebugLogReason.TYPE_OFF
            else -> DebugLogReason.OUTSIDE_ZONES
        }
        val fast = isFastType(t.type.toThreatType())
        return DebugLogEntry(
            ctx.now, DebugLogKind.REGION_THREAT, ctx.night, ctx.sirenOverride,
            if (fast) ctx.fastVibrationLevel else ctx.slowVibrationLevel,
            false, reason, t.id, t.type.toThreatType(), null, distKm,
            t.locality ?: t.district ?: t.region
        )
    }

    /**
     * Two different reasons can represent the same audible steady state — FIRED and
     * ALREADY_NOTIFIED both mean "siren up for this tier" — so they share a fingerprint
     * and don't spam a duplicate row the tick after they fire.
     */
    internal fun fingerprintOf(entry: DebugLogEntry): String {
        val state = when (entry.reason) {
            DebugLogReason.FIRED, DebugLogReason.ALREADY_NOTIFIED -> "SIREN"
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
        val distKm = distanceFlat(focus.lat, focus.lon, t.lat, t.lon) / 1000.0
        if (distKm > (NEPTUN_TYPES[t.type]?.reachKm ?: 1500.0) &&
            !inOblast(t.region, t.district, t.locality, ctx.token)
        ) continue
        regionIds.add(t.id)

        val entry = ctx.zoneThreats[t.id]?.let { DebugLog.zoneEntry(t, it, focus, distKm, ctx) }
            ?: DebugLog.regionEntry(t, distKm, ctx)
        val fingerprint = DebugLog.fingerprintOf(entry)
        if (nextVerdicts[t.id] == fingerprint) continue
        nextVerdicts[t.id] = fingerprint
        newEntries.add(entry)
    }
    // Threats we were tracking that left the region entirely: log an exit, drop the marker.
    nextVerdicts.keys.filterNot { it in regionIds }.toList().forEach { id ->
        val t = ctx.threats[id]
        newEntries.add(
            DebugLogEntry(
                ctx.now, DebugLogKind.ZONE_EXIT, ctx.night, ctx.sirenOverride, null,
                false, DebugLogReason.LEFT, id, t?.type?.toThreatType(), null, null, t?.locality
            )
        )
        nextVerdicts.remove(id)
    }
    return newEntries to nextVerdicts
}

/**
 * Serialized form of the log — pipe-delimited lines, one per event:
 * "at|kind|night|siren|vibr|notified|reason|threatId|type|tier|distance|locality".
 * Blank fields mean null. Pure, so persistence is unit-testable without DataStore.
 */
internal fun serializeDebugLog(entries: List<DebugLogEntry>): String =
    entries.joinToString("\n") { entry ->
        listOf(
            entry.atMillis,
            entry.kind.name,
            entry.night,
            entry.sirenOverride,
            entry.vibrationLevel ?: "",
            entry.notified,
            entry.reason.name,
            entry.threatId ?: "",
            entry.threatType?.name ?: "",
            entry.tier?.name ?: "",
            entry.distanceKm?.toString() ?: "",
            entry.locality ?: ""
        ).joinToString("|")
    }

/** Reverse of [serializeDebugLog]; skips malformed lines and caps the ring buffer. */
internal fun parseDebugLog(raw: String, maxEntries: Int = DebugLog.MAX_ENTRIES): List<DebugLogEntry> =
    raw.split('\n').mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 12) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = DebugLogKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        val night = parts[2].toBooleanStrictOrNull() ?: return@mapNotNull null
        val siren = parts[3].toBooleanStrictOrNull() ?: return@mapNotNull null
        val vibr = parts[4].toIntOrNull()
        val notified = parts[5].toBooleanStrictOrNull() ?: return@mapNotNull null
        val reason = DebugLogReason.entries.firstOrNull { it.name == parts[6] } ?: return@mapNotNull null
        val threatId = parts[7].takeIf { it.isNotEmpty() }
        val type = ThreatType.entries.firstOrNull { it.name == parts[8] }
        val tier = ThreatZone.entries.firstOrNull { it.name == parts[9] }
        val dist = parts[10].toDoubleOrNull()
        val locality = parts[11].takeIf { it.isNotEmpty() }
        DebugLogEntry(at, kind, night, siren, vibr, notified, reason, threatId, type, tier, dist, locality)
    }.takeLast(maxEntries)