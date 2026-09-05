package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.ukrainedrones.engine.LatLng
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
        updatedAt: Long = 0L
    ): NormalizedThreat = NormalizedThreat(
        id = id, type = type, title = "t", region = null, district = null, locality = null,
        lat = lat, lon = lon, heading = heading, bearingDeg = bearingDeg, status = "active",
        advisory = false, areaOnly = false, confirmations = 1, reliability = "high", count = 1,
        explanationShort = null, speedKmh = null, uncertaintyKm = null, positionQuality = quality,
        confirmedAtMillis = updatedAt, updatedAtMillis = updatedAt, trail = emptyList()
    )

    private fun poseAtRing(t: NormalizedThreat, engine: ThreatEngine, props: ThreatProps, now: Long): MarkerPose =
        resolveThreatPose(engine, t, props, LatLng(50.0, 30.0), redKm = 20, yellowKm = 50, now = now)

    @Test
    fun `orbit tangent follows the clockwise patrol`() {
        assertEquals(90.0, orbitTangentBearing(0.0), 0.001)                       // at north point, moving east
        assertEquals(180.0, orbitTangentBearing(Math.toRadians(90.0)), 0.001)     // east point → south
        assertEquals(270.0, orbitTangentBearing(Math.toRadians(180.0)), 0.001)    // south point → west
        assertEquals(0.0, orbitTangentBearing(Math.toRadians(270.0)), 0.001)      // west point → north
    }

    @Test
    fun `fast threats orbit the red ring, slow ones the yellow`() {
        val fast = threat(lat = 50.01, lon = 30.0)
        val fastPose = poseAtRing(fast, fastEngine, fastProps, now = 0L)
        assertEquals(ThreatPoseMode.ORBIT, fastPose.mode)
        assertEquals(20.0, distanceFlat(50.0, 30.0, fastPose.lat, fastPose.lon) / 1000.0, 1.0)
        assertEquals(orbitTangentBearing(orbitAngle(0L, fast.id)).toFloat(), fastPose.headingDeg, 0.001f)

        val slow = threat(type = "shahed", lat = 50.01, lon = 30.0)
        val slowPose = poseAtRing(slow, slowEngine, slowProps, now = 0L)
        assertEquals(ThreatPoseMode.ORBIT, slowPose.mode)
        assertEquals(50.0, distanceFlat(50.0, 30.0, slowPose.lat, slowPose.lon) / 1000.0, 1.0)
    }

    @Test
    fun `a course-carrying confirmed track drifts along its bearing`() {
        val t = threat(quality = "confirmed", bearingDeg = 0.0, updatedAt = 0L)
        val pose = resolveThreatPose(fastEngine, t, fastProps, LatLng(50.0, 30.0), 20, 50, now = 10_000L)
        assertEquals(ThreatPoseMode.DRIFT, pose.mode)
        assertEquals(0.0f, pose.headingDeg, 0.001f)
        val metersNorth = (pose.lat - 50.0) * 111_320.0
        assertEquals(236.11 * 10.0, metersNorth, 100.0)
    }

    @Test
    fun `a stale track parks on its raw fix`() {
        val t = threat(updatedAt = 0L)
        val pose = resolveThreatPose(fastEngine, t, fastProps, LatLng(50.0, 30.0), 20, 50, now = 1_000_000L)
        assertEquals(ThreatPoseMode.PARKED, pose.mode)
        assertEquals(50.0, pose.lat, 0.0)
        assertEquals(30.0, pose.lon, 0.0)
    }

    @Test
    fun `an approximate track beyond its ring drifts instead of orbiting`() {
        val t = threat(lat = 51.0, lon = 30.0)   // ~111 km north of focus — outside both rings
        val pose = poseAtRing(t, slowEngine, slowProps, now = 0L)
        assertEquals(ThreatPoseMode.DRIFT, pose.mode)
    }
}