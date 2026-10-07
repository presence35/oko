package com.odesaplay.oko

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the process woke or came back: our own 15-min alarm, the 15-min worker, a reboot. */
enum class WakeKind { ALARM, WORKER, BOOT }

data class WakeEvent(
    val atMillis: Long,
    val kind: WakeKind
)

/**
 * Every resurrection-path fire, in one place. Screen on/off and plug/unplug are already in the
 * power rows (derive transitions in analysis); what is NOT otherwise visible is whether OUR
 * alarms correlate with wakes — which is the question this log exists to answer.
 *
 * In-memory only, like the decision audit: a boot is recorded after the restart into the fresh
 * ring, so nothing is lost that matters. Few rows per day by construction.
 */
object WakeEventLog {

    internal const val MAX_ENTRIES = 32

    private val _entries = MutableStateFlow<List<WakeEvent>>(emptyList())
    val entries: StateFlow<List<WakeEvent>> = _entries.asStateFlow()

    fun record(kind: WakeKind, now: Long = System.currentTimeMillis()) {
        _entries.value = append(_entries.value, WakeEvent(now, kind), MAX_ENTRIES)
    }

    internal fun append(events: List<WakeEvent>, event: WakeEvent, max: Int): List<WakeEvent> =
        (events + event).takeLast(max)
}
