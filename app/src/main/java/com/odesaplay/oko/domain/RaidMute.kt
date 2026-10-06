package com.odesaplay.oko

/**
 * "Mute raid" state: the alerts for the current raid are suppressed entirely — no sound,
 * no vibration and no notification. The user muted it, so the user owns the consequence.
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

    /** True while the alerts should stay suppressed. Timed mutes lapse on their own. */
    fun silent(now: Long): Boolean = when (this) {
        None -> false
        UntilClear -> true
        is Until -> now < deadlineMs
    }

    /**
     * This state with its time already spent dropped. A lapsed [Until] becomes [None] rather
     * than lingering as a dead object, so the UI can never keep counting down to a deadline
     * that has passed and keep claiming a mute that no longer mutes.
     */
    fun lapsed(now: Long): RaidMute = if (this is Until && now >= deadlineMs) None else this

    /** "Mute raid" — the strongest mute. */
    fun untilClear(): RaidMute = UntilClear

    /** "Mute N min" — a no-op while [UntilClear] already dominates, and never weakens a longer
     *  timed mute already in force. */
    fun forDuration(now: Long, durationMs: Long): RaidMute = when (this) {
        is UntilClear -> this
        is Until -> Until(maxOf(deadlineMs, now + durationMs))
        None -> Until(now + durationMs)
    }

    fun cleared(): RaidMute = None

    /**
     * Persisted form for the service store. Only a *timed* mute survives a process death:
     * [UntilClear] has no deadline to restore, and resurrecting one after the raid already
     * ended (while we were dead, so no all-clear could ever fire) would leave the app
     * permanently silent — strictly worse than losing the mute. A restarted raid therefore
     * rings again, which is the honest reading of a mute the OS recycled.
     */
    fun encode(): String? = when (this) {
        None, UntilClear -> null
        is Until -> deadlineMs.toString()
    }

    companion object {
        /** Read a persisted deadline. Anything unparseable restores as [None] — never a mute
         *  the user did not ask for. */
        fun decode(raw: String?): RaidMute =
            raw?.trim()?.toLongOrNull()?.let { Until(it) } ?: None
    }
}
