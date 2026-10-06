package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The morale voices are pure decoration, but they are still read by people in three languages, and
 * the failure mode here is invisible: a voice that quietly serves the English copy under a Russian
 * label looks translated in the picker and isn't on the settings page.
 */
class MoraleVoicesTest {

    private val voices = MoraleVoice.values().filter { it != MoraleVoice.RANDOM }

    private fun packStrings(lang: AppLanguage, voice: MoraleVoice): List<String> {
        val pack = moraleVoicePack(lang, voice)
        return pack.calm + listOf(
            pack.calmDesc, pack.flybyDesc, pack.deathDesc, pack.followBulletDesc,
            pack.hdExplosionDesc, pack.tallyDesc, pack.allUkraineDesc, pack.alarmEpisodeDesc,
            pack.note
        )
    }

    @Test
    fun `every voice carries its own copy in all three languages`() {
        for (voice in voices) {
            val en = packStrings(AppLanguage.EN, voice)
            val ua = packStrings(AppLanguage.UA, voice)
            val ru = packStrings(AppLanguage.RU, voice)
            // Same shape everywhere: a translated line list, not a translated description with the
            // English one filling in behind it.
            assertEquals("${voice.name} en", en.size, ua.size)
            assertEquals("${voice.name} en", en.size, ru.size)
            assertEquals("${voice.name} ru", 25, ru.size)
            for (s in ru) assertTrue("${voice.name} ru is blank", s.isNotBlank())
        }
    }

    @Test
    fun `no voice serves the English copy in Russian`() {
        for (voice in voices) {
            val en = packStrings(AppLanguage.EN, voice).toSet()
            for (line in packStrings(AppLanguage.RU, voice)) {
                assertFalse(
                    "${voice.name} still ships English in RU: \"$line\"",
                    line in en
                )
            }
        }
    }

    @Test
    fun `no voice serves the English copy in Ukrainian`() {
        for (voice in voices) {
            val en = packStrings(AppLanguage.EN, voice).toSet()
            for (line in packStrings(AppLanguage.UA, voice)) {
                assertFalse(
                    "${voice.name} still ships English in UA: \"$line\"",
                    line in en
                )
            }
        }
    }

    @Test
    fun `plain is the same plain strings in every language`() {
        val en = packStrings(AppLanguage.EN, MoraleVoice.PLAIN)
        val ua = packStrings(AppLanguage.UA, MoraleVoice.PLAIN)
        val ru = packStrings(AppLanguage.RU, MoraleVoice.PLAIN)
        assertEquals(en.size, ua.size)
        assertEquals(en.size, ru.size)
        // PLAIN is built from Strings, so it must not be one of the characterful packs.
        assertFalse(en == packStrings(AppLanguage.EN, MoraleVoice.WARM))
    }

    /** The label is the promise; a Russian label over a foreign pack is what started all this. */
    @Test
    fun `every voice is named in every language`() {
        for (lang in AppLanguage.values()) {
            for (voice in MoraleVoice.values()) {
                assertTrue("${voice.name} in $lang", voice.label(lang).isNotBlank())
            }
            assertTrue(moraleVoiceSectionTitle(lang).isNotBlank())
        }
        assertEquals("Сленг", MoraleVoice.SLANG.label(AppLanguage.RU))
        assertEquals("Военное", MoraleVoice.VIYSKO.label(AppLanguage.RU))
        assertEquals("Бабуля", MoraleVoice.BABUSIA.label(AppLanguage.RU))
    }
}