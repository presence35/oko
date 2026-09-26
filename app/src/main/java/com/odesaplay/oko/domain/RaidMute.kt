package com.odesaplay.oko

/**
 * "Mute raid" state: bells off for the current raid without hiding notifications.
 *
 * A sealed state, never scattered booleans — [UntilClear] is strictly stronger than a
 * timed [Until], so a short mute can never weaken a longer one. [cleared] is the only
 * reset, called when the sky goes fully clear; the next raid rings again.
 */
sealed interface RaidMute {
    data object None : RaidMute
    /** Silent until the sky clears. */
    data object UntilClear : RaidMute
    /** Silent until this wall-clock deadline. */
    data class Until(val deadlineMs: Long) : RaidMute

    /** True while the bells should stay silent. Timed mutes lapse on their own. */
    fun silent(now: Long): Boolean = when (this) {
        None -> false
        UntilClear -> true
        is Until -> now < deadlineMs
    }

    /** "Mute raid" — the strongest mute. */
    fun untilClear(): RaidMute = UntilClear

    /** "Mute N min" — a no-op while [UntilClear] already dominates. */
    fun forDuration(now: Long, durationMs: Long): RaidMute =
        if (this is UntilClear) this else Until(now + durationMs)

    fun cleared(): RaidMute = None
}
