package com.odesaplay.oko

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianToponymsTest {

    @Test
    fun `major exonyms render their real Russian names`() {
        assertEquals("Киев", RussianToponyms.city("Київ"))
        assertEquals("Харьков", RussianToponyms.city("Харків"))
        assertEquals("Львов", RussianToponyms.city("Львів"))
        assertEquals("Днепр", RussianToponyms.city("Дніпро"))
        assertEquals("Одесса", RussianToponyms.city("Одеса"))
        assertEquals("Николаев", RussianToponyms.city("Миколаїв"))
        assertEquals("Чернигов", RussianToponyms.city("Чернігів"))
        assertEquals("Сумы", RussianToponyms.city("Суми"))
    }

    @Test
    fun `apostrophe variants resolve regardless of the mark used`() {
        assertEquals("Каменское", RussianToponyms.city("Кам’янське"))
        assertEquals("Каменское", RussianToponyms.city("Кам'янське"))
    }

    @Test
    fun `oblasts and raions render their Russian forms`() {
        assertEquals("Донецкая область", RussianToponyms.oblast("donetska"))
        assertEquals("Запорожская область", RussianToponyms.oblast("zaporizka"))
        assertEquals("Автономная Республика Крым", RussianToponyms.oblast("krym"))
        assertEquals("Бердянский район", RussianToponyms.raion("Бердянський район"))
        assertEquals("Бердянский", RussianToponyms.raion("Бердянський"))
    }

    @Test
    fun `unknown names fall back to the Ukrainian original`() {
        assertEquals("Атлантида", RussianToponyms.city("Атлантида"))
        assertEquals("atlantis", RussianToponyms.oblast("atlantis"))
        assertEquals("Хмарочос", RussianToponyms.raion("Хмарочос"))
    }
}
