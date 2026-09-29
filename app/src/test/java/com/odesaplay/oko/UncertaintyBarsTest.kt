package com.odesaplay.oko

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The precision bar is at-a-glance info, so distinct magnitudes must not collapse onto the same
 * fill. Values below are what NEPTUN actually reports (a snapshot ran 4, 10, 25 and 70 km): the
 * previous <1/<2/<4/<8 scale drew 7 of 20 live tracks as "2 bars" and the other 13 as "1".
 */
class UncertaintyBarsTest {

    @Test
    fun `one band per magnitude class the feed reports`() {
        // ±10 and ±10.7 are the same class and must share a fill; 4 / 10 / 25 / 70 must not.
        assertTrue(uncertaintyBars(10.0) == uncertaintyBars(10.7))
        val onePerBand = listOf(4.0, 10.0, 25.0, 70.0)
        val bars = onePerBand.map { uncertaintyBars(it) }
        assertTrue("distinct classes must not collapse: $onePerBand -> $bars", bars.distinct().size == onePerBand.size)
        assertTrue(bars.all { it in 1..5 })
    }

    @Test
    fun `tighter is never worse than looser`() {
        var previous = 5
        for (km in listOf(0.5, 1.9, 2.0, 7.9, 8.0, 19.9, 20.0, 39.9, 40.0, 500.0)) {
            val bars = uncertaintyBars(km)
            assertTrue("uncertainty $km must not gain bars (got $bars, previous $previous)", bars <= previous)
            previous = bars
        }
        assertTrue(uncertaintyBars(0.5) > uncertaintyBars(500.0))
    }

    @Test
    fun `bands are the ones the feed lands on`() {
        assertTrue(uncertaintyBars(4.0) >= 4)
        assertTrue(uncertaintyBars(10.0) == 3)
        assertTrue(uncertaintyBars(25.0) == 2)
        assertTrue(uncertaintyBars(70.0) == 1)
    }
}
