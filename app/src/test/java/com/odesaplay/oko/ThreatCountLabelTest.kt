package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The count forms of type labels, pinned for every type and every plural band. The bug this
 * guards: the tally title took a plural label regardless of count, so one resolved Shahed read
 * "1 Drones resolved". Singular now comes from the catalog, so it cannot drift from the map label.
 */
class ThreatCountLabelTest {

    private val bands = mapOf(
        AppLanguage.EN to mapOf(1 to "one", 2 to "many", 5 to "many", 11 to "many", 21 to "many", 22 to "many"),
        AppLanguage.UA to mapOf(1 to "one", 2 to "few", 4 to "few", 5 to "many", 11 to "many", 14 to "many", 21 to "one", 22 to "few", 25 to "many"),
        AppLanguage.RU to mapOf(1 to "one", 2 to "few", 4 to "few", 5 to "many", 11 to "many", 14 to "many", 21 to "one", 22 to "few", 25 to "many")
    )

    @Test
    fun `every type carries its plural forms`() {
        assertEquals(ThreatType.values().toSet(), TYPE_PLURALS.keys)
    }

    @Test
    fun `singular is the catalog label in every language`() {
        for (type in ThreatType.values()) {
            for (lang in AppLanguage.values()) {
                val catalog = ThreatTypeCatalog.INFO.getValue(type).label(lang)
                assertEquals("$type/$lang", catalog, typeCountLabel(type, lang, 1))
            }
        }
    }

    @Test
    fun `each count lands in its language's band`() {
        for ((lang, counts) in bands) {
            val plurals = TYPE_PLURALS.getValue(ThreatType.SHAHED)
            for ((count, band) in counts) {
                val expected = when (band) {
                    "one" -> ThreatTypeCatalog.INFO.getValue(ThreatType.SHAHED).label(lang)
                    "few" -> plurals.uaFew.takeIf { lang == AppLanguage.UA }
                        ?: plurals.ruFew.takeIf { lang == AppLanguage.RU }
                        ?: plurals.en
                    else -> plurals.uaMany.takeIf { lang == AppLanguage.UA }
                        ?: plurals.ruMany.takeIf { lang == AppLanguage.RU }
                        ?: plurals.en
                }
                assertEquals("$lang/$count", expected, typeCountLabel(ThreatType.SHAHED, lang, count))
            }
        }
    }

    @Test
    fun `five plus uses the many form, not the few`() {
        assertEquals("КАБи", typeCountLabel(ThreatType.KAB, AppLanguage.UA, 2))
        assertEquals("КАБів", typeCountLabel(ThreatType.KAB, AppLanguage.UA, 5))
        assertEquals("КАБов", typeCountLabel(ThreatType.KAB, AppLanguage.RU, 5))
        assertEquals("КАБа", typeCountLabel(ThreatType.KAB, AppLanguage.RU, 2))
        assertEquals("КАБ", typeCountLabel(ThreatType.KAB, AppLanguage.UA, 21))
    }

    @Test
    fun `tally title declines from the count`() {
        assertEquals("1 Drone resolved", resolvedTypeThreatsPhrase(1, ThreatType.SHAHED, AppLanguage.EN))
        assertEquals("3 Drones resolved", resolvedTypeThreatsPhrase(3, ThreatType.SHAHED, AppLanguage.EN))
        assertEquals("Знешкоджено: 1 БпЛА", resolvedTypeThreatsPhrase(1, ThreatType.SHAHED, AppLanguage.UA))
        assertEquals("Знешкоджено: 5 Дронів", resolvedTypeThreatsPhrase(5, ThreatType.SHAHED, AppLanguage.UA))
        assertEquals("Нейтрализовано: 1 БпЛА", resolvedTypeThreatsPhrase(1, ThreatType.SHAHED, AppLanguage.RU))
        assertEquals("Нейтрализовано: 5 Дронов", resolvedTypeThreatsPhrase(5, ThreatType.SHAHED, AppLanguage.RU))
        assertEquals("Знешкоджено: 5 КАБів", resolvedTypeThreatsPhrase(5, ThreatType.KAB, AppLanguage.UA))
        assertEquals("Нейтрализовано: 2 КАБа", resolvedTypeThreatsPhrase(2, ThreatType.KAB, AppLanguage.RU))
    }
}
