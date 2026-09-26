package com.odesaplay.oko.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicTest {

    @Test
    fun `default provider returns non-negative value`() {
        val result = Monotonic.now()
        assertTrue(result >= 0)
    }

    @Test
    fun `custom provider returns deterministic value`() {
        Monotonic.nowProvider = { 1000L }
        assertEquals(1000L, Monotonic.now())
        assertEquals(1000L, Monotonic.now())
        assertEquals(1000L, Monotonic.now())
    }

    @Test
    fun `custom provider increments deterministically`() {
        var tick = 500L
        Monotonic.nowProvider = { tick += 10; tick - 10 }
        assertEquals(500L, Monotonic.now())
        assertEquals(510L, Monotonic.now())
    }

    @Test
    fun `now is monotonically non-decreasing with fixed provider`() {
        Monotonic.nowProvider = { 42L }
        assertEquals(42L, Monotonic.now())
        assertEquals(42L, Monotonic.now())
    }

    @Test
    fun `custom provider is not tied to wall clock`() {
        Monotonic.nowProvider = { 999_999_999L }
        assertEquals(999_999_999L, Monotonic.now())
    }

    @Test
    fun `reset provider works`() {
        Monotonic.nowProvider = { 0L }
        assertEquals(0L, Monotonic.now())
        Monotonic.nowProvider = { 42L }
        assertEquals(42L, Monotonic.now())
    }
}
