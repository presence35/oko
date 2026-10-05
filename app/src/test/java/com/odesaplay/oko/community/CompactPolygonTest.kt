package com.odesaplay.oko.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPolygonTest {

    private fun square(
        minLat: Int = 50_000,
        maxLat: Int = 51_000,
        minLon: Int = 30_000,
        maxLon: Int = 31_000
    ) = ScaledRing(
        intArrayOf(
            minLat, minLon,
            minLat, maxLon,
            maxLat, maxLon,
            maxLat, minLon
        )
    )

    @Test
    fun `ring exposes its point count and order`() {
        val ring = square()
        assertEquals(4, ring.pointCount)
        assertEquals(LatLon(50.0, 30.0), ring.getPoint(0))
        assertEquals(LatLon(51.0, 31.0), ring.getPoint(2))
    }

    @Test
    fun `point inside the ring is contained`() {
        assertTrue(square().contains(50.5, 30.5))
    }

    @Test
    fun `points outside the ring are not contained`() {
        val ring = square()
        assertFalse(ring.contains(49.9, 30.5))
        assertFalse(ring.contains(51.1, 30.5))
        assertFalse(ring.contains(50.5, 29.9))
        assertFalse(ring.contains(50.5, 31.1))
    }

    @Test
    fun `degenerate ring never contains`() {
        assertFalse(ScaledRing(intArrayOf(50_000, 30_000, 50_000, 31_000)).contains(50.5, 30.5))
    }

    @Test
    fun `bounding box spans the ring`() {
        assertEquals(GeoBounds(50.0, 51.0, 30.0, 31.0), CompactPolygon(square()).boundingBox())
    }

    @Test
    fun `empty polygon has no bounding box`() {
        assertNull(CompactPolygon(emptyList()).boundingBox())
    }

    @Test
    fun `multi ring polygon contains a point in any ring`() {
        val left = square(minLon = 30_000, maxLon = 30_100)
        val right = square(minLon = 40_000, maxLon = 40_100)
        val poly = CompactPolygon(listOf(left, right))
        assertTrue(poly.contains(50.5, 30.05))
        assertTrue(poly.contains(50.5, 40.05))
        assertFalse(poly.contains(50.5, 35.0))
    }

    @Test
    fun `multi ring bounding box spans every ring`() {
        val left = square(minLon = 30_000, maxLon = 30_100)
        val right = square(minLat = 60_000, maxLat = 61_000, minLon = 40_000, maxLon = 40_100)
        val box = CompactPolygon(listOf(left, right)).boundingBox()!!
        assertEquals(50.0, box.minLat, 0.0)
        assertEquals(61.0, box.maxLat, 0.0)
        assertEquals(30.0, box.minLon, 0.0)
        assertEquals(40.1, box.maxLon, 0.0)
    }

    @Test
    fun `toPoints preserves ring order`() {
        val pts = CompactPolygon(square()).toPoints().single()
        assertEquals(
            listOf(LatLon(50.0, 30.0), LatLon(50.0, 31.0), LatLon(51.0, 31.0), LatLon(51.0, 30.0)),
            pts
        )
    }
}
