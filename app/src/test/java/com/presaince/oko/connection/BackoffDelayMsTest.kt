package com.presaince.oko.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffDelayMsTest {

    @Test
    fun `attempt zero with jitter one dot zero returns base backoff`() {
        assertEquals(1000L, ResilientConnectionSupervisor.backoffDelayMs(0, jitter = 1.0))
    }

    @Test
    fun `attempt three with jitter one returns base times eight`() {
        assertEquals(8000L, ResilientConnectionSupervisor.backoffDelayMs(3, jitter = 1.0))
    }

    @Test
    fun `exponential growth caps at MAX_BACKOFF_MS`() {
        val result = ResilientConnectionSupervisor.backoffDelayMs(10, jitter = 1.0)
        assertEquals(30000L, result)
    }

    @Test
    fun `jitter zero point seven five produces scaled delay`() {
        val result = ResilientConnectionSupervisor.backoffDelayMs(2, jitter = 0.75)
        assertEquals(3000L, result)
    }

    @Test
    fun `jitter one point two five produces scaled up delay`() {
        val result = ResilientConnectionSupervisor.backoffDelayMs(2, jitter = 1.25)
        assertEquals(5000L, result)
    }

    @Test
    fun `high attempt clamped to six before exponentiation`() {
        val result = ResilientConnectionSupervisor.backoffDelayMs(100, jitter = 1.0)
        assertEquals(30000L, result)
    }

    @Test
    fun `result always within bounds`() {
        for (attempt in 0..20) {
            for (jitter in listOf(0.5, 0.75, 1.0, 1.25, 2.0)) {
                val result = ResilientConnectionSupervisor.backoffDelayMs(attempt, jitter = jitter)
                assertTrue("attempt=$attempt jitter=$jitter result=$result",
                    result in 1000L..30000L)
            }
        }
    }

    @Test
    fun `result is never zero`() {
        val result = ResilientConnectionSupervisor.backoffDelayMs(0, jitter = 1.0)
        assertTrue(result > 0)
    }
}
