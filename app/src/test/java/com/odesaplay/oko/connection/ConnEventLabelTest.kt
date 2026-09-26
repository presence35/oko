package com.odesaplay.oko.connection

import com.odesaplay.oko.AppLanguage
import com.odesaplay.oko.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnEventLabelTest {

    private val s = Strings.get(AppLanguage.EN)

    @Test
    fun `all ConnEventKind cases produce non-empty labels`() {
        for (kind in ConnEventKind.entries) {
            val event = ConnEvent(
                atMillis = 1000L, kind = kind,
                attempt = 1, delayMs = 5000L, detail = "test"
            )
            val label = event.label(s)
            assertTrue("label for $kind is empty", label.isNotEmpty())
        }
    }

    @Test
    fun `CONNECTION_LOST uses connEventLost`() {
        assertEquals("Connection lost", ConnEvent(1000L, ConnEventKind.CONNECTION_LOST).label(s))
    }

    @Test
    fun `RETRY_SCHEDULED formats delay and attempt`() {
        assertEquals("Retrying in 10s · attempt 3",
            ConnEvent(1000L, ConnEventKind.RETRY_SCHEDULED, attempt = 3, delayMs = 10000L).label(s))
    }

    @Test
    fun `RETRY_MANUAL uses connEventManualRetry`() {
        assertEquals("Manual retry", ConnEvent(1000L, ConnEventKind.RETRY_MANUAL).label(s))
    }

    @Test
    fun `NO_NETWORK uses connEventNoNetwork`() {
        assertEquals("No network — waiting to retry", ConnEvent(1000L, ConnEventKind.NO_NETWORK).label(s))
    }

    @Test
    fun `DEGRADED uses connEventDegraded`() {
        assertEquals("Connection degraded", ConnEvent(1000L, ConnEventKind.DEGRADED).label(s))
    }

    @Test
    fun `MILESTONE_3 uses connEventMin3`() {
        assertEquals("3 min offline", ConnEvent(1000L, ConnEventKind.MILESTONE_3).label(s))
    }

    @Test
    fun `MILESTONE_20 uses connEventMin20`() {
        assertEquals("20 min offline — still retrying", ConnEvent(1000L, ConnEventKind.MILESTONE_20).label(s))
    }

    @Test
    fun `GAVE_UP uses connEventGaveUp`() {
        assertEquals("Still offline — retrying in background", ConnEvent(1000L, ConnEventKind.GAVE_UP).label(s))
    }

    @Test
    fun `IGNORE_MUTED formats detail`() {
        assertEquals("Notifications muted for muted by user",
            ConnEvent(1000L, ConnEventKind.IGNORE_MUTED, detail = "muted by user").label(s))
    }

    @Test
    fun `FALLBACK_ACTIVE formats detail`() {
        assertEquals("Fallback active: ubilling",
            ConnEvent(1000L, ConnEventKind.FALLBACK_ACTIVE, detail = "ubilling").label(s))
    }

    @Test
    fun `FALLBACK_RESTORED uses connEventFallbackRestored`() {
        assertEquals("Primary restored", ConnEvent(1000L, ConnEventKind.FALLBACK_RESTORED).label(s))
    }

    @Test
    fun `SOURCE_TOGGLED formats detail`() {
        assertEquals("Source testsource",
            ConnEvent(1000L, ConnEventKind.SOURCE_TOGGLED, detail = "testsource").label(s))
    }

    @Test
    fun `RETRY_SCHEDULED coerces sub-second delay to 1s`() {
        assertEquals("Retrying in 1s · attempt 0",
            ConnEvent(1000L, ConnEventKind.RETRY_SCHEDULED, attempt = 0, delayMs = 500L).label(s))
    }

    @Test
    fun `RETRY_SCHEDULED with null delay defaults to 0`() {
        assertEquals("Retrying in 1s · attempt 2",
            ConnEvent(1000L, ConnEventKind.RETRY_SCHEDULED, attempt = 2, delayMs = null).label(s))
    }

    @Test
    fun `all enum values covered`() {
        assertEquals(15, ConnEventKind.entries.size)
    }
}
