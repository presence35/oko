package com.odesaplay.oko.engine

import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceTest {

    @Test
    fun `destinationPoint round-trips the distance`() {
        for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            val d = destinationPoint(50.0, 30.0, 5_000.0, bearing)
            assertEquals("bearing=$bearing", 5_000.0, distanceHaversine(50.0, 30.0, d.lat, d.lon), 1.0)
        }
    }

    @Test
    fun `destinationPoint round-trips the bearing`() {
        for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 270.0)) {
            val d = destinationPoint(50.0, 30.0, 5_000.0, bearing)
            val back = bearingHaversine(50.0, 30.0, d.lat, d.lon)
            val delta = min(abs(back - bearing), 360.0 - abs(back - bearing))
            assertTrue("bearing=$bearing back=$back", delta < 0.5)
        }
    }

    @Test
    fun `north bearing increases latitude`() {
        val d = destinationPoint(50.0, 30.0, 10_000.0, 0.0)
        assertTrue(d.lat > 50.0)
        assertEquals(30.0, d.lon, 0.05)
    }

    @Test
    fun `east bearing increases longitude`() {
        val d = destinationPoint(50.0, 30.0, 10_000.0, 90.0)
        assertTrue(d.lon > 30.0)
        assertEquals(50.0, d.lat, 0.05)
    }

    @Test
    fun `flat and haversine distances agree at short range`() {
        val flat = distanceFlat(50.0, 30.0, 50.1, 30.1)
        val hav = distanceHaversine(50.0, 30.0, 50.1, 30.1)
        assertEquals(hav, flat, hav * 0.02)
    }

    @Test
    fun `bearingFlat matches cardinal directions`() {
        assertEquals(0.0, bearingFlat(50.0, 30.0, 51.0, 30.0), 0.5)
        assertEquals(90.0, bearingFlat(50.0, 30.0, 50.0, 31.0), 0.5)
        assertEquals(180.0, bearingFlat(50.0, 30.0, 49.0, 30.0), 0.5)
        assertEquals(270.0, bearingFlat(50.0, 30.0, 50.0, 29.0), 0.5)
    }
}
