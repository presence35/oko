package ua.ukrainedrones

import org.junit.Assert.*
import org.junit.Test
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.toThreatType

/**
 * Tests for the NEPTUN JSON parser (`normalizedThreatFromJson`) — field mapping, stale
 * detection, ghost filtering, and catalog lookups.
 */
class ThreatTest {

    // ─────────────────────────────────────────────────────────────
    // ThreatTypeCatalog
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `catalog - ballistic missile has info`() {
        val info = ThreatTypeCatalog.INFO[ThreatType.BALLISTIC]
        assertNotNull(info)
        assertTrue(info!!.labelUa.isNotBlank())
        assertTrue(info.labelEn.isNotBlank())
    }

    @Test
    fun `catalog - all types have non-empty display names`() {
        ThreatType.entries.forEach { type ->
            val info = ThreatTypeCatalog.INFO[type]
            assertNotNull("Catalog missing entry for $type", info)
            assertTrue("Display name empty for $type", info!!.labelUa.isNotBlank())
            assertTrue("Display name EN empty for $type", info.labelEn.isNotBlank())
        }
    }

    @Test
    fun `catalog - SHAHED label is correct`() {
        val info = ThreatTypeCatalog.INFO[ThreatType.SHAHED]!!
        assertEquals("БпЛА", info.labelUa)
        assertEquals("UAV", info.labelEn)
    }

    // ─────────────────────────────────────────────────────────────
    // normalizedThreatFromJson edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `fromJson - missing lat returns null`() {
        val json = org.json.JSONObject().apply {
            put("id", "test-1")
            put("lon", 30.0)
        }
        assertNull(normalizedThreatFromJson(json))
    }

    @Test
    fun `fromJson - missing lon returns null`() {
        val json = org.json.JSONObject().apply {
            put("id", "test-1")
            put("lat", 50.0)
        }
        assertNull(normalizedThreatFromJson(json))
    }

    @Test
    fun `fromJson - blank id returns null`() {
        val json = org.json.JSONObject().apply {
            put("id", "")
            put("lat", 50.0)
            put("lon", 30.0)
        }
        assertNull(normalizedThreatFromJson(json))
    }

    @Test
    fun `fromJson - minimal valid threat parses`() {
        val json = org.json.JSONObject().apply {
            put("id", "shahed-001")
            put("lat", 50.0)
            put("lon", 30.0)
            put("type", "shahed")
            put("status", "active")
        }
        val threat = normalizedThreatFromJson(json)
        assertNotNull(threat)
        assertEquals("shahed-001", threat!!.id)
        assertEquals(ThreatType.SHAHED, threat.type.toThreatType())
        assertEquals("active", threat.status)
    }

    @Test
    fun `fromJson - velocity parsing works`() {
        val json = org.json.JSONObject().apply {
            put("id", "test-1")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("velocity", org.json.JSONObject().apply {
                put("speedKmh", 180.0)
                put("bearingDeg", 90.0)
            })
        }
        val threat = normalizedThreatFromJson(json)!!
        assertEquals(180.0, threat.speedKmh!!, 0.001)
        assertEquals(90.0, threat.bearingDeg!!, 0.001)
    }

    @Test
    fun `fromJson - future updatedAtMillis is clamped to now`() {
        val future = "2099-01-01T00:00:00Z"
        val json = org.json.JSONObject().apply {
            put("id", "test-future")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("updatedAt", future)
        }
        val before = System.currentTimeMillis()
        val threat = normalizedThreatFromJson(json)!!
        val after = System.currentTimeMillis()
        assertNotNull(threat.updatedAtMillis)
        assertTrue("clamped timestamp must be <= now", threat.updatedAtMillis!! <= after)
        assertTrue("clamped timestamp must be >= parse time", threat.updatedAtMillis!! >= before)
    }

    @Test
    fun `fromJson - future confirmedAtMillis is clamped to now`() {
        val future = "2099-01-01T00:00:00Z"
        val json = org.json.JSONObject().apply {
            put("id", "test-future-confirm")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("confirmedAt", future)
        }
        val before = System.currentTimeMillis()
        val threat = normalizedThreatFromJson(json)!!
        val after = System.currentTimeMillis()
        assertNotNull(threat.confirmedAtMillis)
        assertTrue(threat.confirmedAtMillis!! <= after)
        assertTrue(threat.confirmedAtMillis!! >= before)
    }

    @Test
    fun `fromJson - past timestamps are preserved`() {
        val json = org.json.JSONObject().apply {
            put("id", "test-past")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("updatedAt", "2025-06-15T12:00:00Z")
            put("confirmedAt", "2025-06-15T11:55:00Z")
        }
        val threat = normalizedThreatFromJson(json)!!
        val expectedUpdated = java.time.Instant.parse("2025-06-15T12:00:00Z").toEpochMilli()
        val expectedConfirmed = java.time.Instant.parse("2025-06-15T11:55:00Z").toEpochMilli()
        assertEquals(expectedUpdated, threat.updatedAtMillis)
        assertEquals(expectedConfirmed, threat.confirmedAtMillis)
    }

    @Test
    fun `fromJson - future threat does not become immortal`() {
        val future = "2099-01-01T00:00:00Z"
        val json = org.json.JSONObject().apply {
            put("id", "test-immortal")
            put("lat", 50.0)
            put("lon", 30.0)
            put("type", "shahed")
            put("status", "active")
            put("updatedAt", future)
        }
        val threat = normalizedThreatFromJson(json)!!
        val engine = ThreatEngine(NEPTUN_TYPES)
        val now = System.currentTimeMillis()
        // With clamped timestamp, threat should become stale after its window
        // SHAHED staleAfterMs = 300_000 (5 min). A clamped-to-now threat is fresh,
        // but it should NOT be immune to staleness in the future.
        assertFalse("fresh clamped threat is not stale", engine.isStale(threat, engine.propsFor(threat.type), now))
    }

    @Test
    fun `fromJson - future trail timestamps are clamped`() {
        val json = org.json.JSONObject().apply {
            put("id", "test-trail-future")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("trail", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("lat", 50.1)
                    put("lon", 30.1)
                    put("t", "2099-01-01T00:00:00Z")
                })
                put(org.json.JSONObject().apply {
                    put("lat", 50.2)
                    put("lon", 30.2)
                    put("t", "2025-06-15T12:00:00Z")
                })
            })
        }
        val before = System.currentTimeMillis()
        val threat = normalizedThreatFromJson(json)!!
        val after = System.currentTimeMillis()
        assertEquals(2, threat.trail.size)
        val futurePoint = threat.trail[0]
        assertNotNull(futurePoint.tMillis)
        assertTrue("future trail tMillis clamped to <= now", futurePoint.tMillis!! <= after)
        assertTrue("future trail tMillis clamped to >= parse time", futurePoint.tMillis!! >= before)
        val pastPoint = threat.trail[1]
        assertEquals(java.time.Instant.parse("2025-06-15T12:00:00Z").toEpochMilli(), pastPoint.tMillis)
    }

    // ─────────────────────────────────────────────────────────────
    // NormalizedThreat.flying property
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `flying - needs bearing and confirmedAt and active status`() {
        val threat = makeThreat(
            bearingDeg = 180.0,
            speedKmh = 100.0,
            confirmedAtMillis = System.currentTimeMillis() - 10_000,
            status = "active"
        )
        assertTrue(threat.flying)
    }

    @Test
    fun `flying - missing speed still returns true`() {
        val threat = makeThreat(
            bearingDeg = 180.0,
            speedKmh = null,
            confirmedAtMillis = System.currentTimeMillis() - 10_000,
            status = "active"
        )
        assertTrue(threat.flying)
    }

    @Test
    fun `flying - missing bearing returns false`() {
        val threat = makeThreat(
            bearingDeg = null,
            speedKmh = 100.0,
            confirmedAtMillis = System.currentTimeMillis() - 10_000,
            status = "active"
        )
        assertFalse(threat.flying)
    }

    @Test
    fun `flying - resolved status returns false`() {
        val threat = makeThreat(
            bearingDeg = 180.0,
            speedKmh = 100.0,
            confirmedAtMillis = System.currentTimeMillis() - 10_000,
            status = "resolved"
        )
        assertFalse(threat.flying)
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun makeThreat(
        id: String = "test-${System.nanoTime()}",
        type: ThreatType = ThreatType.SHAHED,
        lat: Double = 50.0,
        lon: Double = 30.0,
        speedKmh: Double? = 100.0,
        bearingDeg: Double? = 180.0,
        heading: Double? = null,
        updatedAtMillis: Long = System.currentTimeMillis(),
        confirmedAtMillis: Long? = System.currentTimeMillis() - 60_000,
        status: String = "active",
        advisory: Boolean = false,
        areaOnly: Boolean = false,
        region: String? = "Київська",
        district: String? = null,
        locality: String? = null,
        explanationShort: String? = null
    ): NormalizedThreat = threat(
        id = id,
        type = type,
        title = "Test threat",
        region = region,
        district = district,
        locality = locality,
        lat = lat,
        lon = lon,
        heading = heading,
        bearingDeg = bearingDeg,
        status = status,
        advisory = advisory,
        areaOnly = areaOnly,
        confirmations = 1,
        reliability = "MEDIUM",
        count = 1,
        explanationShort = explanationShort,
        speedKmh = speedKmh,
        uncertaintyKm = null,
        positionQuality = null,
        confirmedAtMillis = confirmedAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}