package com.odesaplay.oko

/**
 * Why the map's notice pill says alert bells are silent — resolved in one pure place so
 * both notice sites (the controls column and the landscape overlay) agree, and so the
 * label/countdown never re-derives policy in the UI.
 *
 * Priority: system notifications disabled (nothing can show at all) → an explicit mute
 * (fresh, actionable — tap unmutes) → all zone bells off (tap opens the zones panel).
 * Null means the bells are on.
 */
sealed interface SilentReason {
    data object NotificationsDisabled : SilentReason
    data object ZonesOff : SilentReason
    data object MutedForRaid : SilentReason
    /** Timed mute; [deadlineMs] feeds the countdown label. */
    data class MutedFor(val deadlineMs: Long) : SilentReason

    val isMute: Boolean get() = this is MutedForRaid || this is MutedFor

    companion object {
        fun resolve(
            zonesArmed: Boolean,
            notificationsDisabled: Boolean,
            mute: RaidMute,
            now: Long
        ): SilentReason? = when {
            notificationsDisabled -> NotificationsDisabled
            !mute.silent(now) -> if (zonesArmed) null else ZonesOff
            mute is RaidMute.Until -> MutedFor(mute.deadlineMs)
            else -> MutedForRaid
        }
    }
}
