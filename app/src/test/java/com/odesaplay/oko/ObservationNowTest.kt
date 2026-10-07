package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The combine re-fires on every input (threat/alert snapshot, location, prefs) while nowFlow
 * advances only on the delay loop, which Doze suspends. The evaluation must therefore observe
 * the wall clock, never the tick value — a 6-minute alarm closed on a doze-stale tick measured
 * 3 minutes (onset 22:32:36, all-clear snapshot evaluated at tick 22:36:01 although it arrived
 * 22:38:42), and every staleness/TTL decision on that tick aged from the same frozen clock.
 */
class ObservationNowTest {

    @Test
    fun `a fresh tick observes the wall clock`() {
        val wall = 1791401922083L
        val observed = AlertService.observationNow(wall - 12, wall)
        assertTrue(observed in (wall - 12)..wall)
    }

    @Test
    fun `a doze-stale tick observes the wall clock, not the frozen tick`() {
        // Field values: tick loop asleep since 22:36:01, alerts snapshot arriving 22:38:42.
        val tickNow = 1791401761059L
        val wall = 1791401922083L
        assertEquals(wall, AlertService.observationNow(tickNow, wall))
    }

    @Test
    fun `a backward wall jump never moves observation backward`() {
        val tickNow = 1791401922083L
        assertEquals(tickNow, AlertService.observationNow(tickNow, tickNow - 60_000L))
    }
}
