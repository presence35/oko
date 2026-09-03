package ua.ukrainedrones

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
}
