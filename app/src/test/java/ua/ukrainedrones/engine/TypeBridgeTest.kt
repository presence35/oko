package ua.ukrainedrones.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.ukrainedrones.ThreatType

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
            if (type in fast) assertTrue("$type should be fast", isFastType(type))
            else assertFalse("$type should be slow", isFastType(type))
        }
    }

    @Test
    fun `typical speed matches catalog nominal speeds`() {
        assertEquals(850.0, typicalSpeedKmh(ThreatType.CRUISE_MISSILE)!!, 1.0)
        assertEquals(3300.0, typicalSpeedKmh(ThreatType.BALLISTIC)!!, 1.0)
        assertEquals(120.0, typicalSpeedKmh(ThreatType.FPV_LOITERING)!!, 1.0)
        assertEquals(180.0, typicalSpeedKmh(ThreatType.SHAHED)!!, 1.0)
        assertEquals(null, typicalSpeedKmh(ThreatType.UNKNOWN))
    }
}