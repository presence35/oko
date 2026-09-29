package com.odesaplay.oko

/**
 * Why the map's notice pill says alert bells are silent — resolved in one pure place so
 * both notice sites (the controls column and the landscape overlay) agree, and so the
 * label/countdown never re-derives policy in the UI.
 *
 * Priority: system notifications disabled (nothing can show at all) → an explicit mute →
 * all zone bells off. Null means the bells are on. The tap affordance for each reason is
 * [SilentReason.action] — see [SilentNoticeAction].
 */
sealed interface SilentReason {
    data object NotificationsDisabled : SilentReason
    data object ZonesOff : SilentReason
    data object MutedForRaid : SilentReason
    /** Timed mute; [deadlineMs] feeds the countdown label. */
    data class MutedFor(val deadlineMs: Long) : SilentReason

    /**
     * The affordance a tap on the notice pill must offer. Owned here so a reason can never be
     * paired with the wrong destination (the zones panel cannot restore a system-wide
     * notification block) and so the mapping is exhaustive at compile time.
     */
    val action: SilentNoticeAction get() = when (this) {
        NotificationsDisabled -> SilentNoticeAction.OpenNotificationSettings
        ZonesOff -> SilentNoticeAction.OpenZones
        MutedForRaid, is MutedFor -> SilentNoticeAction.Unmute
    }

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

/** Where tapping the silent-bells notice sends the user. */
enum class SilentNoticeAction {
    /** System notifications are off — only the OS page can restore them. */
    OpenNotificationSettings,

    /** Every zone bell is off — the in-app zones panel owns those toggles. */
    OpenZones,

    /** A raid mute is live — the tap itself clears it. */
    Unmute
}
