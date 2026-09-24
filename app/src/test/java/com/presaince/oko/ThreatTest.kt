package com.presaince.oko

import org.junit.Assert.*
import org.junit.Test
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.source.neptun.NeptunSource.Companion.NEPTUN_TYPES
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.toThreatType

/**
 * Domain tests for Threat domain model — catalog definitions, flying status,
 * course translation, and MiG descriptors. Wire parsing tests live in [NeptunDecoderTest].
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
        assertEquals("Drone", info.labelEn)
        assertEquals("БпЛА", info.label(AppLanguage.UA))
        assertEquals("Drone", info.label(AppLanguage.EN))
        assertEquals("Drone", info.label(AppLanguage.RU))
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
    fun `flying - reported heading alone is enough`() {
        val threat = makeThreat(
            bearingDeg = null,
            heading = 45.0,
            confirmedAtMillis = System.currentTimeMillis() - 10_000,
            status = "active"
        )
        assertTrue(threat.flying)
    }

    @Test
    fun `flying - updatedAt can anchor when confirmedAt is missing`() {
        val threat = makeThreat(
            bearingDeg = 180.0,
            confirmedAtMillis = null,
            updatedAtMillis = System.currentTimeMillis() - 10_000,
            status = "active"
        )
        assertTrue(threat.flying)
    }

    @Test
    fun `flying - no course and no anchor returns false`() {
        val threat = makeThreat(
            bearingDeg = null,
            heading = null,
            confirmedAtMillis = null,
            updatedAtMillis = null,
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
    // National MiG-31K translation
    // ─────────────────────────────────────────────────────────────

    private val liveMigCourse =
        "Зафіксовано зліт МіГ-31К — носія аеробалістичних ракет «Кинджал». " +
            "Загроза для всієї території України: можливий пуск балістики за лічені хвилини. " +
            "Будьте поблизу укриття."

    @Test
    fun `mig - EN course renders fixed text, never transliteration`() {
        val en = translateCourseAssessment(liveMigCourse, AppLanguage.EN)
        assertEquals(nationalMigCourseText(), en)
        assertFalse(en!!.contains("Zafiksovano", ignoreCase = true))
    }

    @Test
    fun `mig - UA course keeps raw server text`() {
        assertEquals(liveMigCourse, translateCourseAssessment(liveMigCourse, AppLanguage.UA))
    }

    @Test
    fun `mig - RU course follows the EN pipeline until a real RU translation lands`() {
        assertEquals(
            nationalMigCourseText(),
            translateCourseAssessment(liveMigCourse, AppLanguage.RU)
        )
    }

    @Test
    fun `mig - survives rewording that keeps the token`() {
        assertEquals(
            nationalMigCourseText(),
            translateCourseAssessment("Зліт МіГ-31К, загроза по всій країні.", AppLanguage.EN)
        )
    }

    @Test
    fun `mig - isNationalMig ignores simulator title and real localities`() {
        val live = makeThreat(
            type = ThreatType.AVIATION, region = "Загальнодержавна загроза",
            district = "Носій «Кинджал»", explanationShort = liveMigCourse
        )
        assertTrue(isNationalMig(live))
        // Simulator title is already English and carries no descriptors — not a national MiG.
        val sim = makeThreat(type = ThreatType.AVIATION, region = null, explanationShort = null)
        assertFalse(isNationalMig(sim))
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
        updatedAtMillis: Long? = System.currentTimeMillis(),
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