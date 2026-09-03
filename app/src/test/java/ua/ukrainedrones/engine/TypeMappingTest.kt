package ua.ukrainedrones.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.ukrainedrones.ThreatType

class TypeMappingTest {

    @Test
    fun `all ThreatType values map to engine strings`() {
        for (type in ThreatType.entries) {
            val engine = type.toEngineString()
            assertNotNull("Null mapping for $type", engine)
            assertTrue("Empty mapping for $type", engine.isNotEmpty())
        }
    }

    @Test
    fun `round-trip ThreatType to engine string`() {
        assertEquals("shahed", ThreatType.SHAHED.toEngineString())
        assertEquals("fpv", ThreatType.FPV_LOITERING.toEngineString())
        assertEquals("cruise", ThreatType.CRUISE_MISSILE.toEngineString())
        assertEquals("ballistic", ThreatType.BALLISTIC.toEngineString())
        assertEquals("kab", ThreatType.KAB.toEngineString())
        assertEquals("aviation", ThreatType.AVIATION.toEngineString())
        assertEquals("recon", ThreatType.RECON.toEngineString())
        assertEquals("unknown", ThreatType.UNKNOWN.toEngineString())
    }

    @Test
    fun `engine string round-trips back to the same ThreatType`() {
        for (type in ThreatType.entries) {
            val engine = type.toEngineString()
            assertEquals("round-trip failed for $type", type, engine.toThreatType())
        }
    }

    @Test
    fun `threatTypeInfoByString resolves known engine strings`() {
        assertNotNull(threatTypeInfoByString("shahed"))
        assertNotNull(threatTypeInfoByString("ballistic"))
        assertNotNull(threatTypeInfoByString("unknown"))
    }
}