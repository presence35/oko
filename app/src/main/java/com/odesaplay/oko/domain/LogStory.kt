package com.odesaplay.oko

import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.resolveOblastId

/**
 * A raid — one continuous span of activity, told as a story rather than a row dump.
 *
 * Sessions are the unit of meaning in the Logs screen: each one answers "what happened, was I
 * told, and if not, why" in a single card. The official alert is the spine ([official]) — when
 * the source announces an alarm, its boundaries define the session; other events group by a time
 * gap around it.
 *
 * Pure and unit-tested: it reads the already-recorded audit rows and never re-derives a decision.
 */
data class LogSession(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val official: AlertLevel?,
    val entries: List<DebugLogEntry>
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val size: Int get() = entries.size

    /** True when at least one event inside actually reached the shade. */
    val told: Boolean get() = entries.any { notifyOutcome(it) == NotifyOutcome.RANG }

    /** Plain-language reason the session stayed quiet, or null when it rang. */
    fun silenceReason(s: Strings.StringSet): String? {
        if (told) return null
        val reason = entries.asSequence()
            .filter { notifyOutcome(it) != NotifyOutcome.RANG }
            .groupingBy { it.reason }.eachCount()
            .maxByOrNull { it.value }?.key
            ?: return null
        return reason.label(s)
    }

    /** The place this session is about — first entry that resolves to somewhere. */
    val place: String? get() = entries.firstOrNull { !it.locality.isNullOrBlank() }?.locality
}

/** Grouping policy: how far apart two events may be and still be the same raid. */
const val SESSION_GAP_MS = 20L * 60 * 1000

/**
 * Cluster decision rows into raid sessions, newest first.
 *
 * The official alert is authoritative: an [DebugLogKind.OFFICIAL_ON]/[OFFICIAL_OFF] pair always
 * opens/closes its own session, so a raid reads as one story even when its cause drones arrive
 * earlier and its all-clear lands later. Everything else groups by a [SESSION_GAP_MS] gap.
 * An event belongs to the session it starts; nothing is dropped.
 */
fun buildSessions(entries: List<DebugLogEntry>): List<LogSession> {
    if (entries.isEmpty()) return emptyList()
    val sorted = entries.sortedBy { it.atMillis }
    val sessions = mutableListOf<MutableList<DebugLogEntry>>()

    for (e in sorted) {
        val current = sessions.lastOrNull()
        val previous = current?.lastOrNull()
        // An ON starts a story; a new ON can never append to an existing one. An OFF closes
        // the story it belongs to, so anything after it must start fresh.
        val previousClosed = previous?.kind == DebugLogKind.OFFICIAL_OFF
        val continues = previous != null &&
            e.atMillis - previous.atMillis <= SESSION_GAP_MS &&
            e.kind != DebugLogKind.OFFICIAL_ON &&
            !previousClosed
        if (continues) current!!.add(e) else sessions.add(mutableListOf(e))
    }

    return sessions.map { group ->
        val official = group.firstNotNullOfOrNull { it.level } ?: officialFromKind(group)
        LogSession(
            id = "s-${group.first().atMillis}-${group.last().atMillis}",
            startMs = group.first().atMillis,
            endMs = group.last().atMillis,
            official = official,
            entries = group.sortedByDescending { it.atMillis }
        )
    }.sortedByDescending { it.startMs }
}

/** A session already anchored by an alarm is closed — the next event starts a fresh story. */
private fun officialFromKind(group: List<DebugLogEntry>): AlertLevel? = when {
    group.any { it.kind == DebugLogKind.OFFICIAL_OFF } -> AlertLevel.NONE
    else -> null
}

/**
 * Session-level place id: the oblast the session is about, from its most populated entry place.
 * Used by the Mine scope so a whole raid is in or out — never a mix.
 */
fun LogSession.oblastId(): String? =
    entries.mapNotNull { it.scopeOblastId ?: resolveOblastId(it.locality) }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
