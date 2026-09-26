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
}
