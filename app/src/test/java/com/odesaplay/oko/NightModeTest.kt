package com.odesaplay.oko

import com.odesaplay.oko.engine.ZoneParams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NightModeTest {

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `disabled night mode is never active`() {
        val config = NightConfig(enabled = false, startMin = 22 * 60, endMin = 7 * 60)
        assertFalse(isNightActive(config, at(23, 0)))
        assertFalse(isNightActive(config, at(3, 0)))
    }

    @Test
    fun `equal start and end means never active`() {
        val config = NightConfig(enabled = true, startMin = 12 * 60, endMin = 12 * 60)
        assertFalse(isNightActive(config, at(12, 0)))
    }

    @Test
    fun `same-day window is active only inside it`() {
        val config = NightConfig(enabled = true, startMin = 7 * 60, endMin = 22 * 60)
        assertFalse(isNightActive(config, at(6, 59)))
        assertTrue(isNightActive(config, at(7, 0)))
        assertTrue(isNightActive(config, at(15, 30)))
        assertFalse(isNightActive(config, at(22, 0)))
        assertFalse(isNightActive(config, at(23, 59)))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        val config = NightConfig(enabled = true, startMin = 22 * 60, endMin = 7 * 60)
        assertFalse(isNightActive(config, at(21, 59)))
        assertTrue(isNightActive(config, at(22, 0)))
        assertTrue(isNightActive(config, at(23, 59)))
        assertTrue(isNightActive(config, at(0, 0)))
        assertTrue(isNightActive(config, at(3, 0)))
        assertFalse(isNightActive(config, at(7, 0)))
        assertFalse(isNightActive(config, at(12, 0)))
    }

    @Test
    fun `duration counts the wrap, and an equal pair is no window at all`() {
        assertEquals(540, nightWindowDurationMin(22 * 60, 7 * 60))
        assertEquals(900, nightWindowDurationMin(7 * 60, 22 * 60))
        assertEquals(1320, nightWindowDurationMin(12 * 60, 10 * 60))
        assertEquals(0, nightWindowDurationMin(12 * 60, 12 * 60))
    }

    @Test
    fun `effective params pick night values only while active and enabled`() {
        val day = ZoneParams(20, 50, 5, 20)
        val night = NightZones(10, 30, 3, 10, slowRedArmed = false, slowYellowArmed = true, fastRedArmed = true, fastYellowArmed = false)
        assertEquals(day, effectiveZoneParams(day, night, useNightZones = true, nightActive = false))
        assertEquals(day, effectiveZoneParams(day, night, useNightZones = false, nightActive = true))
        val effective = effectiveZoneParams(day, night, useNightZones = true, nightActive = true)
        assertEquals(10, effective.slowRedKm)
        assertEquals(30, effective.slowYellowKm)
        assertEquals(3, effective.fastRedMin)
        assertEquals(10, effective.fastYellowMin)
    }

    @Test
    fun `effective armed picks night bells only while active and enabled`() {
        val night = NightZones(10, 30, 3, 10,
            slowRedArmed = false, slowYellowArmed = false,
            fastRedArmed = false, fastYellowArmed = false)
        val day = ZoneArmed(true, true, true, true)
        val muted = ZoneArmed(false, false, false, false)
        assertEquals(day, effectiveArmed(day, night, true, false))
        assertEquals(muted, effectiveArmed(day, night, true, true))
        assertEquals(day, effectiveArmed(day, night, false, true))
    }

    @Test
    fun `an unset night policy follows the day value inside the window`() {
        // The reported bug, as a regression: "city level" on, window active, no night choice made ->
        // the day value must survive the window. Before this, every night policy key carried its own
        // hardcoded default, and the scope defaulted to false, so the whole oblast rang at night.
        assertTrue(effectiveNightPolicy(day = true, night = null, nightActive = true))
        assertFalse(effectiveNightPolicy(day = false, night = null, nightActive = true))
        assertTrue(effectiveNightPolicy(day = true, night = null, nightActive = false))
        assertFalse(effectiveNightPolicy(day = false, night = null, nightActive = false))
    }

    @Test
    fun `a night choice wins inside the window and only there`() {
        assertFalse(effectiveNightPolicy(day = true, night = false, nightActive = true))
        assertTrue(effectiveNightPolicy(day = false, night = true, nightActive = true))
        assertTrue(effectiveNightPolicy(day = true, night = false, nightActive = false))
        assertFalse(effectiveNightPolicy(day = false, night = true, nightActive = false))
    }

    @Test
    fun `an unset night choice resolves to the day value when written`() {
        // What a preset or the sleep snapshot states for a key the user never set.
        assertTrue(nightOverrideOr(null, true))
        assertFalse(nightOverrideOr(null, false))
        assertTrue(nightOverrideOr(true, false))
        assertFalse(nightOverrideOr(false, true))
    }

    @Test
    fun `night sleep preset round-trips all nine flags`() {
        val preset = NightSleepPreset(
            useCustomZones = true,
            slowRedArmed = false, slowYellowArmed = true,
            fastRedArmed = false, fastYellowArmed = true,
            zoneSirenOverride = true, officialSirenOverride = null,
            officialRed = false, officialYellow = true
        )
        assertEquals(preset, NightSleepPreset.decode(preset.encode()))
    }

    @Test
    fun `night sleep preset decode rejects malformed input`() {
        assertNull(NightSleepPreset.decode(null))
        assertNull(NightSleepPreset.decode(""))
        assertNull(NightSleepPreset.decode("101"))
        assertNull(NightSleepPreset.decode("101010102"))
        assertNotNull(NightSleepPreset.decode("10000ii1i"))
    }

    @Test
    fun `night sleep preset muting needs custom zones and every alert off`() {
        assertTrue(NightSleepPreset.MUTED.isSilent)
        assertTrue(NightSleepPreset.MUTED.isMuted)
        assertFalse(NightSleepPreset.MUTED.copy(useCustomZones = false).isMuted)
        assertFalse(NightSleepPreset.MUTED.copy(officialYellow = true).isSilent)
        assertFalse(NightSleepPreset.MUTED.copy(fastRedArmed = true).isMuted)
        assertEquals(NightSleepPreset.MUTED, NightSleepPreset.decode(NightSleepPreset.MUTED.encode()))
    }
}