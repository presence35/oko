package com.odesaplay.oko.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FallingDebrisBufferTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A buffer on virtual time: [nowMs] reads the test scheduler's clock, so the deadline expires
     * when the test says so rather than when a wall clock does. The job goes in [backgroundScope]
     * because the countdown loop never completes on its own — it is cancelled when the test ends.
     */
    private fun TestScope.virtualBuffer(
        nowMs: () -> Long = { testScheduler.currentTime },
        onTick: ((Int) -> Unit)? = null,
        onCompleted: () -> Unit
    ) = FallingDebrisBuffer(backgroundScope, nowMs = nowMs, onTick = onTick, onCompleted = onCompleted)

    @Test
    fun `zero duration completes immediately without running`() {
        val s = scope()
        try {
            var completed = false
            var ticks = 0
            FallingDebrisBuffer(s, onTick = { ticks++ }, onCompleted = { completed = true })
                .start(0)
            assertTrue(completed)
            assertEquals(0, ticks)
        } finally {
            s.cancel()
        }
    }

    @Test
    fun `abort stops the countdown and skips completion`() = runTest {
        var completed = false
        val buf = virtualBuffer(onCompleted = { completed = true })
        buf.start(60)
        runCurrent()
        assertTrue(buf.isRunning)

        buf.abort()
        assertFalse(buf.isRunning)
        // Two virtual seconds: long enough that a surviving job would have ticked and completed.
        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(completed)
    }

    @Test
    fun `one second countdown ticks once then completes`() = runTest {
        val ticks = AtomicInteger(0)
        var completed = false
        virtualBuffer(onTick = { ticks.incrementAndGet() }, onCompleted = { completed = true })
            .start(1)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, ticks.get())
        assertTrue(completed)
    }

    @Test
    fun `restart cancels the previous countdown`() = runTest {
        val completions = AtomicInteger(0)
        val buf = virtualBuffer(onCompleted = { completions.incrementAndGet() })
        buf.start(60)
        runCurrent()
        buf.start(0)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, completions.get())
        assertFalse(buf.isRunning)
    }

    @Test
    fun `lowering the setting shortens a running countdown`() {
        // 10-minute countdown, 4 minutes in, slider moved to 4: the report was a countdown that
        // jumped back to a full 4 minutes instead of continuing from where it was.
        val s = scope()
        try {
            var clock = 0L
            val buf = FallingDebrisBuffer(s, nowMs = { clock }, onCompleted = {})
            buf.start(600)
            clock = 240_000L
            assertEquals(360, buf.remainingAt(clock))
            buf.shrinkTo(240)
            assertEquals(240, buf.remainingAt(clock))
        } finally {
            s.cancel()
        }
    }

    @Test
    fun `raising the setting never extends a running countdown`() {
        val s = scope()
        try {
            var clock = 0L
            val buf = FallingDebrisBuffer(s, nowMs = { clock }, onCompleted = {})
            buf.start(600)
            clock = 240_000L
            buf.shrinkTo(240)
            clock = 300_000L
            buf.shrinkTo(600)
            assertEquals(180, buf.remainingAt(clock))
        } finally {
            s.cancel()
        }
    }

    @Test
    fun `the remaining time comes from the clock, so a suspended process resumes truthfully`() {
        // A `for (n downTo 1) delay(1000)` loop freezes in doze and reports a stale number.
        val s = scope()
        try {
            var clock = 0L
            val buf = FallingDebrisBuffer(s, nowMs = { clock }, onCompleted = {})
            buf.start(600)
            assertEquals(600, buf.remainingAt(clock))
            clock = 3_600_000L
            assertEquals(0, buf.remainingAt(clock))
        } finally {
            s.cancel()
        }
    }
}
