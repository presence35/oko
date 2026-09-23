package com.presaince.oko.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.presaince.oko.ThreatType
import com.presaince.oko.source.NeptunSource.Companion.NEPTUN_TYPES

class TypeBridgeTest {

    @Test
    fun `fast types tier by ETA`() {
        val fast = setOf(
            ThreatType.CRUISE_MISSILE,
            ThreatType.BALLISTIC,
            ThreatType.KAB,
            ThreatType.AVIATION
        )
        for (type in ThreatType.entries) {
if (type in fast) assertTrue("$type should be fast", isFastType(type, NEPTUN_TYPES))
        else assertFalse("$type should be slow", isFastType(type, NEPTUN_TYPES))
        }
    }

    @Test
    fun `typical speed matches catalog nominal speeds`() {
        assertEquals(850.0, typicalSpeedKmh(ThreatType.CRUISE_MISSILE, NEPTUN_TYPES)!!, 1.0)
        assertEquals(3300.0, typicalSpeedKmh(ThreatType.BALLISTIC, NEPTUN_TYPES)!!, 1.0)
        assertEquals(120.0, typicalSpeedKmh(ThreatType.FPV_LOITERING, NEPTUN_TYPES)!!, 1.0)
        assertEquals(180.0, typicalSpeedKmh(ThreatType.SHAHED, NEPTUN_TYPES)!!, 1.0)
        assertEquals(null, typicalSpeedKmh(ThreatType.UNKNOWN, NEPTUN_TYPES))
    }
}