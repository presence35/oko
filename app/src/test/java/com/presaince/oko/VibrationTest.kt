package com.presaince.oko

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VibrationTest {

    @Test
    fun `level zero disables vibration`() {
        assertArrayEquals(longArrayOf(0), vibrationPattern(0))
    }

    @Test
    fun `default level three is a strong double pulse`() {
        val p = vibrationPattern(3)
        assertTrue(p.size == 4)
        assertEquals(400L, p[1])
        assertEquals(400L, p[3])
    }

    @Test
    fun `every level yields a distinct pattern`() {
        assertEquals(5, (0..4).map { vibrationPattern(it).toList() }.distinct().size)
    }

    @Test
    fun `out of range falls back to the default`() {
        assertArrayEquals(vibrationPattern(3), vibrationPattern(99))
    }

    @Test
    fun `vibration patterns have valid positive timings`() {
        for (lvl in 1..4) {
            val pattern = vibrationPattern(lvl)
            assertTrue("Pattern for level $lvl must not be empty", pattern.isNotEmpty())
            assertTrue("Initial delay must be non-negative", pattern[0] >= 0)
            for (i in 1 until pattern.size) {
                assertTrue("Timing step $i in level $lvl must be strictly positive", pattern[i] > 0)
            }
        }
    }
}

