package com.odesaplay.oko

import com.odesaplay.oko.service.MonitorEvent
import com.odesaplay.oko.service.MonitorLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorLifecycleTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `round trip keeps the cause and the order`() {
        val events = listOf(
            MonitorEvent(now, MonitorEvent.Kind.START),
            MonitorEvent(now + 1, MonitorEvent.Kind.STOP_BY_USER),
            MonitorEvent(now + 2, MonitorEvent.Kind.RESURRECT_BLOCKED, "ForegroundServiceStartNotAllowedException")
        )

        assertEquals(events, MonitorLifecycle.decode(MonitorLifecycle.encode(events)))
    }

    @Test
    fun `a malformed line is dropped, not fatal`() {
        val raw = "garbage\n${now}|START\n${now + 1}|NOT_A_KIND\n${now + 2}|STOP_OS"

        val decoded = MonitorLifecycle.decode(raw)

        assertEquals(
            listOf(
                MonitorEvent(now, MonitorEvent.Kind.START),
                MonitorEvent(now + 2, MonitorEvent.Kind.STOP_OS)
            ),
            decoded
        )
    }

    @Test
    fun `rows older than the retention window are pruned`() {
        val old = MonitorEvent(now - MonitorLifecycle.KEEP_MS - 1, MonitorEvent.Kind.START)
        val recent = MonitorEvent(now - MonitorLifecycle.KEEP_MS + 1, MonitorEvent.Kind.STOP_OS)

        assertEquals(listOf(recent), MonitorLifecycle.prune(listOf(old, recent), now))
    }

    @Test
    fun `the newest events survive the cap`() {
        val existing = (0 until MonitorLifecycle.MAX_EVENTS).map {
            MonitorEvent(now - it, MonitorEvent.Kind.START)
        }
        val newest = MonitorEvent(now + 1, MonitorEvent.Kind.STOP_OS)

        val appended = MonitorLifecycle.append(existing, newest, now + 1)

        assertEquals(MonitorLifecycle.MAX_EVENTS, appended.size)
        assertEquals(newest, appended.last())
        assertTrue(appended.none { it == MonitorEvent(now, MonitorEvent.Kind.START) })
    }
}
