package com.presaince.oko.engine

import com.presaince.oko.community.CompactOblastBoundaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OblastIdentityTest {

    @Test
    fun `canonical resolver accepts ids, stems and full names, but no substrings`() {
        assertEquals("luhanska", CompactOblastBoundaries.canonicalId("луганськ"))
        assertEquals("luhanska", CompactOblastBoundaries.canonicalId("Луганська область"))
        assertEquals("luhanska", CompactOblastBoundaries.canonicalId("luhanska"))
        assertEquals("zaporizka", CompactOblastBoundaries.canonicalId("Запорізька область"))
        assertEquals("kyivska", CompactOblastBoundaries.canonicalId("м. київ"))
        assertEquals("kyivska", CompactOblastBoundaries.canonicalId("Київ"))
        // A raion adjective must never resolve to a parent oblast.
        assertNull(CompactOblastBoundaries.canonicalId("Дніпровський"))
        assertNull(CompactOblastBoundaries.canonicalId("Нова Каховка"))
        assertNull(CompactOblastBoundaries.canonicalId("Atlantis"))
    }

    @Test
    fun `sameAlertRegion merges Crimea and Sevastopol only`() {
        assertTrue(sameAlertRegion("krym", "krym"))
        assertTrue(sameAlertRegion("krym", "sevastopol"))
        assertTrue(sameAlertRegion("sevastopol", "krym"))
        assertFalse(sameAlertRegion("krym", "odeska"))
        assertFalse(sameAlertRegion(null, "krym"))
        assertFalse(sameAlertRegion("krym", null))
    }
}
