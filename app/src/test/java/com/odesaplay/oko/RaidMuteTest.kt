package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaidMuteTest {

    private val now = 1_000L

    @Test
    fun `none is never silent`() {
        assertFalse(RaidMute.None.silent(now))
    }

    @Test
    fun `until clear is silent until cleared`() {
        val m = RaidMute.None.untilClear()
        assertEquals(RaidMute.UntilClear, m)
        assertTrue(m.silent(now))
        assertEquals(RaidMute.None, m.cleared())
        assertFalse(m.cleared().silent(now))
    }

    @Test
    fun `timed mute lapses on its own`() {
        val m = RaidMute.None.forDuration(now, durationMs = 10_000L)
        assertEquals(RaidMute.Until(11_000L), m)
        assertTrue(m.silent(10_999L))
        assertFalse(m.silent(11_000L))
    }

    @Test
    fun `a short timed mute never weakens until clear`() {
        val m = RaidMute.UntilClear.forDuration(now, durationMs = 1L)
        assertEquals(RaidMute.UntilClear, m)
        assertTrue(m.silent(now + 999_999L))
    }

    @Test
    fun `a spent deadline lapses to none instead of lingering as a dead mute`() {
        // The pill used to keep counting down to a deadline that had passed, and claim a mute
        // that no longer muted anything.
        assertEquals(RaidMute.None, RaidMute.Until(10_000L).lapsed(10_000L))
        assertEquals(RaidMute.Until(10_000L), RaidMute.Until(10_000L).lapsed(9_999L))
        assertEquals(RaidMute.UntilClear, RaidMute.UntilClear.lapsed(Long.MAX_VALUE))
    }

    @Test
    fun `a timed mute survives a process restart, until clear does not`() {
        // The report: a 10-minute mute was gone after the service was recycled.
        assertEquals("11000", RaidMute.Until(11_000L).encode())
        assertEquals(RaidMute.Until(11_000L), RaidMute.decode("11000"))
        assertEquals(RaidMute.None, RaidMute.decode(null))
        assertEquals(RaidMute.None, RaidMute.decode(""))
        assertEquals(RaidMute.None, RaidMute.decode("nonsense"))
        // UntilClear has no deadline: resurrecting one after the raid already ended (while we
        // were dead, so no all-clear could fire) would leave the app permanently silent.
        assertEquals(null, RaidMute.UntilClear.encode())
    }
}
