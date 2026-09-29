package com.odesaplay.oko

import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.resolveOblastId

/**
 * A raid — one continuous span of activity, told as a story rather than a row dump.
 *
 * Sessions are the unit of meaning in the Logs screen: each one answers "what happened, was I
 * told, and if not, why" in a single card. A session is anchored by the official alert — it
 * runs from the ON to the OFF. Threats the source reports just before the alarm (a drone ahead
 * of the siren) are adopted into the session so the story stays whole.
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
 * A track reported this long before an alarm is adopted into it — NEPTUN sometimes carries a
 * drone minutes ahead of the siren, and that drone belongs to the raid it precedes.
 */
const val SESSION_ADOPT_LEAD_MS = 2L * 60 * 1000

/**
 * Cluster decision rows into raid sessions, newest first.
 *
 * The official alert is the spine: a session runs ON → OFF. Events before the ON within
 * [SESSION_ADOPT_LEAD_MS] are adopted into it (the siren-catching-up case); everything else
 * groups by a [SESSION_GAP_MS] gap. Nothing is ever dropped.
 */
fun buildSessions(entries: List<DebugLogEntry>): List<LogSession> {
    if (entries.isEmpty()) return emptyList()
    val sorted = entries.sortedBy { it.atMillis }

    // Walk once, cutting a new session at every ON and after every OFF.
    val groups = mutableListOf<MutableList<DebugLogEntry>>()
    for (e in sorted) {
        val current = groups.lastOrNull()
        val previousClosed = current?.lastOrNull()?.kind == DebugLogKind.OFFICIAL_OFF
        val continues = current != null && !previousClosed &&
            e.kind != DebugLogKind.OFFICIAL_ON &&
            e.atMillis - current.last().atMillis <= SESSION_GAP_MS
        if (continues) current!!.add(e) else groups.add(mutableListOf(e))
    }

    // Adopt each alarm's immediate lead-in: a session that starts with an ON swallows the
    // preceding session's tail when it sits within the lead window AND that tail is ordinary
    // activity (never another alarm's ON/OFF).
    val byAlarm = groups.toMutableList()
    val merged = mutableListOf<MutableList<DebugLogEntry>>()
    for (g in byAlarm) {
        val startsWithAlarm = g.first().kind == DebugLogKind.OFFICIAL_ON
        val prev = merged.lastOrNull()
        if (startsWithAlarm && prev != null &&
            !prev.any { it.kind == DebugLogKind.OFFICIAL_ON || it.kind == DebugLogKind.OFFICIAL_OFF } &&
            g.first().atMillis - prev.last().atMillis <= SESSION_ADOPT_LEAD_MS
        ) {
            prev.addAll(g)
        } else {
            merged.add(g)
        }
    }

    return merged.map { group ->
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

private fun officialFromKind(group: List<DebugLogEntry>): AlertLevel? = when {
    group.any { it.kind == DebugLogKind.OFFICIAL_OFF } -> AlertLevel.NONE
    else -> null
}

/**
 * Session-level place id: the oblast the session is about, from its most populated entry place.
 */
fun LogSession.oblastId(): String? =
    entries.mapNotNull { it.scopeOblastId ?: resolveOblastId(it.locality) }
        .groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key

/** True when ANY event in the session was evaluated against the user — the honest "about me". */
fun LogSession.aboutMe(): Boolean = entries.any { it.aboutMe }
