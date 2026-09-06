package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.distanceFlat

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
        updatedAt: Long = 0L,
        explanationShort: String? = null,
        areaOnly: Boolean = false
    ): NormalizedThreat = NormalizedThreat(
        id = id, type = type, title = "t", region = null, district = null, locality = null,
        lat = lat, lon = lon, heading = heading, bearingDeg = bearingDeg, status = "active",
        advisory = false, areaOnly = areaOnly, confirmations = 1, reliability = "high", count = 1,
        explanationShort = explanationShort, speedKmh = null, uncertaintyKm = null, positionQuality = quality,
        confirmedAtMillis = updatedAt, updatedAtMillis = updatedAt, trail = emptyList()
    )

    private fun poseAtRing(t: NormalizedThreat, engine: ThreatEngine, props: ThreatProps, now: Long): MarkerPose =
        resolveThreatPose(engine, t, props, redKm = 20, yellowKm = 50, now = now)

    @Test
    fun `orbit tangent follows the clockwise patrol`() {
        assertEquals(90.0, orbitTangentBearing(0.0), 0.001)                       // at north point, moving east
        assertEquals(180.0, orbitTangentBearing(Math.toRadians(90.0)), 0.001)     // east point → south
        assertEquals(270.0, orbitTangentBearing(Math.toRadians(180.0)), 0.001)    // south point → west
        assertEquals(0.0, orbitTangentBearing(Math.toRadians(270.0)), 0.001)      // west point → north
    }

    @Test
    fun `fast threats heading to a city orbit its yellow ring`() {
        // Chornomorsk (near Odesa) is the named destination; the threat is approx, far out.
        val fast = threat(lat = 50.4, lon = 30.4, explanationShort = "Ракета летить на Чорноморськ")
        val fastPose = poseAtRing(fast, fastEngine, fastProps, now = 0L)
        assertEquals(ThreatPoseMode.ORBIT, fastPose.mode)
        val dest = Cities.findCity("Чорноморськ")!!
        assertEquals(50.0, distanceFlat(dest.lat, dest.lon, fastPose.lat, fastPose.lon) / 1000.0, 1.0)
        assertEquals(orbitTangentBearing(orbitAngle(0L, fast.id)).toFloat(), fastPose.headingDeg, 0.001f)
    }

    @Test
    fun `slow threats heading to a city orbit the same yellow ring`() {
        val slow = threat(type = "shahed", lat = 50.4, lon = 30.4, explanationShort = "Шахеди курсом на Чорноморськ")
        val slowPose = poseAtRing(slow, slowEngine, slowProps, now = 0L)
        assertEquals(ThreatPoseMode.ORBIT, slowPose.mode)
        val dest = Cities.findCity("Чорноморськ")!!
        assertEquals(50.0, distanceFlat(dest.lat, dest.lon, slowPose.lat, slowPose.lon) / 1000.0, 1.0)
    }

    @Test
    fun `a threat already inside the red zone parks instead of orbiting`() {
        // Raw fix within redKm of the destination → somebody has better coords, park.
        val dest = Cities.findCity("Чорноморськ")!!
        val t = threat(lat = dest.lat, lon = dest.lon, explanationShort = "Шахеди курсом на Чорноморськ")
        val pose = poseAtRing(t, slowEngine, slowProps, now = 0L)
        assertEquals(ThreatPoseMode.PARKED, pose.mode)
        assertEquals(t.lat, pose.lat, 0.0)
        assertEquals(t.lon, pose.lon, 0.0)
    }

    @Test
    fun `an approximate track with no resolvable destination drifts along its bearing`() {
        val t = threat(lat = 50.4, lon = 30.4)   // approx but no course target
        val pose = poseAtRing(t, fastEngine, fastProps, now = 0L)
        assertEquals(ThreatPoseMode.DRIFT, pose.mode)
    }

    @Test
    fun `a stale approximate track without a destination parks on its raw fix`() {
        val t = threat(lat = 50.01, lon = 30.0, updatedAt = 0L)
        val pose = resolveThreatPose(fastEngine, t, fastProps, 20, 50, now = 1_000_000L)
        assertEquals(ThreatPoseMode.PARKED, pose.mode)
        assertEquals(50.01, pose.lat, 0.0)
        assertEquals(30.0, pose.lon, 0.0)
    }

    @Test
    fun `a course-carrying confirmed track drifts along its bearing`() {
        val t = threat(quality = "confirmed", bearingDeg = 0.0, updatedAt = 0L)
        val pose = resolveThreatPose(fastEngine, t, fastProps, 20, 50, now = 10_000L)
        assertEquals(ThreatPoseMode.DRIFT, pose.mode)
        assertEquals(0.0f, pose.headingDeg, 0.001f)
        val metersNorth = (pose.lat - 50.0) * 111_320.0
        assertEquals(236.11 * 10.0, metersNorth, 100.0)
    }

    @Test
    fun `a stale track parks on its raw fix`() {
        val t = threat(updatedAt = 0L)
        val pose = resolveThreatPose(fastEngine, t, fastProps, 20, 50, now = 1_000_000L)
        assertEquals(ThreatPoseMode.PARKED, pose.mode)
        assertEquals(50.0, pose.lat, 0.0)
        assertEquals(30.0, pose.lon, 0.0)
    }

    @Test
    fun `an area-only threat never orbits`() {
        val t = threat(
            lat = 50.4, lon = 30.4, areaOnly = true,
            explanationShort = "Шахеди курсом на Чорноморськ"
        )
        val pose = poseAtRing(t, slowEngine, slowProps, now = 0L)
        assertNotEquals(ThreatPoseMode.ORBIT, pose.mode)
    }
}