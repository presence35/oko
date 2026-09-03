package ua.ukrainedrones

import ua.ukrainedrones.engine.NormalizedThreat

import org.junit.Assert.*
import org.junit.Test
import ua.ukrainedrones.engine.inFocusOblast
import ua.ukrainedrones.engine.inOblast
import ua.ukrainedrones.engine.threatBody

class ThreatEvaluatorTest {

    private val userLat = 50.4501
    private val userLng = 30.5234

    // ─────────────────────────────────────────────────────────────
    // inFocusOblast() and inOblast()
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `inFocusOblast - matching region returns true`() {
        val threat = makeThreat(region = "Київська область")
        assertTrue(inFocusOblast(threat, "Київськ"))
    }

    @Test
    fun `inFocusOblast - non-matching region returns false`() {
        val threat = makeThreat(region = "Одеська область")
        assertFalse(inFocusOblast(threat, "Київськ"))
    }

    @Test
    fun `inFocusOblast - null token returns false`() {
        val threat = makeThreat(region = "Київська область")
        assertFalse(inFocusOblast(threat, null))
    }

    @Test
    fun `inOblast - district match works`() {
        assertTrue(inOblast(
            region = "Одеська",
            district = "Київський",
            locality = null,
            token = "Київськ"
        ))
    }

    @Test
    fun `inOblast - locality match works`() {
        assertTrue(inOblast(
            region = "Одеська",
            district = "Одеський",
            locality = "Київське",
            token = "Київськ"
        ))
    }

    // ─────────────────────────────────────────────────────────────
    // threatBody()
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `threatBody - with locality returns label and place`() {
        val threat = makeThreat(
            type = ThreatType.SHAHED,
            locality = "Київ"
        )
        val body = threatBody(threat, AppLanguage.UA)
        assertEquals("БпЛА — Київ", body)
    }

    @Test
    fun `threatBody - EN translates known city`() {
        val threat = makeThreat(
            type = ThreatType.SHAHED,
            locality = "Київ"
        )
        val body = threatBody(threat, AppLanguage.EN)
        assertEquals("UAV — Kyiv", body)
    }

    @Test
    fun `threatBody - no locality returns just label`() {
        val threat = makeThreat(
            type = ThreatType.SHAHED,
            locality = null,
            district = null,
            region = null
        )
        val body = threatBody(threat, AppLanguage.UA)
        assertEquals("БпЛА", body)
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun makeThreat(
        id: String = "test-${System.nanoTime()}",
        type: ThreatType = ThreatType.SHAHED,
        lat: Double = 50.0,
        lon: Double = 30.0,
        region: String? = "Київська область",
        district: String? = null,
        locality: String? = null,
        status: String = "active",
        advisory: Boolean = false,
        areaOnly: Boolean = false
    ): NormalizedThreat = threat(
        id = id,
        type = type,
        title = "Test threat",
        region = region,
        district = district,
        locality = locality,
        lat = lat,
        lon = lon,
        status = status,
        advisory = advisory,
        areaOnly = areaOnly,
        confirmations = 1,
        reliability = "MEDIUM",
        count = 1,
        confirmedAtMillis = System.currentTimeMillis() - 60_000,
        updatedAtMillis = System.currentTimeMillis()
    )
}
