package com.presaince.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.engine.ThreatProps
import com.presaince.oko.engine.distanceFlat

class OrbitMotionTest {

    private val fastProps = ThreatProps(
        isFast = true, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 180_000, ghostCapMs = 900_000,
        nominalSpeedMps = 236.11, horizonSec = 180.0, maxGhostMeters = 30_000.0
    )
    private val slowProps = fastProps.copy(isFast = false, nominalSpeedMps = 50.0)
    private val fastEngine = ThreatEngine(mapOf("cruise" to fastProps))
    private val slowEngine = ThreatEngine(mapOf("shahed" to slowProps))

    private fun threat(
        id: String = "t1",
        type: String = "cruise",
        lat: Double = 50.0,
        lon: Double = 30.0,
        bearingDeg: Double? = 0.0,
        heading: Double? = null,
        quality: String = "approx",
        updatedAtMillis: Long = 0L,
        confirmedAtMillis: Long? = updatedAtMillis,
        explanationShort: String? = null,
        areaOnly: Boolean = false,
        speedKmh: Double? = null,
        status: String = "active"
    ): NormalizedThreat = NormalizedThreat(
        id = id, type = type, title = "t", region = null, district = null, locality = null,
        lat = lat, lon = lon, heading = heading, bearingDeg = bearingDeg, status = status,
        advisory = false, areaOnly = areaOnly, confirmations = 1, reliability = "high", count = 1,
        explanationShort = explanationShort, speedKmh = speedKmh, uncertaintyKm = null, positionQuality = quality,
        confirmedAtMillis = confirmedAtMillis, updatedAtMillis = updatedAtMillis, trail = emptyList()
    )

    private fun outcomeAtRing(t: NormalizedThreat, engine: ThreatEngine, now: Long): BehaviorOutcome =
        resolveThreatBehavior(
            engine,
            t,
            listOf(OrbitBehavior(redKm = 20, yellowKm = 50), StaleDriftBehavior),
            now
        )

    @Test
    fun `orbit tangent follows the clockwise patrol`() {
        assertEquals(90.0, orbitTangentBearing(0.0), 0.001)
        assertEquals(180.0, orbitTangentBearing(Math.toRadians(90.0)), 0.001)
        assertEquals(270.0, orbitTangentBearing(Math.toRadians(180.0)), 0.001)
        assertEquals(0.0, orbitTangentBearing(Math.toRadians(270.0)), 0.001)
    }

    @Test
    fun `fast threats heading to a city orbit its yellow ring`() {
        val fast = threat(lat = 50.4, lon = 30.4, explanationShort = "Ракета летить на Чорноморськ")
        val outcome = outcomeAtRing(fast, fastEngine, now = 0L)
        assertTrue(outcome.moving)
        val dest = Cities.findCity("Чорноморськ")!!
        assertEquals(50.0, distanceFlat(dest.lat, dest.lon, outcome.lat, outcome.lon) / 1000.0, 1.0)
        assertEquals(orbitTangentBearing(orbitAngle(0L, fast.id)).toFloat(), outcome.headingDeg, 0.001f)
    }

    @Test
    fun `slow threats heading to a city orbit the same yellow ring`() {
        val slow = threat(type = "shahed", lat = 50.4, lon = 30.4, explanationShort = "Шахеди курсом на Чорноморськ")
        val outcome = outcomeAtRing(slow, slowEngine, now = 0L)
        assertTrue(outcome.moving)
        val dest = Cities.findCity("Чорноморськ")!!
        assertEquals(50.0, distanceFlat(dest.lat, dest.lon, outcome.lat, outcome.lon) / 1000.0, 1.0)
    }

    @Test
    fun `a threat already inside the red zone parks instead of orbiting`() {
        val dest = Cities.findCity("Чорноморськ")!!
        val t = threat(lat = dest.lat, lon = dest.lon, explanationShort = "Шахеди курсом на Чорноморськ")
        val outcome = outcomeAtRing(t, slowEngine, now = 0L)
        assertFalse(outcome.moving)
        assertEquals(t.lat, outcome.lat, 0.0)
        assertEquals(t.lon, outcome.lon, 0.0)
    }

    @Test
    fun `an approximate track with no resolvable destination drifts along its bearing`() {
        val t = threat(lat = 50.4, lon = 30.4)
        val outcome = outcomeAtRing(t, fastEngine, now = 10_000L)
        assertTrue(outcome.moving)
        assertEquals(0.0f, outcome.headingDeg, 0.001f)
    }

    @Test
    fun `a stale approximate track without a destination parks on its raw fix`() {
        val t = threat(lat = 50.01, lon = 30.0, updatedAtMillis = 0L)
        val outcome = outcomeAtRing(t, fastEngine, now = 1_000_000L)
        assertFalse(outcome.moving)
        assertEquals(50.01, outcome.lat, 0.0)
        assertEquals(30.0, outcome.lon, 0.0)
    }

    @Test
    fun `a course-carrying confirmed track drifts along its bearing`() {
        val t = threat(quality = "confirmed", bearingDeg = 0.0, updatedAtMillis = 0L)
        val outcome = outcomeAtRing(t, fastEngine, now = 10_000L)
        assertTrue(outcome.moving)
        assertEquals(0.0f, outcome.headingDeg, 0.001f)
        val metersNorth = (outcome.lat - 50.0) * 111_320.0
        assertEquals(236.11 * 10.0, metersNorth, 100.0)
    }

    @Test
    fun `a stale track parks on its raw fix`() {
        val t = threat(updatedAtMillis = 0L)
        val outcome = outcomeAtRing(t, fastEngine, now = 1_000_000L)
        assertFalse(outcome.moving)
        assertEquals(50.0, outcome.lat, 0.0)
        assertEquals(30.0, outcome.lon, 0.0)
    }

    @Test
    fun `an area-only threat never orbits`() {
        val t = threat(
            lat = 50.4, lon = 30.4, areaOnly = true,
            explanationShort = "Шахеди курсом на Чорноморськ"
        )
        val outcome = outcomeAtRing(t, slowEngine, now = 0L)
        assertFalse(outcome.moving)
        assertEquals(t.lat, outcome.lat, 0.0)
        assertEquals(t.lon, outcome.lon, 0.0)
    }
}
