package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AviationFlybyTest {

    private val mig = threat(id = "m1", type = ThreatType.AVIATION)

    @Test
    fun `nextShow picks the first unplayed INNER aviation`() {
        val inner = listOf(
            threat(id = "s1", type = ThreatType.SHAHED),
            mig,
            threat(id = "m2", type = ThreatType.AVIATION)
        )
        val show = AviationFlyby.nextShow(inner, emptySet(), true, tick = 1)
        assertNotNull(show)
        assertEquals("m1", show!!.threatId)
    }

    @Test
    fun `nextShow skips already-played ids`() {
        assertNull(AviationFlyby.nextShow(listOf(mig), setOf("m1"), true, 1))
    }

    @Test
    fun `nextShow ignores non-aviation tiers`() {
        val inner = listOf(threat(id = "s1", type = ThreatType.SHAHED))
        assertNull(AviationFlyby.nextShow(inner, emptySet(), true, 1))
    }

    @Test
    fun `nextShow never plays while the map is hidden or app backgrounded`() {
        assertNull(AviationFlyby.nextShow(listOf(mig), emptySet(), false, 1))
    }

    @Test
    fun `course is a fresh random full-circle bearing`() {
        repeat(20) {
            val show = AviationFlyby.nextShow(listOf(mig), emptySet(), true, 1L + it)
            assertNotNull(show)
            assertTrue(show!!.courseDeg in 0.0..360.0)
        }
    }

    @Test
    fun `sprite transform keeps the nose on the flight path`() {
        // Unflipped: plain course − facing.
        assertEquals(30f, AviationFlyby.spriteTransform(30f, 0f).first, 1e-4f)
        assertFalse(AviationFlyby.spriteTransform(30f, 0f).second)
        // Flipped pass: the mirror shifts the art's effective facing (β → 180−β), so the
        // rotation compensates — nose still points along the course.
        val (rotZ, flipped) = AviationFlyby.spriteTransform(0f, 265f)
        assertTrue(flipped)
        assertEquals(0f - (180f - 265f), rotZ, 1e-4f)
    }

    @Test
    fun `sprite transform flips exactly when the pass would invert the jet`() {
        for (course in 0..359) {
            for (facing in intArrayOf(0, 85, 90, 95, 180, 265, 270)) {
                val (_, flipped) = AviationFlyby.spriteTransform(course.toFloat(), facing.toFloat())
                val diff = ((course - facing) % 360f + 360f) % 360f
                assertEquals(diff in 90f..270f, flipped)
            }
        }
    }

    @Test
    fun `direction maps compass bearings to screen vectors`() {
        // North is up (negative y), east right, south down.
        var d = AviationFlyby.direction(0.0)
        assertEquals(0.0, d.first, 1e-9)
        assertEquals(-1.0, d.second, 1e-9)
        d = AviationFlyby.direction(90.0)
        assertEquals(1.0, d.first, 1e-9)
        assertEquals(0.0, d.second, 1e-9)
        d = AviationFlyby.direction(180.0)
        assertEquals(0.0, d.first, 1e-9)
        assertEquals(1.0, d.second, 1e-9)
    }

    @Test
    fun `endpoints enter and exit fully off-screen through the center`() {
        val w = 1000f
        val h = 2000f
        val icon = 100f
        val (entry, exit) = AviationFlyby.endpoints(90.0, w, h, icon)
        // Due east: a horizontal pass through the vertical center, entering half an icon
        // left of the edge and exiting past the right one.
        assertEquals(h / 2f, entry.second, 0.01f)
        assertEquals(h / 2f, exit.second, 0.01f)
        assertEquals(-icon / 2f, entry.first, 0.01f)
        assertEquals(w + icon / 2f, exit.first, 0.01f)

        // For any bearing the path is symmetric about the viewport center.
        val diagonal = AviationFlyby.endpoints(315.0, w, h, icon)
        val cx = (diagonal.first.first + diagonal.second.first) / 2f
        val cy = (diagonal.first.second + diagonal.second.second) / 2f
        assertEquals(w / 2f, cx, 0.01f)
        assertEquals(h / 2f, cy, 0.01f)
    }
}
