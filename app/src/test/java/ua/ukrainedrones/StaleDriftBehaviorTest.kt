package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.distanceFlat

class StaleDriftBehaviorTest {

    private val props = ThreatProps(
        isFast = false, reachKm = 1500.0, alwaysInnerWithinReach = false,
        staleAfterMs = 1_000, ghostCapMs = 9_000,
        nominalSpeedMps = 10.0, horizonSec = 60.0, maxGhostMeters = 30_000.0
    )
    private val engine = ThreatEngine(mapOf("shahed" to props))

    private fun threat(
        status: String = "stale",
        bearingDeg: Double? = 90.0,
        heading: Double? = null,
        updatedAtMillis: Long = 0L,
        confirmedAtMillis: Long? = updatedAtMillis,
        areaOnly: Boolean = false,
        speedKmh: Double = 36.0
    ): NormalizedThreat = NormalizedThreat(
        id = "stale-drift", type = "shahed", title = "stale", region = null, district = null, locality = null,
        lat = 50.0, lon = 30.0, heading = heading, bearingDeg = bearingDeg, status = status,
        advisory = false, areaOnly = areaOnly, confirmations = 1, reliability = "high", count = 1,
        explanationShort = null, speedKmh = speedKmh, uncertaintyKm = null, positionQuality = "confirmed",
        confirmedAtMillis = confirmedAtMillis, updatedAtMillis = updatedAtMillis, trail = emptyList()
    )

    @Test
    fun `stale slow course loops from the latest native fix`() {
        val t = threat()
        val first = StaleDriftBehavior.apply(t, engine, now = 5_000L)!!
        val wrapped = StaleDriftBehavior.apply(t, engine, now = 65_000L)!!

        assertTrue(first.moving)
        assertTrue(wrapped.moving)
        assertEquals(90.0f, first.headingDeg, 0.001f)
        assertEquals(first.lat, wrapped.lat, 0.000001)
        assertEquals(first.lon, wrapped.lon, 0.000001)
        assertTrue(first.lon > t.lon)
    }

    @Test
    fun `stale drift is capped at five kilometres`() {
        val t = threat(speedKmh = 360.0)
        val outcome = StaleDriftBehavior.apply(t, engine, now = 600_000L)!!
        val distance = distanceFlat(t.lat, t.lon, outcome.lat, outcome.lon)

        assertTrue(outcome.moving)
        assertTrue(distance <= 5_000.001)
    }

    @Test
    fun `stale drift anchors on updatedAt rather than confirmedAt`() {
        val t = threat(updatedAtMillis = 10_000L, confirmedAtMillis = 0L)
        val outcome = StaleDriftBehavior.apply(t, engine, now = 15_000L)!!
        val distance = distanceFlat(t.lat, t.lon, outcome.lat, outcome.lon)

        assertTrue(outcome.moving)
        assertTrue(distance in 49.0..51.0)
    }

    @Test
    fun `stale drift gate rejects fresh fast area-only course-less and resolved threats`() {
        val fresh = threat(status = "active", updatedAtMillis = 500L)
        val fastEngine = ThreatEngine(mapOf("shahed" to props.copy(isFast = true)))
        val areaOnly = threat(areaOnly = true)
        val courseLess = threat(bearingDeg = null)
        val resolved = threat(status = "resolved")

        assertNull(StaleDriftBehavior.apply(fresh, engine, now = 5_000L))
        assertNull(StaleDriftBehavior.apply(threat(), fastEngine, now = 5_000L))
        assertNull(StaleDriftBehavior.apply(areaOnly, engine, now = 5_000L))
        assertNull(StaleDriftBehavior.apply(courseLess, engine, now = 5_000L))
        assertNull(StaleDriftBehavior.apply(resolved, engine, now = 5_000L))
    }

    @Test
    fun `stale destination outside red uses stale drift after orbit behavior declines`() {
        val t = threat(
            lat = 50.4,
            lon = 30.4,
            explanationShort = "Шахеди курсом на Чорноморськ"
        )
        val outcome = resolveThreatBehavior(
            engine,
            t,
            listOf(OrbitBehavior(redKm = 20, yellowKm = 50), StaleDriftBehavior),
            now = 5_000L
        )

        assertTrue(outcome.moving)
        assertTrue(outcome.lon > t.lon)
    }

    @Test
    fun `stale destination inside red parks before stale drift`() {
        val dest = Cities.findCity("Чорноморськ")!!
        val t = threat(
            lat = dest.lat,
            lon = dest.lon,
            explanationShort = "Шахеди курсом на Чорноморськ"
        )
        val outcome = resolveThreatBehavior(
            engine,
            t,
            listOf(OrbitBehavior(redKm = 20, yellowKm = 50), StaleDriftBehavior),
            now = 5_000L
        )

        assertFalse(outcome.moving)
        assertEquals(dest.lat, outcome.lat, 0.0)
        assertEquals(dest.lon, outcome.lon, 0.0)
    }
}
