package com.odesaplay.oko

import android.app.NotificationManager
import android.media.AudioManager

/**
 * One of our notification channels as the OS currently has it.
 *
 * [muted] is deliberately separate from the app-level switch: a user can silence exactly one
 * channel and leave the rest on, which is a silence no app-side toggle can explain or detect.
 */
data class NotifChannel(
    val id: String,
    val importance: Int,
    val hasSound: Boolean
) {
    val muted: Boolean get() = importance == NotificationManager.IMPORTANCE_NONE
}

/**
 * What the OS will actually let us deliver — the facts a `prefs` dump cannot carry, and whose
 * absence made "I hear a beep with nothing in the shade" undiagnosable from a log bundle.
 *
 * One reader (`AlertNotificationManager.notificationState`), two consumers: the log bundle
 * (diagnosis) and [settingsConflicts] (telling the user their intent is unachievable). Pure data,
 * so both are testable without a device.
 */
data class NotificationState(
    val enabled: Boolean,
    val channels: List<NotifChannel>,
    val policyAccess: Boolean,
    val interruptionFilter: Int,
    val ringerMode: Int,
    val suspended: Boolean
) {
    /**
     * Do Not Disturb is suppressing something. Gated on [policyAccess] because without it the OS
     * reports `INTERRUPTION_FILTER_UNKNOWN` rather than the truth — and reading "unknown" as
     * "restricted" would warn users about DND that is not on.
     */
    val dndActive: Boolean
        get() = policyAccess &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    /**
     * The ringer will swallow a channel sound. Vibrate counts: vibrate does not play the sound,
     * which is exactly the complaint this is here to separate from "the alert never fired".
     */
    val ringerSilent: Boolean
        get() = ringerMode != AudioManager.RINGER_MODE_NORMAL

    val mutedChannels: List<NotifChannel> get() = channels.filter { it.muted }
}
