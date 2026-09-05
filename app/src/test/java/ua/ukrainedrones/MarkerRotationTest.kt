package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerRotationTest {

    /** osmdroid renders `marker.rotation` negated (`Marker.draw` passes `-mBearing` into
     *  `Canvas.rotate`, where a positive angle is clockwise on screen). So the compass bearing a
     *  nose-up icon appears to point at is `base - rotation`. The marker rotation must satisfy
     *  `base - rotation == course` for the icon to face its travel direction. */
    private fun displayedFacing(rotation: Float, base: Float): Float {
        val raw = base - rotation
        return (raw % 360f + 360f) % 360f
    }

    private fun assertFacesCourse(course: Float, base: Float) {
        val rotation = threatMarkerRotation(course, base)
        assertEquals(course, displayedFacing(rotation, base), 0.001f)
    }

    @Test
    fun `nose-up icons face their course on all cardinal bearings`() {
        for (course in listOf(0f, 90f, 180f, 270f)) {
            assertFacesCourse(course, base = 0f)
        }
    }

    @Test
    fun `nose-down (fpv photo) icons face their course`() {
        for (course in listOf(0f, 90f, 180f, 270f)) {
            assertFacesCourse(course, base = 180f)
        }
    }

    @Test
    fun `angled art (cruise photo base 45) faces its course`() {
        assertFacesCourse(90f, base = 45f)
        assertFacesCourse(0f, base = 45f)
        assertFacesCourse(270f, base = 265f)
    }

    @Test
    fun `rotation is the negative of the compass offset`() {
        assertEquals(-90f, threatMarkerRotation(90f, 0f), 0.001f)
        assertEquals(-270f, threatMarkerRotation(90f, 180f), 0.001f)
        assertEquals(0f, threatMarkerRotation(45f, 45f), 0.001f)
    }
}