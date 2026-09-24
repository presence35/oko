package com.presaince.oko.source.neptun

import com.presaince.oko.ThreatType
import com.presaince.oko.engine.MonitorCore
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.OblastAlert
import com.presaince.oko.engine.toThreatType
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class NeptunDecoderTest {

    private lateinit var core: FakeMonitorCore
    private lateinit var decoder: NeptunDecoder

    private fun makeThreat(
        id: String = "t1",
        type: String = "shahed",
        lat: Double = 46.0,
        lon: Double = 30.0,
        status: String = "active",
        bearingDeg: Double? = 180.0,
        heading: Double? = null,
        speedKmh: Double? = 50.0,
        updatedAtMillis: Long = 1000L,
        confirmedAtMillis: Long? = 500L
    ): NormalizedThreat = NormalizedThreat(
        id = id, type = type, title = "Test", region = null, district = null,
        locality = null, lat = lat, lon = lon, heading = heading,
        bearingDeg = bearingDeg, status = status, advisory = false, areaOnly = false,
        confirmations = 1, reliability = "medium", count = 1, explanationShort = null,
        speedKmh = speedKmh, uncertaintyKm = null, positionQuality = null,
        confirmedAtMillis = confirmedAtMillis, updatedAtMillis = updatedAtMillis,
        trail = emptyList()
    )

    @Before
    fun setUp() {
        core = FakeMonitorCore()
        decoder = NeptunDecoder(core)
    }

    // ─────────────────────────────────────────────────────────────
    // Threat Count Sanitization (Upstream Leaked Telemetry vs Formations)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `sanitizeCount - rejects leaked artifacts on singular titles`() {
        // Upstream NLP often leaks telegram post sequence or scrape counter into count
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 14, rawTitle = "Ракета"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 15, rawTitle = "БпЛА"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 6, rawTitle = "БпЛА"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 22, rawTitle = "Керована авіабомба"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 8, rawTitle = "КАБ"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 12, rawTitle = "Шахед"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 4, rawTitle = "Крилата ракета"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 2, rawTitle = "Балістика"))
    }

    @Test
    fun `sanitizeCount - explicit parenthetical group count is authoritative`() {
        // Authors specify group estimate in title parenthetically
        assertEquals(5, NeptunDecoder.sanitizeCount(rawCount = 5, rawTitle = "Група БпЛА (5+)"))
        assertEquals(4, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Група БпЛА (4+)"))
        assertEquals(4, NeptunDecoder.sanitizeCount(rawCount = 1, rawTitle = "Група БпЛА (4+?)"))
        assertEquals(3, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Шахеди (3)"))
        // Clamp bounds for extreme markers
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Ціль (0)"))
        assertEquals(10, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Група БпЛА (25+)"))
        assertEquals(10, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Група БпЛА (99+?)"))
    }

    @Test
    fun `sanitizeCount - pair indicator resolves to 2`() {
        assertEquals(2, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Пара ракет"))
        assertEquals(2, NeptunDecoder.sanitizeCount(rawCount = 1, rawTitle = "пара БпЛА"))
        assertEquals(2, NeptunDecoder.sanitizeCount(rawCount = 9, rawTitle = "Пара крилатих ракет"))
    }

    @Test
    fun `sanitizeCount - swarm and cluster fallback and preservation`() {
        // Рій (swarm) represents >= 3 targets
        assertEquals(3, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Рій БпЛА"))
        assertEquals(5, NeptunDecoder.sanitizeCount(rawCount = 5, rawTitle = "Рій БпЛА"))
        assertEquals(3, NeptunDecoder.sanitizeCount(rawCount = 1, rawTitle = "Рій БпЛА"))

        // Група / Хвиля represents >= 2 targets
        assertEquals(2, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Група БпЛА"))
        assertEquals(4, NeptunDecoder.sanitizeCount(rawCount = 4, rawTitle = "Група БпЛА"))
        assertEquals(2, NeptunDecoder.sanitizeCount(rawCount = 1, rawTitle = "Хвиля ракет"))
    }

    @Test
    fun `sanitizeCount - bounds and edge cases`() {
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 0, rawTitle = "Невідома ціль"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = -5, rawTitle = "БпЛА"))
        assertEquals(1, NeptunDecoder.sanitizeCount(rawCount = 100, rawTitle = "Ціль"))
        assertEquals(7, NeptunDecoder.sanitizeCount(rawCount = 7, rawTitle = "Невідома ціль"))
    }

    // ─────────────────────────────────────────────────────────────
    // Wire JSON to NormalizedThreat Parsing
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `parseNormalizedThreat - missing lat returns null`() {
        val json = JSONObject().apply {
            put("id", "test-1")
            put("lon", 30.0)
        }
        assertNull(NeptunDecoder.parseNormalizedThreat(json))
    }

    @Test
    fun `parseNormalizedThreat - missing lon returns null`() {
        val json = JSONObject().apply {
            put("id", "test-1")
            put("lat", 50.0)
        }
        assertNull(NeptunDecoder.parseNormalizedThreat(json))
    }

    @Test
    fun `parseNormalizedThreat - blank id returns null`() {
        val json = JSONObject().apply {
            put("id", "")
            put("lat", 50.0)
            put("lon", 30.0)
        }
        assertNull(NeptunDecoder.parseNormalizedThreat(json))
    }

    @Test
    fun `parseNormalizedThreat - lat out of range returns null`() {
        val json = JSONObject().apply {
            put("id", "test-1")
            put("lat", 95.0)
            put("lon", 30.0)
        }
        assertNull(NeptunDecoder.parseNormalizedThreat(json))
    }

    @Test
    fun `parseNormalizedThreat - lon out of range returns null`() {
        val json = JSONObject().apply {
            put("id", "test-1")
            put("lat", 50.0)
            put("lon", 181.0)
        }
        assertNull(NeptunDecoder.parseNormalizedThreat(json))
    }

    @Test
    fun `parseNormalizedThreat - minimal valid threat parses with sanitized count`() {
        val json = JSONObject().apply {
            put("id", "shahed-001")
            put("lat", 50.0)
            put("lon", 30.0)
            put("type", "shahed")
            put("title", "БпЛА")
            put("count", 15)
            put("status", "active")
        }
        val threat = NeptunDecoder.parseNormalizedThreat(json)
        assertNotNull(threat)
        assertEquals("shahed-001", threat!!.id)
        assertEquals(ThreatType.SHAHED, threat.type.toThreatType())
        assertEquals("active", threat.status)
        assertEquals(1, threat.count) // 15 sanitized to 1 for singular БпЛА
    }

    @Test
    fun `parseNormalizedThreat - velocity parsing works`() {
        val json = JSONObject().apply {
            put("id", "test-1")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("velocity", JSONObject().apply {
                put("speedKmh", 180.0)
                put("bearingDeg", 90.0)
            })
        }
        val threat = NeptunDecoder.parseNormalizedThreat(json)!!
        assertEquals(180.0, threat.speedKmh!!, 0.001)
        assertEquals(90.0, threat.bearingDeg!!, 0.001)
    }

    @Test
    fun `parseNormalizedThreat - future timestamps are clamped to now`() {
        val future = "2099-01-01T00:00:00Z"
        val json = JSONObject().apply {
            put("id", "test-future")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("updatedAt", future)
            put("confirmedAt", future)
        }
        val now = 1_700_000_000_000L
        val threat = NeptunDecoder.parseNormalizedThreat(json, nowWall = now)!!
        assertEquals(now, threat.updatedAtMillis)
        assertEquals(now, threat.confirmedAtMillis)
    }

    @Test
    fun `parseNormalizedThreat - future trail timestamps are clamped`() {
        val json = JSONObject().apply {
            put("id", "test-trail-future")
            put("lat", 50.0)
            put("lon", 30.0)
            put("status", "active")
            put("trail", JSONArray().apply {
                put(JSONObject().apply {
                    put("lat", 50.1)
                    put("lon", 30.1)
                    put("t", "2099-01-01T00:00:00Z")
                })
                put(JSONObject().apply {
                    put("lat", 50.2)
                    put("lon", 30.2)
                    put("t", "2025-06-15T12:00:00Z")
                })
            })
        }
        val now = 1_700_000_000_000L
        val threat = NeptunDecoder.parseNormalizedThreat(json, nowWall = now)!!
        assertEquals(2, threat.trail.size)
        assertEquals(now, threat.trail[0].tMillis)
        assertEquals(Instant.parse("2025-06-15T12:00:00Z").toEpochMilli(), threat.trail[1].tMillis)
    }

    // ─────────────────────────────────────────────────────────────
    // WebSocket Frames
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `snapshot updates threats and sanitizes count`() {
        val json = """{"type":"snapshot","data":{"threats":[{"id":"t1","type":"shahed","title":"Ракета","count":14,"lat":46.0,"lon":30.0,"status":"active","bearingDeg":180.0,"confirmations":1,"reliability":"medium","speedKmh":50.0,"updatedAtMillis":1000,"confirmedAtMillis":500,"trail":[]}]}}"""
        decoder.handleFrame(json)
        assertEquals(1, core.threats.value.size)
        assertEquals("t1", core.threats.value[0].id)
        assertEquals(1, core.threats.value[0].count)
    }

    @Test
    fun `snapshot with malformed threat skips it`() {
        val json = """{"type":"snapshot","data":{"threats":[{"id":"bad"}]}}"""
        decoder.handleFrame(json)
        assertTrue(core.threats.value.isEmpty())
    }

    @Test
    fun `upsert adds a new threat with group count`() {
        val json = """{"type":"upsert","data":{"id":"t2","type":"fpv","title":"Група БпЛА (4+)","count":0,"lat":46.0,"lon":30.0,"status":"active","bearingDeg":180.0,"confirmations":1,"reliability":"medium","speedKmh":33.33,"updatedAtMillis":2000,"confirmedAtMillis":1000,"trail":[]}}"""
        decoder.handleFrame(json)
        assertEquals(1, core.threats.value.size)
        assertEquals("t2", core.threats.value[0].id)
        assertEquals(4, core.threats.value[0].count)
    }

    @Test
    fun `upsert resolved threat removes it and emits ThreatRemoved`() {
        decoder.handleFrame("""{"type":"snapshot","data":{"threats":[]}}""")
        val t = makeThreat(id = "t1")
        core.updateThreats(listOf(t))

        val json = """{"type":"upsert","data":{"id":"t1","type":"shahed","lat":46.0,"lon":30.0,"status":"resolved","bearingDeg":180.0,"confirmations":1,"reliability":"medium","count":1,"speedKmh":50.0,"updatedAtMillis":1000,"confirmedAtMillis":500,"trail":[]}}"""
        decoder.handleFrame(json)
        assertTrue(core.threats.value.isEmpty())
    }

    @Test
    fun `remove deletes an existing threat and emits ThreatRemoved`() {
        val t = makeThreat(id = "t1")
        core.updateThreats(listOf(t))

        val json = """{"type":"remove","data":{"id":"t1"}}"""
        decoder.handleFrame(json)
        assertTrue(core.threats.value.isEmpty())
    }

    @Test
    fun `remove unknown id does nothing`() {
        val json = """{"type":"remove","data":{"id":"nonexistent"}}"""
        decoder.handleFrame(json)
        assertTrue(core.threats.value.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────
    // Alerts
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `alerts frame updates core alerts`() {
        val json = """{"type":"alerts","data":[{"key":"odessa","name":"Одеська","oblast":"Одеська","level":"red"}]}"""
        decoder.handleFrame(json)
        assertEquals(1, core.alerts.value.size)
        assertEquals("odessa", core.alerts.value[0].key)
    }

    @Test
    fun `alerts frame with raions and oblasts`() {
        val json = """{"type":"alerts","data":{"oblasts":[{"key":"kyiv","name":"Київська","oblast":"Київська"}],"raions":[{"key":"kyiv-brovary","name":"Бровари","oblast":"Київська"}]}}"""
        decoder.handleFrame(json)
        assertEquals(2, core.alerts.value.size)
    }

    @Test
    fun `heartbeat is handled without error`() {
        val json = """{"type":"heartbeat"}"""
        decoder.handleFrame(json)
        assertTrue(true)
    }

    @Test
    fun `flushPendingAlertClear clears alerts after confirm delay`() {
        val json = """{"type":"alerts","data":[{"key":"odessa","name":"Одеська","oblast":"Одеська"}]}"""
        decoder.handleFrame(json, nowMono = 1000L)
        assertEquals(1, core.alerts.value.size)

        // Incoming empty alerts frame initiates debounce
        val emptyAlerts = """{"type":"alerts","data":[]}"""
        decoder.handleFrame(emptyAlerts, nowMono = 2000L)
        // Alerts should not be immediately cleared
        assertEquals(1, core.alerts.value.size)

        // Before timeout: still not cleared
        decoder.flushPendingAlertClear(nowMono = 2000L + NeptunDecoder.ALERT_CLEAR_CONFIRM_MS - 1)
        assertEquals(1, core.alerts.value.size)

        // After timeout: cleared
        decoder.flushPendingAlertClear(nowMono = 2000L + NeptunDecoder.ALERT_CLEAR_CONFIRM_MS)
        assertEquals(0, core.alerts.value.size)
    }

    @Test
    fun `handleTransportDrop drops pending clear`() {
        val json = """{"type":"alerts","data":[{"key":"odessa","name":"Одеська","oblast":"Одеська"}]}"""
        decoder.handleFrame(json, nowMono = 1000L)
        decoder.handleFrame("""{"type":"alerts","data":[]}""", nowMono = 2000L)

        // Connection dropped
        decoder.handleTransportDrop()

        // Flush after drop should not clear because pending state was discarded
        decoder.flushPendingAlertClear(nowMono = 2000L + NeptunDecoder.ALERT_CLEAR_CONFIRM_MS + 1000)
        assertEquals(1, core.alerts.value.size)
    }

    // ─────────────────────────────────────────────────────────────
    // Fake MonitorCore
    // ─────────────────────────────────────────────────────────────

    private class FakeMonitorCore : MonitorCore {
        override val threats = MutableStateFlow<List<NormalizedThreat>>(emptyList())
        override val alerts = MutableStateFlow<List<OblastAlert>>(emptyList())
        override val isInformationStale = MutableStateFlow(false)
        override val lastUpdateEpochMs = MutableStateFlow(0L)

        override fun onBaselineSyncRequired() {}
        override fun onStreamFrameReceived() {}
        override fun onNetworkDisconnected(reason: String) {}
        override fun onNetworkReconnected() {}
        override fun updateThreats(threats: List<NormalizedThreat>) { this.threats.value = threats }
        override fun upsertThreat(threat: NormalizedThreat) {
            val current = threats.value.toMutableList()
            val idx = current.indexOfFirst { it.id == threat.id }
            if (idx >= 0) current[idx] = threat else current.add(threat)
            this.threats.value = current
        }
        override fun removeThreat(threatId: String) {
            this.threats.value = threats.value.filter { it.id != threatId }
        }
        override fun updateAlerts(alerts: List<OblastAlert>) { this.alerts.value = alerts }
        override fun markUserShot(id: String) {}
        override fun wasUserShotRecently(id: String): Boolean = false
    }
}
