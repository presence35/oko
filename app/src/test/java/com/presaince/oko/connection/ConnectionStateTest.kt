package com.presaince.oko.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `Connected is connected but not degraded or offline`() {
        val cs = ConnectionState.Connected(generation = 0, openedAtMs = now, lastFrameAtMs = now)
        assertTrue(cs.isConnected)
        assertFalse(cs.isDegraded)
        assertFalse(cs.isOffline)
    }

    @Test
    fun `Degraded is connected and degraded but not offline`() {
        val cs = ConnectionState.Degraded(
            generation = 0, openedAtMs = now, lastFrameAtMs = now - 30_000, quietDurationMs = 30_000
        )
        assertTrue(cs.isConnected)
        assertTrue(cs.isDegraded)
        assertFalse(cs.isOffline)
    }

    @Test
    fun `Offline is offline but not connected`() {
        val cs = ConnectionState.Offline(since = now, reconnectStartMillis = now)
        assertFalse(cs.isConnected)
        assertFalse(cs.isDegraded)
        assertTrue(cs.isOffline)
    }

    @Test
    fun `Disconnected is offline`() {
        assertTrue(ConnectionState.Disconnected.isOffline)
        assertFalse(ConnectionState.Disconnected.isConnected)
    }

    @Test
    fun `offlineSinceOrNull returns since for Offline`() {
        val cs = ConnectionState.Offline(since = 12345L, reconnectStartMillis = 12345L)
        assertTrue(cs.offlineSinceOrNull == 12345L)
    }

    @Test
    fun `offlineSinceOrNull returns null for Connected`() {
        val cs = ConnectionState.Connected(generation = 0, openedAtMs = now, lastFrameAtMs = now)
        assertTrue(cs.offlineSinceOrNull == null)
    }

    @Test
    fun `reconnectStartMillisOrZero returns value for Offline`() {
        val cs = ConnectionState.Offline(since = now, reconnectStartMillis = 999L)
        assertTrue(cs.reconnectStartMillisOrZero == 999L)
    }

    @Test
    fun `reconnectStartMillisOrZero returns 0 for Connected`() {
        val cs = ConnectionState.Connected(generation = 0, openedAtMs = now, lastFrameAtMs = now)
        assertTrue(cs.reconnectStartMillisOrZero == 0L)
    }
}
