package com.odesaplay.oko

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OS-state facts behind "I hear a beep with nothing in the shade". Pure: every case is built
 * from values, so none of it needs a device.
 */
class NotificationStateTest {

    private fun state(
        enabled: Boolean = true,
        channels: List<NotifChannel> = emptyList(),
        policyAccess: Boolean = false,
        interruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
        suspended: Boolean = false
    ) = NotificationState(enabled, channels, policyAccess, interruptionFilter, ringerMode, suspended)

    private fun channel(
        id: String = "alerts_outer",
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        hasSound: Boolean = true
    ) = NotifChannel(id, importance, hasSound)

    @Test
    fun `a channel at importance none is muted, one at any level above is not`() {
        assertTrue(channel(importance = NotificationManager.IMPORTANCE_NONE).muted)
        assertFalse(channel(importance = NotificationManager.IMPORTANCE_LOW).muted)
        assertFalse(channel(importance = NotificationManager.IMPORTANCE_HIGH).muted)
    }

    /**
     * A user can silence exactly one channel and leave the app switch on, which is the silence no
     * app-side toggle can explain — so this is the list the warning is built from.
     */
    @Test
    fun `mutedChannels names only the silenced channels, with the app switch on`() {
        val s = state(
            enabled = true,
            channels = listOf(
                channel("alerts_inner"),
                channel("alerts_outer", importance = NotificationManager.IMPORTANCE_NONE),
                channel("offline_critical", importance = NotificationManager.IMPORTANCE_NONE)
            )
        )
        assertEquals(listOf("alerts_outer", "offline_critical"), s.mutedChannels.map { it.id })
    }

    /**
     * The trap this guards: without policy access the OS reports UNKNOWN, and reading "unknown" as
     * "restricted" would warn every user who never granted the permission.
     */
    @Test
    fun `dnd is active only with access and a restricting filter`() {
        assertFalse(
            "no policy access means we cannot see DND, not that DND is on",
            state(policyAccess = false, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE).dndActive
        )
        assertFalse(
            "UNKNOWN is not a restriction",
            state(policyAccess = true, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN).dndActive
        )
        assertFalse(
            state(policyAccess = true, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL).dndActive
        )
        assertTrue(
            state(policyAccess = true, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY).dndActive
        )
        assertTrue(
            state(policyAccess = true, interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE).dndActive
        )
    }

    /** Vibrate is not normal: it does not play the channel sound, which is the whole distinction. */
    @Test
    fun `vibrate counts as the ringer swallowing the sound`() {
        assertFalse(state(ringerMode = AudioManager.RINGER_MODE_NORMAL).ringerSilent)
        assertTrue(state(ringerMode = AudioManager.RINGER_MODE_VIBRATE).ringerSilent)
        assertTrue(state(ringerMode = AudioManager.RINGER_MODE_SILENT).ringerSilent)
    }
}
