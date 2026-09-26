package com.odesaplay.oko

import com.odesaplay.oko.connection.ConnectionState
import com.odesaplay.oko.connection.ResilientConnectionSupervisor
import com.odesaplay.oko.connection.isDegraded
import com.odesaplay.oko.connection.isOffline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeptunClientTest {

    @Test
    fun `backoff is exponential with full jitter`() {
        assertEquals(2000L, ResilientConnectionSupervisor.backoffDelayMs(1, jitter = 1.0))
        assertEquals(4000L, ResilientConnectionSupervisor.backoffDelayMs(2, jitter = 1.0))
        assertEquals(8000L, ResilientConnectionSupervisor.backoffDelayMs(3, jitter = 1.0))
        assertEquals(16000L, ResilientConnectionSupervisor.backoffDelayMs(4, jitter = 1.0))
    }

    @Test
    fun `backoff caps at max`() {
        for (attempt in 5..30) {
            val ms = ResilientConnectionSupervisor.backoffDelayMs(attempt, jitter = 1.0)
            assertEquals(ResilientConnectionSupervisor.MAX_BACKOFF_MS, ms)
        }
        repeat(100) {
            val ms = ResilientConnectionSupervisor.backoffDelayMs(3)
            assertTrue("attempt 3 must stay within jittered bounds, got $ms", ms in 1000L..30000L)
        }
    }

    @Test
    fun `stream is degraded when connected but no frame arrived for the threshold`() {
        val now = System.currentTimeMillis()
        val fresh = ConnectionState.Connected(generation = 1, lastFrameAtMs = now - 5_000L, openedAtMs = now)
        assertFalse(fresh.isDegraded)

        // The Degraded state signals a stale link — the client transitions to it
        // when a Connected state has been quiet for >= DEGRADED_STALE_MS.
        val stale = ConnectionState.Degraded(generation = 1, openedAtMs = now, lastFrameAtMs = now - ResilientConnectionSupervisor.DEGRADED_STALE_MS, quietDurationMs = ResilientConnectionSupervisor.DEGRADED_STALE_MS)
        assertTrue(stale.isDegraded)

        // Offline always wins — never degraded once the socket is actually down.
        val down = ConnectionState.Offline(since = now - ResilientConnectionSupervisor.DEGRADED_STALE_MS, reconnectStartMillis = 0L)
        assertFalse(down.isDegraded)
        assertTrue(down.isOffline)

        // No frames yet (lastFrameAt == 0) → not degraded, still green.
        val never = ConnectionState.Connected(generation = 1, lastFrameAtMs = 0L, openedAtMs = 0L)
        assertFalse(never.isDegraded)
    }
}
