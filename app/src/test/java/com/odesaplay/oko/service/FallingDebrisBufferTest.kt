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
}
