package com.presaince.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.engine.ThreatProps
import com.presaince.oko.engine.ZoneParams
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

    private val params = ZoneParams(slowRedKm = 20, slowYellowKm = 50, fastRedMin = 5, fastYellowMin = 20)
    private val dest = Cities.findCity("Чорноморськ")!!
    private val focus = LatLng(dest.lat, dest.lon)
    private val destLatLng = LatLng(dest.lat, dest.lon)

    private fun threat(
        id: String = "t1",
        type: String = "cruise",
        lat: Double = 50.4,
        lon: Double = 30.4,
        bearingDeg: Double? = 0.0,
        heading: Double? = null,
        quality: String = "approx",
        updatedAtMillis: Long = 0L,
        confirmedAtMillis: Long? = updatedAtMillis,
        destination: LatLng? = null,
        areaOnly: Boolean = false,
        speedKmh: Double? = null,
        status: String = "active"
    ): NormalizedThreat = NormalizedThreat(
        id = id, type = type, title = "t", region = null, district = null, locality = null,
        lat = lat, lon = lon, heading = heading, bearingDeg = bearingDeg, status = status,
        advisory = false, areaOnly = areaOnly, confirmations = 1, reliability = "high", count = 1,
        explanationShort = null, speedKmh = speedKmh, uncertaintyKm = null, positionQuality = quality,
        confirmedAtMillis = confirmedAtMillis, updatedAtMillis = updatedAtMillis, trail = emptyList(),
        destination = destination
    )

    private fun outcome(t: NormalizedThreat, engine: ThreatEngine, now: Long = 0L): BehaviorOutcome =
        resolveThreatBehavior(engine, t, listOf(OrbitBehavior(params, focus)), now)

    @Test
    fun `orbit tangent follows the clockwise patrol`() {
        assertEquals(90.0, orbitTangentBearing(0.0), 0.001)
        assertEquals(180.0, orbitTangentBearing(Math.toRadians(90.0)), 0.001)
        assertEquals(270.0, orbitTangentBearing(Math.toRadians(180.0)), 0.001)
        assertEquals(0.0, orbitTangentBearing(Math.toRadians(270.0)), 0.001)
    }

    @Test
    fun `a fast inbound track at the city patrols the yellow ring`() {
        val t = threat(lat = dest.lat, lon = dest.lon, destination = destLatLng)
        val outcome = outcome(t, fastEngine)
        assertTrue(outcome.moving)
        assertEquals(50.0, distanceFlat(dest.lat, dest.lon, outcome.lat, outcome.lon) / 1000.0, 1.0)
        assertEquals(orbitTangentBearing(orbitAngle(0L, t, destLatLng)).toFloat(), outcome.headingDeg, 0.001f)
    }

    @Test
    fun `a slow inbound track at the city patrols the yellow ring`() {
        val t = threat(type = "shahed", lat = dest.lat, lon = dest.lon, destination = destLatLng)
        val outcome = outcome(t, slowEngine)
        assertTrue(outcome.moving)
        assertEquals(50.0, distanceFlat(dest.lat, dest.lon, outcome.lat, outcome.lon) / 1000.0, 1.0)
    }

    @Test
    fun `an inbound track far from the city is not pulled onto the ring`() {
        val t = threat(lat = 50.4, lon = 30.4, destination = destLatLng)
        val outcome = outcome(t, fastEngine, now = 10_000L)
        assertTrue(outcome.moving)
        assertEquals(0.0f, outcome.headingDeg, 0.001f)
        assertTrue(distanceFlat(dest.lat, dest.lon, outcome.lat, outcome.lon) / 1000.0 > 100.0)
    }

    @Test
    fun `a track with no named destination is not inbound`() {
        assertFalse(isInbound(threat(lat = 50.4, lon = 30.4), focus, params, fastProps, 0L))
    }

    @Test
    fun `an approximate track with no inbound signal drifts along its bearing`() {
        val t = threat(lat = 50.4, lon = 30.4)
        val outcome = outcome(t, fastEngine, now = 10_000L)
        assertTrue(outcome.moving)
        assertEquals(0.0f, outcome.headingDeg, 0.001f)
        assertTrue(outcome.lat > 50.4)
    }

    @Test
    fun `a stale track without an inbound signal parks on its raw fix`() {
        val t = threat(lat = 50.01, lon = 30.0, updatedAtMillis = 0L)
        val outcome = outcome(t, fastEngine, now = 1_000_000L)
        assertFalse(outcome.moving)
        assertEquals(50.01, outcome.lat, 0.0)
        assertEquals(30.0, outcome.lon, 0.0)
    }

    @Test
    fun `a confirmed track is never treated as inbound`() {
        val t = threat(quality = "confirmed", destination = destLatLng)
        assertFalse(isInbound(t, focus, params, fastProps, 0L))
    }

    @Test
    fun `an area-only threat is never treated as inbound`() {
        val t = threat(areaOnly = true, destination = destLatLng)
        assertFalse(isInbound(t, focus, params, slowProps, 0L))
    }
}
