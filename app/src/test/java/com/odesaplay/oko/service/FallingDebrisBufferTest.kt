package com.odesaplay.oko.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FallingDebrisBufferTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    fun `abort stops the countdown and skips completion`() {
        val s = scope()
        try {
            var completed = false
            val buf = FallingDebrisBuffer(s, onCompleted = { completed = true })
            buf.start(60)
            assertTrue(buf.isRunning)
            buf.abort()
            assertFalse(buf.isRunning)
            Thread.sleep(1200)
            assertFalse(completed)
        } finally {
            s.cancel()
        }
    }

    @Test
    fun `one second countdown ticks once then completes`() {
        val s = scope()
        try {
            val ticks = AtomicInteger(0)
            val done = CountDownLatch(1)
            FallingDebrisBuffer(s, onTick = { ticks.incrementAndGet() }, onCompleted = { done.countDown() })
                .start(1)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, ticks.get())
        } finally {
            s.cancel()
        }
    }

    @Test
    fun `restart cancels the previous countdown`() {
        val s = scope()
        try {
            val completions = AtomicInteger(0)
            val buf = FallingDebrisBuffer(s, onCompleted = { completions.incrementAndGet() })
            buf.start(60)
            buf.start(0)
            Thread.sleep(1200)
            assertEquals(1, completions.get())
            assertFalse(buf.isRunning)
        } finally {
            s.cancel()
        }
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
