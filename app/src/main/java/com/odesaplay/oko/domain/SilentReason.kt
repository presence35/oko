package com.odesaplay.oko

/**
 * Why the map's notice pill says alerts are silent — resolved in one pure place so
 * both notice sites (the controls column and the landscape overlay) agree, and so the
 * label/countdown never re-derives policy in the UI.
 *
 * Priority: system notifications disabled (nothing can show at all) → an explicit mute →
 * all zone bells off. Null means the bells are on. The tap affordance for each reason is
 * [SilentReason.action] — see [SilentNoticeAction].
 */
sealed interface SilentReason {
    data object NotificationsDisabled : SilentReason
    /** Every zone bell is off — [cause] says WHY, because the three causes need three
     *  different words and the same fix does not apply to all of them. */
    data class ZonesOff(val cause: ZonesOffCause) : SilentReason
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
        is ZonesOff -> SilentNoticeAction.OpenZones
        MutedForRaid, is MutedFor -> SilentNoticeAction.Unmute
    }

    companion object {
        fun resolve(
            zonesArmed: Boolean,
            notificationsDisabled: Boolean,
            mute: RaidMute,
            now: Long,
            /** Only consulted when the bells are all off; defaults to the plain cause. */
            zonesOffCause: ZonesOffCause = ZonesOffCause.Manual
        ): SilentReason? {
            // A mute whose time has run out is not a mute: resolving it before anything else
            // keeps a dead deadline from being reported as a live countdown.
            val live = mute.lapsed(now)
            return when {
                notificationsDisabled -> NotificationsDisabled
                !live.silent(now) -> if (zonesArmed) null else ZonesOff(zonesOffCause)
                live is RaidMute.Until -> MutedFor(live.deadlineMs)
                else -> MutedForRaid
            }
        }
    }
}

/**
 * Why every zone bell reads off. The old single "Zone alerts are off" string was honest about
 * the state and useless about the cause: a night window, the "Just let me sleep!" preset and a
 * hand-set pair of switches all produce exactly the same armed flags.
 */
enum class ZonesOffCause {
    /** "Just let me sleep!" owns the night settings and has them all off. */
    SleepPreset,

    /** The night window is active and its bells are off. */
    Night,

    /** Daytime bells switched off by hand. */
    Manual
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
