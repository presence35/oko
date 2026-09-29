package com.odesaplay.oko

import com.odesaplay.oko.engine.LatLng
import com.odesaplay.oko.engine.distanceHaversine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StrikePlanTest {

    private val at = LatLng(51.36, 32.05)
    private val speedMps = 200.0

    private fun courseOf(plan: StrikePlan): Double? {
        val dLat = plan.anchor.lat - plan.start.lat
        val dLon = plan.anchor.lon - plan.start.lon
        return if (dLat == 0.0 && dLon == 0.0) null else {
            com.odesaplay.oko.engine.bearingFlat(plan.start.lat, plan.start.lon, plan.anchor.lat, plan.anchor.lon)
        }
    }

    private fun moving(headingDeg: Float) =
        BehaviorOutcome(at.lat, at.lon, headingDeg, moving = true)

    private fun parked() = BehaviorOutcome(at.lat, at.lon, 90f, moving = false)

    @Test
    fun `a course the source never reported never flies`() {
        assertEquals(null, strikeCourseDeg(parked()))
        assertTrue(strikePlan(at, strikeCourseDeg(parked()), 0f, speedMps) is StrikeInPlace)
    }

    @Test
    fun `a removal with no reported course explodes where it sits`() {
        assertEquals(null, strikeCourseDeg(outcome = null))
        assertTrue(strikePlan(at, strikeCourseDeg(null), 0f, speedMps) is StrikeInPlace)
    }

    @Test
    fun `moving threat intercepts along its own course`() {
        val plan = strikePlan(at, strikeCourseDeg(moving(90f)), 0f, speedMps)
        assertTrue(plan is StrikeFlight)
        val moved = distanceHaversine(plan.start.lat, plan.start.lon, plan.anchor.lat, plan.anchor.lon)
        assertEquals(speedMps * 2.0, moved, 1.0)
        assertEquals(90.0, courseOf(plan)!!, 1.0)
    }

    @Test
    fun `engine outcome overrides the removal bearing`() {
        assertEquals(null, strikeCourseDeg(parked(), removalCourse = 270.0))
        assertEquals(270.0, strikeCourseDeg(null, removalCourse = 270.0)!!, 1e-9)
    }

    @Test
    fun `no nominal speed means nothing to extrapolate`() {
        assertTrue(strikePlan(at, strikeCourseDeg(moving(45f)), 0f, 0.0) is StrikeInPlace)
    }

    @Test
    fun `in-place plan keeps the marker rotation`() {
        assertEquals(37f, strikePlan(at, null, 37f, speedMps).rotationDeg, 1e-6f)
    }
}
