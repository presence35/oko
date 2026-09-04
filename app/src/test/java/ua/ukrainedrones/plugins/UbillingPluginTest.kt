package ua.ukrainedrones.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbillingPluginTest {

    private val now = 1_000_000L
    private val since = now - UbillingPlugin.GRACE_MS

    private fun plugin() = UbillingPlugin(
        primaryHealthy = kotlinx.coroutines.flow.flowOf(true),
        appForeground = kotlinx.coroutines.flow.flowOf(false)
    )

    @Test
    fun `idle when disabled`() {
        val p = plugin()
        assertNull(p.computeIntervalMs(since, foreground = true, healthy = false, enabled = false, now = now))
    }

    @Test
    fun `idle when primary healthy`() {
        val p = plugin()
        assertNull(p.computeIntervalMs(since, foreground = true, healthy = true, enabled = true, now = now))
    }

    @Test
    fun `idle when no outage recorded`() {
        val p = plugin()
        assertNull(p.computeIntervalMs(null, foreground = true, healthy = false, enabled = true, now = now))
    }

    @Test
    fun `idle during grace period`() {
        val p = plugin()
        val recentSince = now - 1_000L
        assertNull(p.computeIntervalMs(recentSince, foreground = true, healthy = false, enabled = true, now = now))
    }

    @Test
    fun `foreground polls fast after grace`() {
        val p = plugin()
        assertEquals(UbillingPlugin.POLL_FAST_MS, p.computeIntervalMs(since, foreground = true, healthy = false, enabled = true, now = now))
    }

    @Test
    fun `background polls slow after grace`() {
        val p = plugin()
        assertEquals(UbillingPlugin.POLL_BG_MS, p.computeIntervalMs(since, foreground = false, healthy = false, enabled = true, now = now))
    }

    @Test
    fun `disabled overrides everything`() {
        val p = plugin()
        assertNull(p.computeIntervalMs(since, foreground = false, healthy = false, enabled = false, now = now))
    }

    @Test
    fun `parse keeps only alertnow oblasts`() {
        val p = plugin()
        val body = """
            {"states": {
                "Одеська область": {"alertnow": true},
                "Київська область": {"alertnow": false},
                "Львівська область": {}
            }}
        """.trimIndent()
        val alerts = p.parseStates(body)
        assertEquals(1, alerts.size)
        assertEquals("Одеська область", alerts[0].oblast)
        assertEquals("ubilling:Одеська область", alerts[0].key)
        assertNull(alerts[0].since)
    }

    @Test
    fun `parse empty snapshot yields no alerts`() {
        val p = plugin()
        assertTrue(p.parseStates("""{"states":{}}""").isEmpty())
    }
}