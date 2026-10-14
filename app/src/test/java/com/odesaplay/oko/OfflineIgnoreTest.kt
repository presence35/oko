package com.odesaplay.oko

import com.odesaplay.oko.service.OfflineIgnore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIgnoreTest {

    @After
    fun tearDown() {
        OfflineIgnore.reset()
    }

    @Test
    fun `fresh process mutes nothing`() {
        assertFalse(OfflineIgnore.isMuted(nowMs = 1_000L))
    }

    @Test
    fun `ignore mutes for the requested duration then lapses`() {
        OfflineIgnore.ignoreFor(30 * 60_000L, nowMs = 1_000L)
        assertTrue(OfflineIgnore.isMuted(nowMs = 1_000L + 29 * 60_000L))
        assertFalse(OfflineIgnore.isMuted(nowMs = 1_000L + 31 * 60_000L))
    }
}
