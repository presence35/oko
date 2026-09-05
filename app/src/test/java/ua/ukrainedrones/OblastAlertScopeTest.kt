package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.coversCity
import ua.ukrainedrones.engine.inOblast
import ua.ukrainedrones.engine.isOblastWide
import ua.ukrainedrones.engine.officialAlertActiveFor
import ua.ukrainedrones.engine.raionName

class OblastAlertScopeTest {

    private fun alert(key: String, name: String, oblast: String) =
        OblastAlert(key = key, name = name, oblast = oblast, since = null)

    @Test
    fun `isOblastWide - oblast and republic alerts are wide`() {
        assertTrue(alert("луганська", "Луганська область", "Луганська область").isOblastWide())
        assertTrue(
            alert(
                "автономна республіка крим",
                "Автономна Республіка Крим",
                "Автономна Республіка Крим"
            ).isOblastWide()
        )
    }

    @Test
    fun `isOblastWide - raion and city alerts are not wide even when the parent oblast is named`() {
        assertFalse(alert("бердянський", "Бердянський район", "Запорізька область").isOblastWide())
        assertFalse(alert("севастополь", "Севастополь", "Севастополь").isOblastWide())
    }

    @Test
    fun `inOblast - prefix matches the adjectival stem`() {
        val a = alert("луганська", "Луганська область", "Луганська область")
        assertTrue(a.inOblast("Луганськ"))
        assertFalse(a.inOblast("Київськ"))
    }

    @Test
    fun `inOblast - Crimea matches by whole word, not prefix`() {
        val a = alert(
            "автономна республіка крим",
            "Автономна Республіка Крим",
            "Автономна Республіка Крим"
        )
        assertTrue(a.inOblast("Крим"))
        assertFalse(a.inOblast("Львівськ"))
    }

    @Test
    fun `coversCity - oblast-wide alert covers every city in its stem`() {
        val a = alert("луганська", "Луганська область", "Луганська область")
        assertTrue(a.coversCity("Луганськ"))
        assertTrue(a.coversCity("Сєвєродонецьк"))
        assertTrue(a.coversCity("Лисичанськ"))
    }

    @Test
    fun `coversCity - raion alert covers only cities it names`() {
        val a = alert("бердянський", "Бердянський район", "Запорізька область")
        assertTrue(a.coversCity("Бердянськ"))
        assertFalse(a.coversCity("Запоріжжя"))
        assertFalse(a.coversCity("Мелітополь"))
    }

    @Test
    fun `officialAlertActiveFor - scope off rings the whole oblast`() {
        val alerts = listOf(alert("луганська", "Луганська область", "Луганська область"))
        assertTrue(officialAlertActiveFor(alerts, "Луганськ", null, scope = false))
    }

    @Test
    fun `officialAlertActiveFor - city scope with oblast-wide alert covers any city in the stem`() {
        val alerts = listOf(alert("луганська", "Луганська область", "Луганська область"))
        assertTrue(officialAlertActiveFor(alerts, "Луганськ", "Сєвєродонецьк", scope = true))
        assertTrue(officialAlertActiveFor(alerts, "Луганськ", "Луганськ", scope = true))
    }

    @Test
    fun `officialAlertActiveFor - city scope with raion alert narrows to the named city`() {
        val alerts = listOf(alert("бердянський", "Бердянський район", "Запорізька область"))
        assertTrue(officialAlertActiveFor(alerts, "Запорізьк", "Бердянськ", scope = true))
        assertFalse(officialAlertActiveFor(alerts, "Запорізьк", "Запоріжжя", scope = true))
    }

    @Test
    fun `raionName - raion alert strips the район suffix`() {
        assertEquals(
            "бердянський",
            alert("бердянський", "Бердянський район", "Запорізька область").raionName()
        )
    }

    @Test
    fun `raionName - bare adjectival name falls back to the key`() {
        assertEquals(
            "дніпровський",
            alert("дніпровський", "Дніпровський", "Дніпропетровська область").raionName()
        )
    }

    @Test
    fun `raionName - oblast-wide and city alerts are null`() {
        assertNull(alert("луганська", "Луганська область", "Луганська область").raionName())
        assertNull(alert("dnipro", "Дніпро", "Дніпропетровська область").raionName())
    }
}