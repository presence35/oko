package com.odesaplay.oko

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the process woke or came back: our own 15-min alarm, the 15-min worker, a reboot. */
enum class WakeKind { ALARM, WORKER, BOOT }

data class WakeEvent(
    val atMillis: Long,
    val kind: WakeKind,
    /**
     * When the caller asked to be woken, when the caller knows it. `atMillis - triggerAtMs` is the
     * lateness the platform imposed -- the figure that turns "a notification said 18 min while the
     * threshold was 15" into a measurement instead of a theory.
     */
    val triggerAtMs: Long? = null,
    /** How the wakeup was scheduled: `exact` when the platform took `setExactAndAllowWhileIdle`,
     *  `inexact` when it fell back. Recorded per fire so a window in `dumpsys alarm` never has to
     *  be guessed at from the outside. */
    val path: String? = null
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

    fun record(
        kind: WakeKind,
        now: Long = System.currentTimeMillis(),
        triggerAtMs: Long? = null,
        path: String? = null
    ) {
        _entries.value = append(_entries.value, WakeEvent(now, kind, triggerAtMs, path), MAX_ENTRIES)
    }

    internal fun append(events: List<WakeEvent>, event: WakeEvent, max: Int): List<WakeEvent> =
        (events + event).takeLast(max)
}
