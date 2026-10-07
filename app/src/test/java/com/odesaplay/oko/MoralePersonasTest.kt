package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The morale personas are pure decoration, but they are still read by people in three languages, and
 * the failure mode here is invisible: a persona that quietly serves the English copy under a Russian
 * label looks translated in the picker and isn't on the settings page.
 */
class MoralePersonasTest {

    private val personas = MoralePersona.values().filter { it != MoralePersona.RANDOM }

    private fun packStrings(lang: AppLanguage, persona: MoralePersona): List<String> {
        val pack = moralePersonaPack(lang, persona)
        return pack.calm + listOf(
            pack.calmDesc, pack.flybyDesc, pack.deathDesc, pack.followBulletDesc,
            pack.hdExplosionDesc, pack.tallyDesc, pack.allUkraineDesc, pack.alarmEpisodeDesc,
            pack.note
        )
    }

    @Test
    fun `every persona carries its own copy in all three languages`() {
        for (persona in personas) {
            val en = packStrings(AppLanguage.EN, persona)
            val ua = packStrings(AppLanguage.UA, persona)
            val ru = packStrings(AppLanguage.RU, persona)
            // Same shape everywhere: a translated line list, not a translated description with the
            // English one filling in behind it.
            assertEquals("${persona.name} en", en.size, ua.size)
            assertEquals("${persona.name} en", en.size, ru.size)
            assertEquals("${persona.name} ru", 25, ru.size)
            for (s in ru) assertTrue("${persona.name} ru is blank", s.isNotBlank())
        }
    }

    @Test
    fun `no persona serves the English copy in Russian`() {
        for (persona in personas) {
            val en = packStrings(AppLanguage.EN, persona).toSet()
            for (line in packStrings(AppLanguage.RU, persona)) {
                assertFalse(
                    "${persona.name} still ships English in RU: \"$line\"",
                    line in en
                )
            }
        }
    }

    @Test
    fun `no persona serves the English copy in Ukrainian`() {
        for (persona in personas) {
            val en = packStrings(AppLanguage.EN, persona).toSet()
            for (line in packStrings(AppLanguage.UA, persona)) {
                assertFalse(
                    "${persona.name} still ships English in UA: \"$line\"",
                    line in en
                )
            }
        }
    }

    @Test
    fun `plain is the same plain strings in every language`() {
        val en = packStrings(AppLanguage.EN, MoralePersona.PLAIN)
        val ua = packStrings(AppLanguage.UA, MoralePersona.PLAIN)
        val ru = packStrings(AppLanguage.RU, MoralePersona.PLAIN)
        assertEquals(en.size, ua.size)
        assertEquals(en.size, ru.size)
        // PLAIN is built from Strings, so it must not be one of the characterful packs.
        assertFalse(en == packStrings(AppLanguage.EN, MoralePersona.WARM))
    }

    /** The label is the promise; a Russian label over a foreign pack is what started all this. */
    @Test
    fun `every persona is named in every language`() {
        for (lang in AppLanguage.values()) {
            for (persona in MoralePersona.values()) {
                assertTrue("${persona.name} in $lang", persona.label(lang).isNotBlank())
            }
            assertTrue(moralePersonaTitle(lang).isNotBlank())
        }
        assertEquals("Сленг", MoralePersona.SLANG.label(AppLanguage.RU))
        assertEquals("Военное", MoralePersona.VIYSKO.label(AppLanguage.RU))
        assertEquals("Бабуля", MoralePersona.BABUSIA.label(AppLanguage.RU))
    }
}