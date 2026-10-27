package com.odesaplay.oko

/**
 * A configuration that cannot deliver what the user asked for.
 *
 * The rule for what belongs here: warn only when the user's **stated intent is unachievable**,
 * never about a legitimate choice. "All day alerts off" is a config, not a conflict — and a user
 * who has already ignored two notices will ignore a third that is merely opinionated.
 *
 * Deliberately NOT folded into [SilentReason], which looks close but answers a different question.
 * That one is the map's "why is the bell quiet" notice: a single reason in a priority chain,
 * consumed by an exhaustive `when` that renders map copy — so adding cases there would force the
 * map to carry strings for reasons it can never show, and would change the map pill as a side
 * effect. The `NOTIFICATIONS_OFF` / `NotificationsDisabled` overlap is real and intentional: the
 * same fact answers both questions, and each surface words it for its own audience.
 *
 * Pure, over ([UserPreferences], [NotificationState]) only, so every case is testable without a
 * device and the UI merely renders the verdict rather than re-deriving it.
 */
enum class SettingsConflict {
    /** Nothing can reach the user at all: the OS is not letting us post. */
    NOTIFICATIONS_OFF,

    /** The app is allowed to notify, but a channel that carries an alert is silenced. */
    MUTED_CHANNEL,

    /** The user asked to be woken and Do Not Disturb will silence it. */
    DND_BLOCKS_LOUD
}

/** Did the user ask to be woken loudly? Any of the switches, day or night; an unset night override
 *  follows the day value, which the first term already covers. */
val UserPreferences.wantsLoudAlerts: Boolean
    get() = sirenOverride || nightZoneSirenOverride == true || nightOfficialSirenOverride == true

/**
 * Ordered by severity. When the OS blocks us outright the finer warnings are noise, so that case
 * is returned alone — it is the only one that explains everything else.
 */
fun settingsConflicts(p: UserPreferences, n: NotificationState): List<SettingsConflict> {
    if (!n.enabled) return listOf(SettingsConflict.NOTIFICATIONS_OFF)
    return buildList {
        if (n.mutedChannels.isNotEmpty()) add(SettingsConflict.MUTED_CHANNEL)
        if (n.dndActive && p.wantsLoudAlerts) add(SettingsConflict.DND_BLOCKS_LOUD)
    }
}
