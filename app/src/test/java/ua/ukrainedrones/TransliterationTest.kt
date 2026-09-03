package ua.ukrainedrones

import org.junit.Assert.assertEquals
import org.junit.Test

class TransliterationTest {

    @Test
    fun `transliterates major cities`() {
        assertEquals("Kyiv", Transliteration.transliterate("Київ"))
        assertEquals("Odesa", Transliteration.transliterate("Одеса"))
        assertEquals("Lviv", Transliteration.transliterate("Львів"))
        assertEquals("Dnipro", Transliteration.transliterate("Дніпро"))
        assertEquals("Kharkiv", Transliteration.transliterate("Харків"))
        assertEquals("Kherson", Transliteration.transliterate("Херсон"))
        assertEquals("Mykolaiv", Transliteration.transliterate("Миколаїв"))
        assertEquals("Zaporizhzhia", Transliteration.transliterate("Запоріжжя"))
        assertEquals("Kryvyi Rih", Transliteration.transliterate("Кривий Ріг"))
        assertEquals("Ivano-Frankivsk", Transliteration.transliterate("Івано-Франківськ"))
    }

    @Test
    fun `never translates a proper noun semantically`() {
        assertEquals("Zolote", Transliteration.transliterate("Золоте"))
        assertEquals("Zhovti Vody", Transliteration.transliterate("Жовті Води"))
        assertEquals("Bila Tserkva", Transliteration.transliterate("Біла Церква"))
    }

    @Test
    fun `transliterates oblast and district names`() {
        assertEquals("Kyivska oblast", Transliteration.transliterate("Київська область"))
        assertEquals("Odeska oblast", Transliteration.transliterate("Одеська область"))
        assertEquals("Darnytskyi raion", Transliteration.transliterate("Дарницький район"))
    }

    @Test
    fun `preserves case and passes non-cyrillic through`() {
        assertEquals("Yuzhne", Transliteration.transliterate("Южне"))
        assertEquals("Odesa · Kyivska oblast", Transliteration.transliterate("Одеса · Київська область"))
        assertEquals("Kyiv 30.1", Transliteration.transliterate("Київ 30.1"))
    }

    @Test
    fun `applies positional digraph rules`() {
        assertEquals("Yurii", Transliteration.transliterate("Юрій"))
        assertEquals("Oleksii", Transliteration.transliterate("Олексій"))
        assertEquals("Kostiantyn", Transliteration.transliterate("Костянтин"))
        assertEquals("Znamianka", Transliteration.transliterate("Знам'янка"))
        assertEquals("Biliaivka", Transliteration.transliterate("Біляївка"))
        assertEquals("Zghurskyi", Transliteration.transliterate("Згурський"))
    }
}