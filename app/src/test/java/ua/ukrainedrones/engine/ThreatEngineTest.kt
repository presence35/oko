package ua.ukrainedrones.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ThreatEngineTest {

    private val userLat = 50.4501
    private val userLng = 30.5234
    private val params = ZoneParams(slowRedKm = 15, slowYellowKm = 40, fastRedMin = 2, fastYellowMin = 5)
    private lateinit var engine: ThreatEngine

    @Before
    fun setUp() {
        engine = ThreatEngine(NEPTUN_TYPES)
        engine.speedCache.clear()
    }

    @Test
    fun `distanceHaversine - same point returns 0`() {
        assertEquals(0.0, distanceHaversine(userLat, userLng, userLat, userLng), 1.0)
    }

    @Test
    fun `distanceHaversine - 1 degree latitude is about 110km`() {
        val dist = distanceHaversine(50.0, 30.0, 51.0, 30.0)
        assertEquals(111_320.0, dist, 500.0)
    }

    @Test
    fun `distanceHaversine - Kyiv to Odessa`() {
        val dist = distanceHaversine(50.4501, 30.5234, 46.4825, 30.7233)
        assertEquals(441_000.0, dist, 5_000.0)
    }

    @Test
    fun `bearingHaversine - north is 0`() {
        assertEquals(0.0, bearingHaversine(50.0, 30.0, 51.0, 30.0), 1.0)
    }

    @Test
    fun `bearingHaversine - east is 90`() {
        assertEquals(90.0, bearingHaversine(50.0, 30.0, 50.0, 31.0), 1.0)
    }

    @Test
    fun `zoneTier - slow threat tiers by distance`() {
        val props = NEPTUN_TYPES["shahed"]!!
        assertEquals(ThreatZone.INNER, engine.zoneTier(props, 10.0, 180.0, params))
        assertEquals(ThreatZone.OUTER, engine.zoneTier(props, 30.0, 180.0, params))
        assertNull(engine.zoneTier(props, 50.0, 180.0, params))
    }

    @Test
    fun `zoneTier - slow threat ignores speed`() {
        val props = NEPTUN_TYPES["shahed"]!!
        assertEquals(ThreatZone.INNER, engine.zoneTier(props, 10.0, null, params))
        assertEquals(ThreatZone.OUTER, engine.zoneTier(props, 30.0, null, params))
    }

    @Test
    fun `zoneTier - fast threat tiers by ETA`() {
        val props = NEPTUN_TYPES["ballistic"]!!
        // 150 km at 3300 km/h = ~2.73 min → OUTER (fastYellowMin=5)
        assertEquals(ThreatZone.OUTER, engine.zoneTier(props, 150.0, 3300.0, params))
        // 50 km at 3300 km/h = ~0.91 min → INNER (fastRedMin=2)
        assertEquals(ThreatZone.INNER, engine.zoneTier(props, 50.0, 3300.0, params))
    }

    @Test
    fun `zoneTier - fast threat with no speed never tiers`() {
        val props = NEPTUN_TYPES["ballistic"]!!
        assertNull(engine.zoneTier(props, 300.0, null, params))
    }

    @Test
    fun `zoneTier - aviation always INNER within reach`() {
        val props = NEPTUN_TYPES["aviation"]!!
        assertEquals(ThreatZone.INNER, engine.zoneTier(props, 400.0, 900.0, params))
        assertEquals(ThreatZone.INNER, engine.zoneTier(props, 1400.0, null, params))
    }

    @Test
    fun `zoneTier - aviation beyond reach never tiers`() {
        val props = NEPTUN_TYPES["aviation"]!!
        assertNull(engine.zoneTier(props, 10_000.0, 900.0, params))
    }

    @Test
    fun `zoneTier - KAB reach is 70km`() {
        val props = NEPTUN_TYPES["kab"]!!
        // 30 km at 900 km/h = 2 min → INNER (fastRedMin=2)
        assertEquals(ThreatZone.INNER, engine.zoneTier(props, 30.0, 900.0, params))
        assertNull(engine.zoneTier(props, 100.0, 900.0, params))
    }

    @Test
    fun `predictPosition - not flying returns null`() {
        val threat = makeThreat(bearingDeg = null, confirmedAtMillis = System.currentTimeMillis() - 60_000)
        val props = NEPTUN_TYPES["shahed"]!!
        assertNull(engine.predictPosition(threat, 50.0, props, System.currentTimeMillis()))
    }

    @Test
    fun `predictPosition - resolved status returns null`() {
        val threat = makeThreat(status = "resolved", confirmedAtMillis = System.currentTimeMillis() - 60_000)
        val props = NEPTUN_TYPES["shahed"]!!
        assertNull(engine.predictPosition(threat, 50.0, props, System.currentTimeMillis()))
    }

    @Test
    fun `predictPosition - dead reckons along heading`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(
            lat = 50.0, lon = 30.0,
            bearingDeg = 0.0, confirmedAtMillis = now - 60_000
        )
        val props = NEPTUN_TYPES["shahed"]!!
        val pos = engine.predictPosition(threat, 50.0, props, now)
        assertNotNull(pos)
        assertTrue(pos!!.lat > 50.0)
        assertEquals(30.0, pos.lon, 0.01)
    }

    @Test
    fun `motionHeading - prefers bearingDeg`() {
        val threat = makeThreat(bearingDeg = 90.0, heading = 180.0)
        assertEquals(90.0, engine.motionHeading(threat)!!, 0.1)
    }

    @Test
    fun `motionHeading - falls back to heading`() {
        val threat = makeThreat(bearingDeg = null, heading = 180.0)
        assertEquals(180.0, engine.motionHeading(threat)!!, 0.1)
    }

    @Test
    fun `motionHeading - falls back to measured`() {
        val now = System.currentTimeMillis()
        engine.speedCache.record("motion-test", now - 10_000, 50.0, 30.0)
        engine.speedCache.record("motion-test", now, 50.1, 30.0)
        val threat = makeThreat(id = "motion-test", bearingDeg = null, heading = null)
        assertNotNull(engine.motionHeading(threat))
    }

    @Test
    fun `isStale - fresh threat returns false`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(updatedAtMillis = now - 30_000)
        val props = NEPTUN_TYPES["shahed"]!!
        assertFalse(engine.isStale(threat, props, now))
    }

    @Test
    fun `isStale - past stale window returns true`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(updatedAtMillis = now - 400_000)
        val props = NEPTUN_TYPES["shahed"]!!
        assertTrue(engine.isStale(threat, props, now))
    }

    @Test
    fun `isGhost - within ghost cap returns false`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(updatedAtMillis = now - 400_000)
        val props = NEPTUN_TYPES["shahed"]!!
        assertFalse(engine.isGhost(threat, props, now))
    }

    @Test
    fun `isGhost - past ghost cap returns true`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(updatedAtMillis = now - 1_300_000)
        val props = NEPTUN_TYPES["shahed"]!!
        assertTrue(engine.isGhost(threat, props, now))
    }

    @Test
    fun `isGhost - aviation uses longer ghost cap`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(type = "aviation", updatedAtMillis = now - 1_300_000)
        val props = NEPTUN_TYPES["aviation"]!!
        assertFalse(engine.isGhost(threat, props, now))
    }

    @Test
    fun `speedCache - records and estimates speed`() {
        val now = System.currentTimeMillis()
        engine.speedCache.record("t1", now - 10_000, 50.0, 30.0)
        engine.speedCache.record("t1", now, 50.05, 30.0)
        val threat = makeThreat(id = "t1", speedKmh = null)
        val props = NEPTUN_TYPES["shahed"]!!
        val speed = engine.speedCache.estimate("t1", threat, props)
        assertNotNull(speed)
        assertTrue(speed!! > 100.0)
    }

    @Test
    fun `speedCache - prefers server speedKmh over measured`() {
        val now = System.currentTimeMillis()
        engine.speedCache.record("t2", now - 10_000, 50.0, 30.0)
        engine.speedCache.record("t2", now, 50.1, 30.0)
        val threat = makeThreat(id = "t2", speedKmh = 180.0)
        val props = NEPTUN_TYPES["shahed"]!!
        val result = engine.speedCache.estimateWithSource("t2", threat, props)
        assertNotNull(result)
        assertEquals(180.0 / 3.6, result!!.first, 0.1)
        assertEquals(SpeedSource.RECORDED, result.second)
    }

    @Test
    fun `speedCache - falls back to nominal when no data`() {
        val threat = makeThreat(id = "t3", speedKmh = null)
        val props = NEPTUN_TYPES["shahed"]!!
        val result = engine.speedCache.estimateWithSource("t3", threat, props)
        assertNotNull(result)
        assertEquals(SpeedSource.TYPICAL, result!!.second)
    }

    @Test
    fun `speedCache - thread safety`() {
        val threads = (1..10).map { i ->
            Thread {
                repeat(100) { j ->
                    engine.speedCache.record("concurrent", System.currentTimeMillis() + j, 50.0 + i * 0.001, 30.0)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val threat = makeThreat(id = "concurrent", speedKmh = null)
        val props = NEPTUN_TYPES["shahed"]!!
        val avg = engine.speedCache.estimate("concurrent", threat, props)
        assertNotNull(avg)
        assertTrue(avg!! >= 0)
    }

    @Test
    fun `evaluate - UAV inside red returns INNER`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(1, result.threatsInner.size)
        assertEquals(0, result.threatsOuter.size)
        assertEquals(ThreatZone.INNER, result.zoneThreats[threat.id])
        assertEquals(ThreatZone.INNER, result.activeZone)
    }

    @Test
    fun `evaluate - UAV between red and yellow returns OUTER`() {
        val threat = makeThreat(
            lat = userLat + 0.20, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(0, result.threatsInner.size)
        assertEquals(1, result.threatsOuter.size)
        assertEquals(ThreatZone.OUTER, result.zoneThreats[threat.id])
    }

    @Test
    fun `evaluate - resolved threat excluded`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0, status = "resolved"
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(0, result.mapThreats.size)
    }

    @Test
    fun `evaluate - ghost threat excluded`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0,
            updatedAtMillis = now - 1_300_000
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = now
        )
        assertEquals(0, result.mapThreats.size)
    }

    @Test
    fun `evaluate - areaOnly shown on map but not in zones`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0, areaOnly = true
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(1, result.mapThreats.size)
        assertTrue(result.threatsInner.isEmpty())
        assertTrue(result.threatsOuter.isEmpty())
    }

    @Test
    fun `evaluate - advisory excluded from zones`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0, advisory = true
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(1, result.mapThreats.size)
        assertTrue(result.threatsInner.isEmpty())
    }

    @Test
    fun `evaluate - hidden type excluded from map`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = setOf("shahed"),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(0, result.mapThreats.size)
    }

    @Test
    fun `evaluate - silenced type shown on map but not in zones`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = setOf("shahed"),
            now = System.currentTimeMillis()
        )
        assertEquals(1, result.mapThreats.size)
        assertTrue(result.threatsInner.isEmpty())
    }

    @Test
    fun `evaluate - null focus returns empty zones`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = null,
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(1, result.mapThreats.size)
        assertTrue(result.threatsInner.isEmpty())
        assertNull(result.activeZone)
    }

    @Test
    fun `evaluate - multiple threats correct activeZone`() {
        val red = makeThreat(
            id = "red", lat = userLat + 0.05, lon = userLng, speedKmh = 180.0
        )
        val yellow = makeThreat(
            id = "yellow", lat = userLat + 0.20, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(red, yellow),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertEquals(ThreatZone.INNER, result.activeZone)
        assertEquals(1, result.threatsInner.size)
        assertEquals(1, result.threatsOuter.size)
    }

    @Test
    fun `evaluate - threat level is computed`() {
        val threat = makeThreat(
            lat = userLat + 0.05, lon = userLng, speedKmh = 180.0
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis()
        )
        assertTrue(result.threatLevel > 0.0)
    }

    @Test
    fun `scoreThreat - returns 0 beyond yellow zone`() {
        val threat = makeThreat()
        val props = NEPTUN_TYPES["shahed"]!!
        val score = engine.scoreThreat(threat, props, 100.0, null, params.slowRedKm, params.slowYellowKm, System.currentTimeMillis())
        assertEquals(0.0, score, 0.01)
    }

    @Test
    fun `scoreThreat - returns positive within red zone`() {
        val threat = makeThreat()
        val props = NEPTUN_TYPES["shahed"]!!
        val score = engine.scoreThreat(threat, props, 5.0, 2.0, params.slowRedKm, params.slowYellowKm, System.currentTimeMillis())
        assertTrue(score > 0.0)
    }

    @Test
    fun `etaMinutes - converts distance and speed`() {
        assertEquals(10.0, ThreatEngine.etaMinutes(30.0, 180.0)!!, 1e-9)
        assertNull(ThreatEngine.etaMinutes(30.0, null))
        assertNull(ThreatEngine.etaMinutes(30.0, 0.0))
    }

    @Test
    fun `computeProximity - returns distance and ETA`() {
        val threat = makeThreat(
            lat = userLat + 0.10, lon = userLng, speedKmh = 180.0,
            confirmedAtMillis = null
        )
        val proximity = engine.computeProximity(
            threat, LatLng(userLat, userLng), params, System.currentTimeMillis()
        )
        assertNotNull(proximity)
        assertNotNull(proximity!!.distToUserKm)
        assertNotNull(proximity.etaToUserMin)
    }

    @Test
    fun `computeProximity - null threat returns null`() {
        assertNull(engine.computeProximity(null, LatLng(userLat, userLng), params, System.currentTimeMillis()))
    }

    @Test
    fun `computeProximity - areaOnly returns null`() {
        val threat = makeThreat(areaOnly = true)
        assertNull(engine.computeProximity(threat, LatLng(userLat, userLng), params, System.currentTimeMillis()))
    }

    private fun makeThreat(
        id: String = "test-${System.nanoTime()}",
        type: String = "shahed",
        lat: Double = 50.0,
        lon: Double = 30.0,
        speedKmh: Double? = 180.0,
        bearingDeg: Double? = 180.0,
        heading: Double? = null,
        updatedAtMillis: Long = System.currentTimeMillis(),
        confirmedAtMillis: Long? = System.currentTimeMillis() - 60_000,
        status: String = "active",
        advisory: Boolean = false,
        areaOnly: Boolean = false
    ): NormalizedThreat = NormalizedThreat(
        id = id,
        type = type,
        title = "Test",
        region = null,
        district = null,
        locality = null,
        lat = lat,
        lon = lon,
        heading = heading,
        bearingDeg = bearingDeg,
        status = status,
        advisory = advisory,
        areaOnly = areaOnly,
        confirmations = 1,
        reliability = "medium",
        count = 1,
        explanationShort = null,
        speedKmh = speedKmh,
        uncertaintyKm = null,
        positionQuality = null,
        confirmedAtMillis = confirmedAtMillis,
        updatedAtMillis = updatedAtMillis,
        trail = emptyList()
    )
}
