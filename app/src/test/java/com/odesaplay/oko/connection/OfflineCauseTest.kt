package com.odesaplay.oko.connection

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineCause.from] is the one decision that separates "retrying will help" from "retrying is
 * useless", and the device report that motivated it was a Retry button that provably could never
 * work being shown as if it could.
 *
 * The throwable arrives nested — OkHttp wraps, the platform wraps again — so the chain walk is the
 * behaviour under test, not the bare UnknownHostException.
 */
class OfflineCauseTest {

    @Test
    fun `a bare unknown host is a DNS failure naming the host`() {
        assertEquals(
            OfflineCause.DnsFailure("neptun.in.ua"),
            OfflineCause.from(UnknownHostException("neptun.in.ua"), "neptun.in.ua")
        )
    }

    @Test
    fun `a wrapped unknown host is still a DNS failure`() {
        val wrapped = IOException("connect failed", UnknownHostException("h"))
        assertTrue(OfflineCause.from(wrapped, "h") is OfflineCause.DnsFailure)
    }

    @Test
    fun `a double-wrapped unknown host is still a DNS failure`() {
        val wrapped = RuntimeException("outer", IOException("middle", UnknownHostException("h")))
        assertTrue(OfflineCause.from(wrapped, "h") is OfflineCause.DnsFailure)
    }

    @Test
    fun `a timeout is a socket failure, so retrying still means something`() {
        val cause = OfflineCause.from(SocketTimeoutException("timeout"), "h")
        assertTrue(cause is OfflineCause.SocketFailure)
    }

    @Test
    fun `a plain IO failure is a socket failure`() {
        val cause = OfflineCause.from(IOException("connection reset"), "h")
        assertEquals("connection reset", (cause as OfflineCause.SocketFailure).message)
    }

    @Test
    fun `no throwable is a socket failure with no message, never a crash`() {
        assertEquals(OfflineCause.SocketFailure(null), OfflineCause.from(null, "h"))
    }

    /** A cause chain that loops must not spin the classifier forever. */
    @Test
    fun `a cyclic cause chain terminates`() {
        val a = IOException("a")
        val b = IOException("b", a)
        assertTrue(OfflineCause.from(a, "h") is OfflineCause.SocketFailure)
        assertTrue(OfflineCause.from(b, "h") is OfflineCause.SocketFailure)
    }

    @Test
    fun `only Offline carries a cause`() {
        assertEquals(
            OfflineCause.SilenceTimeout,
            ConnectionState.Offline(0, 0, OfflineCause.SilenceTimeout).offlineCauseOrNull
        )
        assertNull(ConnectionState.Disconnected.offlineCauseOrNull)
        assertNull(ConnectionState.Connected(1, 0, 0).offlineCauseOrNull)
        assertNull(ConnectionState.Degraded(1, 0, 0, 0).offlineCauseOrNull)
    }
}