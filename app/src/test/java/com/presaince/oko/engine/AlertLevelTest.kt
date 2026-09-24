package com.presaince.oko.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertLevelTest {

    private fun oblastAlert(level: String) = OblastAlert(
        key = "odeska",
        name = "Одеська область",
        oblast = "Одеська область",
        since = null,
        wide = true,
        level = level
    )

    private fun raionAlert(key: String, name: String, level: String = "red") = OblastAlert(
        key = key,
        name = name,
        oblast = "Одеська область",
        since = null,
        wide = false,
        level = level
    )

    @Test
    fun `blank token is NONE even with live alerts`() {
        val alerts = listOf(oblastAlert("red"))
        assertEquals(AlertLevel.NONE, alerts.maxLevelFor(null, "Одеса", true))
        assertEquals(AlertLevel.NONE, alerts.maxLevelFor("  ", "Одеса", false))
    }

    @Test
    fun `empty feed is NONE`() {
        assertEquals(AlertLevel.NONE, emptyList<OblastAlert>().maxLevelFor("Одеськ", "Одеса", true))
    }

    @Test
    fun `red beats yellow`() {
        val alerts = listOf(oblastAlert("yellow"), oblastAlert("red"))
        assertEquals(AlertLevel.RED, alerts.maxLevelFor("Одеськ", "Одеса", true))
    }

    @Test
    fun `yellow alone is YELLOW`() {
        val alerts = listOf(oblastAlert("yellow"))
        assertEquals(AlertLevel.YELLOW, alerts.maxLevelFor("Одеськ", "Одеса", true))
    }

    @Test
    fun `red alone is RED`() {
        val alerts = listOf(oblastAlert("red"))
        assertEquals(AlertLevel.RED, alerts.maxLevelFor("Одеськ", "Одеса", false))
    }

    @Test
    fun `other oblast does not count`() {
        val alerts = listOf(oblastAlert("red"))
        assertEquals(AlertLevel.NONE, alerts.maxLevelFor("Київськ", "Одеса", false))
    }

    @Test
    fun `scoped raion outside the focus city is ignored`() {
        val alerts = listOf(raionAlert("bolhradskyi", "Болградський район"))
        assertEquals(AlertLevel.NONE, alerts.maxLevelFor("Одеськ", "Одеса", true))
    }

    @Test
    fun `unscoped raion in the oblast counts`() {
        val alerts = listOf(raionAlert("bolhradskyi", "Болградський район"))
        assertEquals(AlertLevel.RED, alerts.maxLevelFor("Одеськ", "Одеса", false))
    }

    @Test
    fun `scoped raion covering the focus city counts`() {
        val alerts = listOf(raionAlert("odeskyi", "Одеський район"))
        assertEquals(AlertLevel.RED, alerts.maxLevelFor("Одеськ", "Одеса", true))
    }

    @Test
    fun `scoped with unknown city falls back to oblast matching`() {
        val alerts = listOf(raionAlert("bolhradskyi", "Болградський район"))
        assertEquals(AlertLevel.RED, alerts.maxLevelFor("Одеськ", null, true))
    }
}
