package com.presaince.oko

import com.presaince.oko.engine.ThreatZone

/**
 * Frequency policy for zone notifications ("how often", not "whether").
 *
 * Capability ("can it ever sound" — armed bells, official toggles, per-type enables)
 * stays in user prefs and [AlertService.alertTier]; this plugin owns only frequency.
 * The service is a dumb executor: feed per-tick facts in, execute the verdicts out.
 * All judgment — episodes, floor, presets, digest — lives here, pure and tested.
 */
enum class ZonePolicy { EVERY_CHANGE, ONCE_PER_THREAT, ONCE_PER_TYPE, DIGEST }

enum class DigestWindow { MIN_2, MIN_10, MIN_60, EPISODE }

enum class DigestScope { PER_TYPE, ANY }

/** Frequency knobs. Defaults: Once per threat, digest 10/episode/any-type (non-overwhelming). */
data class NotifyPrefs(
    val preset: ZonePolicy = ZonePolicy.ONCE_PER_THREAT,
    val digestMax: Int = 10,
    val digestWindow: DigestWindow = DigestWindow.EPISODE,
    val digestPerType: Boolean = false
) {
    companion object {
        /**
         * The single mapping from raw user prefs to plugin semantics. The service
         * calls this and passes the result blindly: master toggle off means every
         * entry and escalation sounds, knobs preserved. Toggle meaning lives here,
         * tested — never an `if` in the service (boundary rule).
         */
        fun from(
            enabled: Boolean,
            preset: ZonePolicy,
            max: Int,
            window: DigestWindow,
            perType: Boolean
        ) = NotifyPrefs(if (enabled) preset else ZonePolicy.EVERY_CHANGE, max, window, perType)
    }
}

enum class VerdictKind { SOUND, SILENT, SUPPRESS }

/** Why a would-be sound was swallowed. Mirrored in [DebugLogReason] for log rows. */
enum class PolicyReason { ONCE_PER_THREAT, ONCE_PER_TYPE, RATE_LIMITED }

/** One threat's per-tick facts. [live] = present, fresh, active, not area-only. */
data class PluginInput(
    val id: String,
    /** Engine tier (banded); null = outside the zones. */
    val tier: ThreatZone?,
    /** Armed mapping; null = muted (bell off) or outside. */
    val alertTier: ThreatZone?,
    val type: ThreatType,
    val live: Boolean,
    /**
     * User just shot this id (grace window): the track is briefly gone, but the episode
     * must survive untouched — a same-id respawn is the same kill, never a new onset.
     */
    val shotGrace: Boolean = false
)

data class PluginVerdict(val kind: VerdictKind, val reason: PolicyReason? = null)

/**
 * Zone-notification frequency. Episodes replace wall-clock holds: an episode opens on
 * first zone sighting and closes only when the track dies (stale / resolved / gone) —
 * flicker ticks and margin crossings never close it, so they can never re-sound.
 * The engine's spatial band ([ZONE_HYSTERESIS_MARGIN]) makes "outside" meaningful.
 *
 * Policy owns every verdict, including red. Escalation to INNER is a gated
 * opportunity: ONCE_PER_THREAT still sounds on first red (per-threat escalation),
 * ONCE_PER_TYPE and DIGEST respect their gates.
 */
class NotifyPlugin {

    private data class Episode(
        var lastEffective: ThreatZone?,
        var present: Boolean,
        var muted: Boolean,
        var type: ThreatType,
        var sounded: Boolean,
        var soundedInner: Boolean
    )

    private val episodes = mutableMapOf<String, Episode>()
    private val buckets = mutableMapOf<String, ArrayDeque<Long>>()

    /** Preset switch = fresh start. Digest tweaks should use [clearBuckets] instead. */
    fun reset() {
        episodes.clear()
        buckets.clear()
    }

    fun clearBuckets() {
        buckets.clear()
    }

    /**
     * Restore open episodes across restarts: ongoing threats must not re-sound.
     * Seeded as already-handled and present, so the first tick reads as steady;
     * anything that genuinely left while dead is detected on the following ticks.
     */
    fun seedKnown(tiers: Map<String, ThreatZone>) {
        episodes.clear()
        tiers.forEach { (id, tier) ->
            episodes[id] = Episode(
                lastEffective = tier, present = true, muted = false,
                type = ThreatType.UNKNOWN, sounded = true,
                soundedInner = tier == ThreatZone.INNER
            )
        }
    }

    /** Open-episode presence snapshot for persistence (id → effective tier). */
    fun snapshot(): Map<String, ThreatZone> =
        episodes.mapNotNull { (id, ep) -> ep.lastEffective?.let { id to it } }.toMap()

    /**
     * One tick: update episodes, return a verdict per alertable threat.
     * Muted (bell off) and out-of-zone threats get no verdict — the sweep logs
     * BELL_MUTED for those exactly as before.
     */
    fun tick(inputs: Collection<PluginInput>, prefs: NotifyPrefs, now: Long): Map<String, PluginVerdict> {
        val byId = inputs.associateBy { it.id }
        // Close dead episodes: track no longer live. Nothing time-based — staleness
        // and removal are facts from the feed, not timers we invented.
        episodes.keys.filterNot { byId[it]?.live == true }.forEach { episodes.remove(it) }
        byId.forEach { (id, inp) -> if (!inp.shotGrace) episodes[id]?.type = inp.type }

        val out = LinkedHashMap<String, PluginVerdict>()
        for (inp in inputs) {
            // Frozen mid-kill: episode untouched, no verdict.
            if (inp.shotGrace) continue
            val effective = inp.alertTier
            if (effective == null) {
                episodes[inp.id]?.let { it.present = inp.tier != null; it.muted = true }
                continue
            }
            val ep = episodes[inp.id]
            if (ep == null) {
                val fresh = Episode(effective, present = true, muted = false, inp.type, sounded = false, soundedInner = false)
                episodes[inp.id] = fresh
                out[inp.id] = decide(fresh, inp, prefs, now)
            } else {
                val wasAway = !ep.present || ep.muted
                val escalation = !wasAway && ep.lastEffective == ThreatZone.OUTER && effective == ThreatZone.INNER
                ep.present = true
                ep.muted = false
                val verdict = when {
                    effective == ThreatZone.INNER && !ep.soundedInner -> decide(ep, inp, prefs, now)
                    escalation -> if (prefs.preset == ZonePolicy.ONCE_PER_THREAT) sound(ep, inp, prefs, now) else decide(ep, inp, prefs, now)
                    wasAway -> decide(ep, inp, prefs, now)
                    ep.lastEffective != effective ->
                        PluginVerdict(VerdictKind.SILENT) // downgrade / lateral: content update only
                    else -> PluginVerdict(VerdictKind.SILENT) // steady
                }
                ep.lastEffective = effective
                out[inp.id] = verdict
            }
        }
        pruneBuckets(prefs, now)
        return out
    }

    private fun decide(ep: Episode, inp: PluginInput, prefs: NotifyPrefs, now: Long): PluginVerdict =
        when (prefs.preset) {
        ZonePolicy.EVERY_CHANGE -> sound(ep, inp, prefs, now)
        ZonePolicy.ONCE_PER_THREAT ->
            if (!ep.sounded || (inp.alertTier == ThreatZone.INNER && !ep.soundedInner)) sound(ep, inp, prefs, now)
            else PluginVerdict(VerdictKind.SUPPRESS, PolicyReason.ONCE_PER_THREAT)
        ZonePolicy.ONCE_PER_TYPE ->
            if (!ep.sounded && episodes.values.none { it !== ep && it.type == inp.type && it.sounded }) {
                sound(ep, inp, prefs, now)
            } else {
                PluginVerdict(VerdictKind.SUPPRESS, PolicyReason.ONCE_PER_TYPE)
            }
        ZonePolicy.DIGEST ->
            if (bucketAllows(inp, prefs, now)) sound(ep, inp, prefs, now)
            else PluginVerdict(VerdictKind.SUPPRESS, PolicyReason.RATE_LIMITED)
    }

    private fun sound(ep: Episode, inp: PluginInput, prefs: NotifyPrefs, now: Long): PluginVerdict {
        ep.sounded = true
        if (inp.alertTier == ThreatZone.INNER) ep.soundedInner = true
        recordBucket(inp, prefs, now)
        return PluginVerdict(VerdictKind.SOUND)
    }

    private fun bucketKey(inp: PluginInput, prefs: NotifyPrefs): String =
        if (prefs.digestPerType) inp.type.name else "*"

    private fun windowMs(prefs: NotifyPrefs): Long = when (prefs.digestWindow) {
        DigestWindow.MIN_2 -> 2 * 60_000L
        DigestWindow.MIN_10 -> 10 * 60_000L
        DigestWindow.MIN_60 -> 60 * 60_000L
        else -> 0L
    }

    private fun bucketAllows(inp: PluginInput, prefs: NotifyPrefs, now: Long): Boolean {
        val q = buckets[bucketKey(inp, prefs)] ?: return true
        if (prefs.digestWindow == DigestWindow.EPISODE) return q.size < prefs.digestMax
        val window = windowMs(prefs)
        return q.count { it > now - window } < prefs.digestMax
    }

    private fun recordBucket(inp: PluginInput, prefs: NotifyPrefs, now: Long) {
        buckets.getOrPut(bucketKey(inp, prefs)) { ArrayDeque() }.addLast(now)
    }

    private fun pruneBuckets(prefs: NotifyPrefs, now: Long) {
        if (prefs.digestWindow == DigestWindow.EPISODE) {
            if (episodes.isEmpty()) {
                buckets.clear()
            } else if (prefs.digestPerType) {
                val liveTypes = episodes.values.map { it.type.name }.toSet()
                buckets.keys.filterNot { it in liveTypes }.forEach { buckets.remove(it) }
            }
            return
        }
        val window = windowMs(prefs)
        buckets.values.forEach { q -> while (q.isNotEmpty() && q.first() <= now - window) q.removeFirst() }
    }

    companion object {
        /**
         * What-if retrospective over the last-24h zone FIRED rows (chronological):
         * how many sounds each preset would have made. Approximate (the log lacks full
         * tick inputs) — shown as estimates in Settings, never as exact claims.
         */
        fun whatIf(fired: List<DebugLogEntry>, prefs: NotifyPrefs): Map<ZonePolicy, Int> {
            val byId = fired.mapNotNull { it.threatId }.toSet()
            val byType = fired.mapNotNull { it.threatType }.toSet()
            val digest = when (prefs.digestWindow) {
                DigestWindow.EPISODE ->
                    if (prefs.digestPerType) {
                        fired.mapNotNull { e -> e.threatId?.let { it to e.threatType } }.toSet().size
                    } else {
                        byId.size
                    }
                else -> {
                    val window = when (prefs.digestWindow) {
                        DigestWindow.MIN_2 -> 2 * 60_000L
                        DigestWindow.MIN_10 -> 10 * 60_000L
                        else -> 60 * 60_000L
                    }
                    val buckets = mutableMapOf<String, ArrayDeque<Long>>()
                    var allowed = 0
                    for (e in fired.sortedBy { it.atMillis }) {
                        val key = if (prefs.digestPerType) e.threatType?.name ?: "*" else "*"
                        val q = buckets.getOrPut(key) { ArrayDeque() }
                        while (q.isNotEmpty() && q.first() <= e.atMillis - window) q.removeFirst()
                        if (q.size < prefs.digestMax) {
                            q.addLast(e.atMillis)
                            allowed++
                        }
                    }
                    allowed
                }
            }
            return mapOf(
                ZonePolicy.EVERY_CHANGE to fired.size,
                ZonePolicy.ONCE_PER_THREAT to byId.size,
                ZonePolicy.ONCE_PER_TYPE to byType.size,
                ZonePolicy.DIGEST to digest
            )
        }
    }
}
