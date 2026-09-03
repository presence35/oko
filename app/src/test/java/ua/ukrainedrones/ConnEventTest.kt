package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.ukrainedrones.connection.ConnEvent
import ua.ukrainedrones.connection.ConnEventKind

class ConnEventTest {

    @Test
    fun `retry and milestone event labels format per language`() {
        val s = Strings.get(AppLanguage.EN)
        val retry = ConnEvent(0L, ConnEventKind.RETRY_SCHEDULED, attempt = 3, delayMs = 12_000L)
        assertEquals("Retrying in 12s · attempt 3", retry.label(s))
        assertEquals("Connection lost", ConnEvent(0L, ConnEventKind.CONNECTION_LOST).label(s))
        assertEquals("No network — waiting to retry", ConnEvent(0L, ConnEventKind.NO_NETWORK).label(s))
        assertEquals("5 min offline — alarm", ConnEvent(0L, ConnEventKind.MILESTONE_5).label(s))
        assertEquals("Retrying paused for 30 min", ConnEvent(0L, ConnEventKind.PAUSED).label(s))
    }

    @Test
    fun `ukrainian event labels are translated`() {
        val s = Strings.get(AppLanguage.UA)
        val retry = ConnEvent(0L, ConnEventKind.RETRY_SCHEDULED, attempt = 2, delayMs = 8_000L)
        assertEquals("Повтор через 8s · спроба 2", retry.label(s))
        assertEquals("З'єднання втрачено", ConnEvent(0L, ConnEventKind.CONNECTION_LOST).label(s))
        assertEquals("Повтор призупинено на 30 хв", ConnEvent(0L, ConnEventKind.PAUSED).label(s))
    }

    @Test
    fun `offline live format carries minute and attempt`() {
        val en = Strings.get(AppLanguage.EN)
        assertEquals("Offline 4/20 min · attempt 5", String.format(en.offlineLiveFormat, 4, 20, 5))
        val ua = Strings.get(AppLanguage.UA)
        assertEquals("Офлайн 4/20 хв · спроба 5", String.format(ua.offlineLiveFormat, 4, 20, 5))
    }
}