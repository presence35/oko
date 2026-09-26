package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Test
import com.odesaplay.oko.connection.ConnEvent
import com.odesaplay.oko.connection.ConnEventKind

class ConnEventTest {

    @Test
    fun `retry and milestone event labels format per language`() {
        val s = Strings.get(AppLanguage.EN)
        val retry = ConnEvent(0L, ConnEventKind.RETRY_SCHEDULED, attempt = 3, delayMs = 12_000L)
        assertEquals("Retrying in 12s · attempt 3", retry.label(s))
        assertEquals("Connection lost", ConnEvent(0L, ConnEventKind.CONNECTION_LOST).label(s))
        assertEquals("No network — waiting to retry", ConnEvent(0L, ConnEventKind.NO_NETWORK).label(s))
        assertEquals("Manual retry", ConnEvent(0L, ConnEventKind.RETRY_MANUAL).label(s))
        assertEquals("5 min offline — alarm", ConnEvent(0L, ConnEventKind.MILESTONE_5).label(s))
        assertEquals("Notifications muted for 30 min", ConnEvent(0L, ConnEventKind.IGNORE_MUTED, detail = "30 min").label(s))
    }

    @Test
    fun `ukrainian event labels are translated`() {
        val s = Strings.get(AppLanguage.UA)
        val retry = ConnEvent(0L, ConnEventKind.RETRY_SCHEDULED, attempt = 2, delayMs = 8_000L)
        assertEquals("Повтор через 8s · спроба 2", retry.label(s))
        assertEquals("З'єднання втрачено", ConnEvent(0L, ConnEventKind.CONNECTION_LOST).label(s))
        assertEquals("Notifications muted for 30 min", ConnEvent(0L, ConnEventKind.IGNORE_MUTED, detail = "30 min").label(s))
    }

    @Test
    fun `offline live format carries minutes only`() {
        val en = Strings.get(AppLanguage.EN)
        assertEquals("Offline 4 min", String.format(en.offlineLiveFormat, 4))
        val ua = Strings.get(AppLanguage.UA)
        assertEquals("Offline 4 min", String.format(ua.offlineLiveFormat, 4))
    }
}