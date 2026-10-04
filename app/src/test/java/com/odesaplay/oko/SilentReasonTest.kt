package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilentReasonTest {

    private val now = 100_000L

    private fun resolve(
        zonesArmed: Boolean = true,
        notificationsDisabled: Boolean = false,
        mute: RaidMute = RaidMute.None,
        cause: ZonesOffCause = ZonesOffCause.Manual
    ) = SilentReason.resolve(zonesArmed, notificationsDisabled, mute, now, cause)

    @Test
    fun `armed zones with no mute is silent for no reason`() {
        assertNull(resolve())
    }

    @Test
    fun `all zone bells off is reported when nothing else applies`() {
        assertEquals(
            SilentReason.ZonesOff(ZonesOffCause.Manual),
            resolve(zonesArmed = false)
        )
    }

    @Test
    fun `the sleep preset and the night window read differently from a hand switch`() {
        // One string for three causes is why the map could only ever say "Zone alerts are off".
        assertEquals(
            SilentReason.ZonesOff(ZonesOffCause.SleepPreset),
            resolve(zonesArmed = false, cause = ZonesOffCause.SleepPreset)
        )
        assertEquals(
            SilentReason.ZonesOff(ZonesOffCause.Night),
            resolve(zonesArmed = false, cause = ZonesOffCause.Night)
        )
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
        assertEquals(
            SilentReason.ZonesOff(ZonesOffCause.Manual),
            resolve(zonesArmed = false, mute = RaidMute.Until(now - 1L))
        )
    }

    @Test
    fun `each reason maps to the affordance that can actually clear it`() {
        assertEquals(
            SilentNoticeAction.OpenNotificationSettings,
            SilentReason.NotificationsDisabled.action
        )
        assertEquals(
            SilentNoticeAction.OpenZones,
            SilentReason.ZonesOff(ZonesOffCause.Manual).action
        )
        assertEquals(SilentNoticeAction.Unmute, SilentReason.MutedForRaid.action)
        assertEquals(SilentNoticeAction.Unmute, SilentReason.MutedFor(1L).action)
    }

    @Test
    fun `the notification reason never points at the in-app zones panel`() {
        // The zones panel's bells cannot lift a system-wide block — routing there was the bug.
        assertNotEquals(
            SilentNoticeAction.OpenZones,
            resolve(zonesArmed = false, notificationsDisabled = true)!!.action
        )
    }
}
