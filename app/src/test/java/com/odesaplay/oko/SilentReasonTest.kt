package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilentReasonTest {

    private val now = 100_000L

    private fun resolve(
        zonesArmed: Boolean = true,
        notificationsDisabled: Boolean = false,
        mute: RaidMute = RaidMute.None
    ) = SilentReason.resolve(zonesArmed, notificationsDisabled, mute, now)

    @Test
    fun `armed zones with no mute is silent for no reason`() {
        assertNull(resolve())
    }

    @Test
    fun `all zone bells off is reported when nothing else applies`() {
        assertEquals(SilentReason.ZonesOff, resolve(zonesArmed = false))
    }

    @Test
    fun `system notifications disabled outranks every other reason`() {
        assertEquals(
            SilentReason.NotificationsDisabled,
            resolve(zonesArmed = false, notificationsDisabled = true, mute = RaidMute.UntilClear)
        )
    }

    @Test
    fun `mute outranks zone bells off`() {
        assertEquals(SilentReason.MutedForRaid, resolve(zonesArmed = false, mute = RaidMute.UntilClear))
    }

    @Test
    fun `a live timed mute reports its deadline`() {
        assertEquals(
            SilentReason.MutedFor(now + 60_000L),
            resolve(mute = RaidMute.Until(now + 60_000L))
        )
    }

    @Test
    fun `a lapsed timed mute falls back to the underlying reason`() {
        assertNull(resolve(zonesArmed = true, mute = RaidMute.Until(now - 1L)))
        assertEquals(SilentReason.ZonesOff, resolve(zonesArmed = false, mute = RaidMute.Until(now - 1L)))
    }

    @Test
    fun `isMute is true only for the mute reasons`() {
        assertEquals(false, SilentReason.ZonesOff.isMute)
        assertEquals(false, SilentReason.NotificationsDisabled.isMute)
        assertEquals(true, SilentReason.MutedForRaid.isMute)
        assertEquals(true, SilentReason.MutedFor(1L).isMute)
    }
}
