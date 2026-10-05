package com.odesaplay.oko

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReconnectLogTest {

    @Before
    fun setUp() {
        ReconnectLog.clear()
    }

    @After
    fun tearDown() {
        ReconnectLog.clear()
    }

    @Test
    fun `scheduled attempt is recorded with a future deadline`() {
        ReconnectLog.recordScheduled(attemptNo = 2, delayMs = 5_000, networkValidated = true)

        val entry = ReconnectLog.entries.value.single()
        assertEquals(2, entry.attemptNo)
        assertEquals(5_000L, entry.delayMs)
        assertTrue(entry.networkValidated)
        assertEquals(entry.atMillis + 5_000, entry.scheduledAt)
    }

    @Test
    fun `failure attaches to the pending attempt`() {
        ReconnectLog.recordScheduled(attemptNo = 1, delayMs = 1_000, networkValidated = false)
        ReconnectLog.recordFailure("timeout", attemptNo = 1)

        val entry = ReconnectLog.entries.value.single()
        assertEquals("timeout", entry.error)
        assertEquals(1, entry.attemptNo)
    }

    @Test
    fun `failure without a pending attempt is its own entry`() {
        ReconnectLog.recordFailure("boom", attemptNo = 3)

        val entry = ReconnectLog.entries.value.single()
        assertEquals("boom", entry.error)
        assertEquals(3, entry.attemptNo)
        assertEquals(0L, entry.delayMs)
    }

    @Test
    fun `failure for a different attempt number is a new entry`() {
        ReconnectLog.recordScheduled(attemptNo = 1, delayMs = 1_000, networkValidated = true)
        ReconnectLog.recordFailure("late", attemptNo = 2)

        assertEquals(2, ReconnectLog.entries.value.size)
    }

    @Test
    fun `log keeps only the most recent fifty entries`() {
        repeat(60) { i -> ReconnectLog.recordFailure("e$i", attemptNo = i) }

        assertEquals(50, ReconnectLog.entries.value.size)
        assertEquals(10, ReconnectLog.entries.value.first().attemptNo)
        assertEquals(59, ReconnectLog.entries.value.last().attemptNo)
    }

    @Test
    fun `success clears the log`() {
        ReconnectLog.recordScheduled(attemptNo = 1, delayMs = 1_000, networkValidated = true)
        ReconnectLog.recordSuccess()

        assertTrue(ReconnectLog.entries.value.isEmpty())
    }

    @Test
    fun `clear empties the log`() {
        ReconnectLog.recordFailure("x", attemptNo = 1)
        ReconnectLog.clear()

        assertTrue(ReconnectLog.entries.value.isEmpty())
    }
}
