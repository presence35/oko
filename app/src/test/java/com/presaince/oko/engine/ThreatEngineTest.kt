package com.presaince.oko.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.presaince.oko.Cities
import com.presaince.oko.community.CompactOblastBoundaries
import com.presaince.oko.community.CompactRaionBoundaries
import com.presaince.oko.source.NeptunSource.Companion.NEPTUN_TYPES
import com.presaince.oko.threat

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
    fun `holdTier - upgrades pass through immediately`() {
        val props = NEPTUN_TYPES["shahed"]!!
        assertEquals(ThreatZone.INNER, engine.holdTier(ThreatZone.INNER, ThreatZone.OUTER, props, 10.0, 180.0, params))
        assertEquals(ThreatZone.INNER, engine.holdTier(ThreatZone.INNER, null, props, 10.0, 180.0, params))
        assertEquals(ThreatZone.OUTER, engine.holdTier(ThreatZone.OUTER, null, props, 30.0, 180.0, params))
    }

    @Test
    fun `holdTier - inner holds within the margin, releases beyond`() {
        val props = NEPTUN_TYPES["shahed"]!!
        // Red 15 km, margin 10% → holds through 16.5 km.
        assertEquals(ThreatZone.INNER, engine.holdTier(ThreatZone.OUTER, ThreatZone.INNER, props, 15.75, 180.0, params))
        assertEquals(ThreatZone.INNER, engine.holdTier(null, ThreatZone.INNER, props, 16.0, 180.0, params))
        assertEquals(ThreatZone.OUTER, engine.holdTier(ThreatZone.OUTER, ThreatZone.INNER, props, 18.0, 180.0, params))
        assertNull(engine.holdTier(null, ThreatZone.INNER, props, 18.0, 180.0, params))
    }

    @Test
    fun `holdTier - outer holds within the margin, releases beyond`() {
        val props = NEPTUN_TYPES["shahed"]!!
        // Yellow 40 km, margin 10% → holds through 44 km.
        assertEquals(ThreatZone.OUTER, engine.holdTier(null, ThreatZone.OUTER, props, 42.0, 180.0, params))
        assertNull(engine.holdTier(null, ThreatZone.OUTER, props, 46.0, 180.0, params))
        // Upgrades still immediate.
        assertEquals(ThreatZone.INNER, engine.holdTier(ThreatZone.INNER, ThreatZone.OUTER, props, 10.0, 180.0, params))
    }

    @Test
    fun `holdTier - beyond reach always exits`() {
        val props = NEPTUN_TYPES["aviation"]!!
        assertNull(engine.holdTier(null, ThreatZone.INNER, props, 10_000.0, 900.0, params))
    }

    @Test
    fun `holdTier - fast threat holds on ETA margin`() {
        val props = NEPTUN_TYPES["ballistic"]!!
        // fastRedMin=2 → holds INNER through 2.2 min ETA; 150 km at 3300 km/h ≈ 2.73 min.
        assertEquals(ThreatZone.INNER, engine.holdTier(ThreatZone.OUTER, ThreatZone.INNER, props, 110.0, 3300.0, params))
        assertEquals(ThreatZone.OUTER, engine.holdTier(ThreatZone.OUTER, ThreatZone.INNER, props, 150.0, 3300.0, params))
    }

    @Test
    fun `predictPosition - no course returns null`() {
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
            bearingDeg = 0.0, updatedAtMillis = now - 60_000
        )
        val props = NEPTUN_TYPES["shahed"]!!
        val pos = engine.predictPosition(threat, 50.0, props, now)
        assertNotNull(pos)
        assertTrue(pos!!.lat > 50.0)
        assertEquals(30.0, pos.lon, 0.01)
    }

    @Test
    fun `predictPosition - dead reckons along reported heading`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(
            lat = 50.0, lon = 30.0,
            bearingDeg = null, heading = 45.0, updatedAtMillis = now - 60_000
        )
        val props = NEPTUN_TYPES["shahed"]!!
        val pos = engine.predictPosition(threat, 50.0, props, now)
        assertNotNull(pos)
        assertTrue(pos!!.lat > 50.0)
        assertTrue(pos.lon > 30.0)
    }

    @Test
    fun `predictPosition - anchors on updatedAt not old confirmedAt`() {
        val now = System.currentTimeMillis()
        // confirmedAt is 10 min old but the fix was refreshed 1 min ago — the icon must glide
        // ~1 min of travel (3 km at 50 m/s), not 10 min (which would exceed nothing here).
        val threat = makeThreat(
            lat = 50.0, lon = 30.0,
            bearingDeg = 0.0,
            updatedAtMillis = now - 60_000,
            confirmedAtMillis = now - 600_000
        )
        val props = NEPTUN_TYPES["shahed"]!!
        val pos = engine.predictPosition(threat, 50.0, props, now)
        assertNotNull(pos)
        val travelledMeters = (pos!!.lat - 50.0) * 111_320.0
        assertTrue("expected ~3 km glide, got $travelledMeters", travelledMeters in 2500.0..3500.0)
    }

    @Test
    fun `predictPosition - course-less track never moves (measured fallback dropped)`() {
        val now = System.currentTimeMillis()
        // No bearingDeg, no heading — a client-side measured fix track is NOT enough to move a
        // track the source reports no course for (NEPTUN isn't moving it).
        engine.speedCache.record("glide-measured", now - 10_000, 50.0, 30.0)
        engine.speedCache.record("glide-measured", now, 50.1, 30.0)
        val threat = makeThreat(
            id = "glide-measured",
            lat = 50.1, lon = 30.0,
            bearingDeg = null, heading = null,
            updatedAtMillis = now - 60_000
        )
        val props = NEPTUN_TYPES["shahed"]!!
        assertNull(engine.predictPosition(threat, 50.0, props, now))
    }

    @Test
    fun `predictPosition - stale track does not move`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(
            lat = 50.0, lon = 30.0,
            bearingDeg = 0.0, updatedAtMillis = now - 400_000
        )
        val props = NEPTUN_TYPES["shahed"]!!
        assertNull(engine.predictPosition(threat, 50.0, props, now))
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
    fun `isStale - aviation never locally expires past stale window`() {
        val now = System.currentTimeMillis()
        val threat = makeThreat(type = "aviation", updatedAtMillis = now - 600_000)
        val props = NEPTUN_TYPES["aviation"]!!
        assertFalse(engine.isStale(threat, props, now))
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
    fun `speedCache - absurd server speed is ignored, not trusted`() {
        val threat = makeThreat(id = "t-crazy", speedKmh = 1_000_000.0)
        val props = NEPTUN_TYPES["shahed"]!!
        val result = engine.speedCache.estimateWithSource("t-crazy", threat, props)
        assertNotNull(result)
        // A corrupt field must not yield a near-zero ETA: fall through to the typical speed.
        assertEquals(SpeedSource.TYPICAL, result!!.second)
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
    fun `speedCache - national MiG never estimates speed or ETA`() {
        val threat = makeThreat(id = "national-mig31k", type = "aviation", speedKmh = null).copy(
            region = "Загальнодержавна загроза",
            district = "Носій «Кинджал»",
            explanationShort = "Зафіксовано зліт МіГ-31К — носія аеробалістичних ракет «Кинджал»."
        )
        val props = NEPTUN_TYPES["aviation"]!!
        assertNull(engine.speedCache.estimateWithSource("national-mig31k", threat, props))
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
    fun `evaluate - Drone inside red returns INNER`() {
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
    fun `evaluate - Drone between red and yellow returns OUTER`() {
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
    fun `evaluate - official alert fills focusOblastAlertActive and redCities`() {
        val alert = OblastAlert(key = "odesa", name = "Одеська область", oblast = "Одеська", since = "x")
        val result = engine.evaluate(
            threats = emptyList(),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis(),
            alerts = listOf(alert),
            focusToken = "Одеськ"
        )
        assertTrue(result.focusOblastAlertActive)
        assertTrue(result.redCities.isNotEmpty())
    }

    @Test
    fun `computeFillKeys - raion alert fills the named raion with canonical keys`() {
        val alertCyrillic = OblastAlert(key = "бердянський", name = "Бердянський район", oblast = "Запорізька область", since = null)
        val (oblastTokens1, raionKeys1) = engine.computeFillKeys(listOf(alertCyrillic), fillRegions = true)
        assertTrue(oblastTokens1.isEmpty())
        assertTrue(("zaporizka" to "berdianskyi") in raionKeys1)

        val alertCanonical = OblastAlert(key = "izmailskyi", name = "Ізмаїльський район", oblast = "odeska", wide = false, since = null)
        val (oblastTokens2, raionKeys2) = engine.computeFillKeys(listOf(alertCanonical), fillRegions = true)
        assertTrue(oblastTokens2.isEmpty())
        assertTrue(("odeska" to "izmailskyi") in raionKeys2)
    }

    @Test
    fun `computeFillKeys - bare city alert does not produce raion keys`() {
        val alert = OblastAlert(key = "berdyansk", name = "Бердянськ", oblast = "Запорізька область", since = null)
        val (oblastTokens, raionKeys) = engine.computeFillKeys(listOf(alert), fillRegions = true)
        assertTrue(oblastTokens.isEmpty())
        assertTrue(raionKeys.isEmpty())
    }

    @Test
    fun `computeFillKeys - fill off leaves no region keys`() {
        val alert = OblastAlert(key = "бердянський", name = "Бердянський район", oblast = "Запорізька область", since = null)
        val (oblastTokens, raionKeys) = engine.computeFillKeys(listOf(alert), fillRegions = false)
        assertTrue(oblastTokens.isEmpty())
        assertTrue(raionKeys.isEmpty())
    }

    @Test
    fun `computeFillKeys - wide alert fills the whole oblast`() {
        val alert = OblastAlert(key = "odesa", name = "Одеська область", oblast = "Одеська", since = "x")
        val (oblastTokens, raionKeys) = engine.computeFillKeys(listOf(alert), fillRegions = true)
        assertTrue("odeska" in oblastTokens)
        assertTrue(raionKeys.isEmpty())

        val alertCanonical = OblastAlert(key = "kyivska", name = "Київська область", oblast = "kyivska", wide = true, since = null)
        val (oblastTokens2, raionKeys2) = engine.computeFillKeys(listOf(alertCanonical), fillRegions = true)
        assertTrue("kyivska" in oblastTokens2)
        assertTrue(raionKeys2.isEmpty())
    }

    @Test
    fun `canonicalId - every city stem resolves to a canonical boundary ID`() {
        for (stem in Cities.cityOblast.values.toSet()) {
            assertNotNull("stem $stem", CompactOblastBoundaries.canonicalId(stem))
        }
        assertEquals("odeska", CompactOblastBoundaries.canonicalId("Одеськ"))
        assertEquals("kharkivska", CompactOblastBoundaries.canonicalId("Харківська область"))
        assertEquals("kyivska", CompactOblastBoundaries.canonicalId("м. київ"))
        assertEquals("krym", CompactOblastBoundaries.canonicalId("Крим"))
        assertNull(CompactOblastBoundaries.canonicalId("Atlantis"))
    }

    @Test
    fun `forKey - unknown raion returns null instead of the parent oblast`() {
        assertNull(CompactRaionBoundaries.forKey("odeska", "неіснуючий"))
    }

    @Test
    fun `computeFillKeys - unknown raion produces no keys`() {
        val alert = OblastAlert(key = "неіснуючий", name = "Неіснуючий", oblast = "Одеська область", since = null)
        val (oblastTokens, raionKeys) = engine.computeFillKeys(listOf(alert), fillRegions = true)
        assertTrue(oblastTokens.isEmpty())
        assertTrue(raionKeys.isEmpty())
    }

    @Test
    fun `evaluate - city scope narrows a raion alert away from the seat`() {
        val raion = OblastAlert(key = "бердянський", name = "Бердянський район", oblast = "Запорізька область", since = null)
        val scoped = engine.evaluate(
            threats = emptyList(),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis(),
            alerts = listOf(raion),
            focusToken = "Запорізьк",
            focusCityUa = "Запоріжжя",
            cityScope = true
        )
        assertFalse(scoped.focusOblastAlertActive)
        val oblastWide = engine.evaluate(
            threats = emptyList(),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis(),
            alerts = listOf(raion),
            focusToken = "Запорізьк",
            cityScope = false
        )
        assertTrue(oblastWide.focusOblastAlertActive)
    }

    @Test
    fun `evaluate - reason derives from the nearest in-zone threat in the alert oblast`() {
        val now = System.currentTimeMillis()
        val alert = OblastAlert(key = "odesa", name = "Одеська область", oblast = "Одеська", since = "x")
        val threat = NormalizedThreat(
            id = "odesa-threat",
            type = "shahed",
            title = "Test",
            region = "Одеська",
            district = null,
            locality = null,
            lat = 46.48,
            lon = 30.73,
            heading = null,
            bearingDeg = null,
            status = "active",
            advisory = false,
            areaOnly = false,
            confirmations = 1,
            reliability = "high",
            count = 1,
            explanationShort = null,
            speedKmh = 180.0,
            uncertaintyKm = null,
            positionQuality = "confirmed",
            confirmedAtMillis = now - 60_000,
            updatedAtMillis = now - 30_000,
            trail = emptyList()
        )
        val result = engine.evaluate(
            threats = listOf(threat),
            focus = LatLng(46.48, 30.73),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = now,
            alerts = listOf(alert),
            focusToken = "Одеськ"
        )
        assertEquals("odesa-threat", result.reasonThreatId)
        assertTrue(result.officialReason != null)
    }

    @Test
    fun `evaluate - no alerts leaves official outputs empty`() {
        val result = engine.evaluate(
            threats = emptyList(),
            focus = LatLng(userLat, userLng),
            params = params,
            hiddenTypes = emptySet(),
            silencedTypes = emptySet(),
            now = System.currentTimeMillis(),
            alerts = emptyList(),
            focusToken = "Одеськ"
        )
        assertFalse(result.focusOblastAlertActive)
        assertTrue(result.redCities.isEmpty())
        assertNull(result.officialReason)
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
    fun `scoreThreat - unknown reliability scores per spec`() {
        val props = NEPTUN_TYPES["shahed"]!!
        fun score(reliability: String) = engine.scoreThreat(
            threat(id = "r-$reliability", reliability = reliability),
            props, 5.0, null, params.slowRedKm, params.slowYellowKm, System.currentTimeMillis()
        )
        val high = score("high")
        val medium = score("medium")
        val unknown = score("unknown")
        val low = score("low")
        assertEquals(1.0 / 0.7, high / unknown, 1e-9)
        assertEquals(1.0 / 0.8, high / medium, 1e-9)
        assertEquals(1.0 / 0.5, high / low, 1e-9)
    }

    @Test
    fun `scoreThreat - mid and unrecognized reliability normalize via fromApi`() {
        val props = NEPTUN_TYPES["shahed"]!!
        fun score(reliability: String) = engine.scoreThreat(
            threat(id = "r2-$reliability", reliability = reliability),
            props, 5.0, null, params.slowRedKm, params.slowYellowKm, System.currentTimeMillis()
        )
        assertEquals(score("medium"), score("mid"), 1e-12)
        assertEquals(score("unknown"), score("bogus"), 1e-12)
    }

    @Test
    fun `speedCache - evicts the oldest track when over cap`() {
        val props = NEPTUN_TYPES["shahed"]!!
        engine.speedCache.record("old", 1_000L, 50.0, 30.0)
        engine.speedCache.record("old", 301_000L, 50.1, 30.0)
        val before = engine.speedCache.estimateWithSource("old", makeThreat(id = "old", speedKmh = null), props)
        assertEquals(SpeedSource.RECORDED, before!!.second)
        for (i in 0 until 600) {
            engine.speedCache.record("flood-$i", 1_000_000L + i, 51.0, 31.0)
        }
        val after = engine.speedCache.estimateWithSource("old", makeThreat(id = "old", speedKmh = null), props)
        assertNotNull(after)
        assertEquals(SpeedSource.TYPICAL, after!!.second)
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
            threat, LatLng(userLat, userLng), System.currentTimeMillis()
        )
        assertNotNull(proximity)
        assertNotNull(proximity!!.distToUserKm)
        assertNotNull(proximity.etaToUserMin)
    }

    @Test
    fun `computeProximity - null threat returns null`() {
        assertNull(engine.computeProximity(null, LatLng(userLat, userLng), System.currentTimeMillis()))
    }

    @Test
    fun `computeProximity - areaOnly returns null`() {
        val threat = makeThreat(areaOnly = true)
        assertNull(engine.computeProximity(threat, LatLng(userLat, userLng), System.currentTimeMillis()))
    }

    @Test
    fun `computeCityAlerts - oblast-wide red tints every city of that oblast`() {
        // Live NEPTUN shape: Latin key, Cyrillic name, explicit wide flag.
        val alert = OblastAlert(
            key = "donetska", name = "Донецька область", oblast = "Донецька область",
            since = null, wide = true, level = "red"
        )
        val result = engine.computeCityAlerts(listOf(alert))
        val donetskaCities = Cities.ALL
            .filter { CompactOblastBoundaries.canonicalId(Cities.cityOblast[it.nameUa] ?: "") == "donetska" }
        assertTrue("expected donetska cities, got ${donetskaCities.size}", donetskaCities.isNotEmpty())
        for (city in donetskaCities) {
            assertEquals("city ${city.nameUa}", AlertLevel.RED, result[city.nameUa])
        }
        val lvivCities = Cities.ALL
            .filter { CompactOblastBoundaries.canonicalId(Cities.cityOblast[it.nameUa] ?: "") == "lvivska" }
        for (city in lvivCities) {
            assertNull("city ${city.nameUa} must stay untinted", result[city.nameUa])
        }
    }

    @Test
    fun `computeCityAlerts - untagged oblast-wide red tints via name heuristic`() {
        // Live flat shape without explicit wide flag: heuristic must kick in.
        val alert = OblastAlert(
            key = "zaporizka", name = "Запорізька область", oblast = "Запорізька область",
            since = null, wide = null, level = "red"
        )
        val result = engine.computeCityAlerts(listOf(alert))
        val zaporizkaCities = Cities.ALL
            .filter { CompactOblastBoundaries.canonicalId(Cities.cityOblast[it.nameUa] ?: "") == "zaporizka" }
        assertTrue(zaporizkaCities.isNotEmpty())
        for (city in zaporizkaCities) {
            assertEquals("city ${city.nameUa}", AlertLevel.RED, result[city.nameUa])
        }
    }

    @Test
    fun `computeCityAlerts - oblast-wide yellow tints yellow and red wins on overlap`() {
        val yellow = OblastAlert(
            key = "odeska", name = "Одеська область", oblast = "Одеська область",
            since = null, wide = true, level = "yellow"
        )
        val result = engine.computeCityAlerts(listOf(yellow))
        val odeskaCities = Cities.ALL
            .filter { CompactOblastBoundaries.canonicalId(Cities.cityOblast[it.nameUa] ?: "") == "odeska" }
        assertTrue(odeskaCities.isNotEmpty())
        for (city in odeskaCities) {
            assertEquals("city ${city.nameUa}", AlertLevel.YELLOW, result[city.nameUa])
        }

        val red = OblastAlert(
            key = "odeska", name = "Одеська область", oblast = "Одеська область",
            since = null, wide = true, level = "red"
        )
        val mixed = engine.computeCityAlerts(listOf(yellow, red))
        for (city in odeskaCities) {
            assertEquals("city ${city.nameUa}", AlertLevel.RED, mixed[city.nameUa])
        }
    }

    @Test
    fun `computeCityAlerts - raion alert tints only its own oblast cities`() {
        val alert = OblastAlert(
            key = "bakhmutskyi", name = "Бахмутський район", oblast = "Донецька область",
            since = null, wide = false, level = "red"
        )
        val result = engine.computeCityAlerts(listOf(alert))
        assertTrue("expected at least one tinted city", result.isNotEmpty())
        for ((cityName, level) in result) {
            assertEquals(AlertLevel.RED, level)
            assertEquals(
                "city $cityName outside donetska",
                "donetska",
                CompactOblastBoundaries.canonicalId(Cities.cityOblast[cityName] ?: "")
            )
        }
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
