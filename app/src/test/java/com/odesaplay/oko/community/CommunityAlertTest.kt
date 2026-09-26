package com.odesaplay.oko.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityAlertTest {

    @Test
    fun testScaledRingCoordinateExtraction() {
        val ring = ScaledRing(
            intArrayOf(
                47659, 34044, // lat: 47.659, lon: 34.044
                47666, 33979
            )
        )
        assertEquals(2, ring.pointCount)
        assertEquals(47.659, ring.getLat(0), 0.0001)
        assertEquals(34.044, ring.getLon(0), 0.0001)
        assertEquals(47.666, ring.getLat(1), 0.0001)
        assertEquals(33.979, ring.getLon(1), 0.0001)

        val points = ring.toPoints()
        assertEquals(2, points.size)
        assertEquals(47.659, points[0].lat, 0.0001)
        assertEquals(34.044, points[0].lon, 0.0001)
    }

    @Test
    fun testScaledRingPointInPolygon() {
        // Square polygon around lat 50.0..51.0, lon 30.0..31.0
        val ring = ScaledRing(
            intArrayOf(
                50000, 30000,
                51000, 30000,
                51000, 31000,
                50000, 31000,
                50000, 30000
            )
        )
        // Inside point
        assertTrue(ring.contains(50.5, 30.5))
        // Outside points
        assertFalse(ring.contains(49.9, 30.5))
        assertFalse(ring.contains(50.5, 31.5))
    }

    @Test
    fun testCompactOblastBoundariesLookup() {
        // Should find Odesa by raw id or stem
        val odesaById = CompactOblastBoundaries.get("odeska")
        assertNotNull(odesaById)
        assertTrue(odesaById!!.pointCount > 50)

        val odesaByStem = CompactOblastBoundaries.get("одеськ")
        assertNotNull(odesaByStem)
        assertEquals(odesaById.pointCount, odesaByStem!!.pointCount)

        // Point inside Odesa city center (approx 46.48, 30.72)
        assertTrue(odesaById.contains(46.48, 30.72))
    }

    @Test
    fun testCompactRaionBoundariesLookup() {
        val nikopol = CompactRaionBoundaries.forKey("Дніпропетровськ", "нікопольський")
        assertNotNull(nikopol)
        assertTrue(nikopol!!.pointCount > 50)

        // Point inside Nikopol city (47.57, 34.40)
        assertTrue(nikopol.contains(47.57, 34.40))

        // All map should contain raions
        assertTrue(CompactRaionBoundaries.all.isNotEmpty())
    }
}
