package ua.ukrainedrones
import ua.ukrainedrones.engine.distanceFlat

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelterTest {

    private fun payload(vararg rows: Array<Any?>): String {
        val data = JSONArray()
        rows.forEach { data.put(JSONArray(it.toList())) }
        return "{\"result\":\"OK\",\"data\":$data}"
    }

    @Test
    fun `parses the city payload shape and drops bad rows`() {
        val json = payload(
            arrayOf(1, "46.48, 30.73", "icon1", "Вулиця Дерибасівська, 1", false),
            arrayOf(2, "50.45, 30.52", "icon2", "Укриття: ст. м. Хрещатик", false),
            arrayOf(3, "", "icon3", "no coords", false),                    // dropped: empty
            arrayOf(4, "abc,def", "icon4", "garbage", false),               // dropped: unparsable
            arrayOf(5, "51.50,-0.12", "icon5", "London", false),            // dropped: outside Ukraine bbox
            arrayOf(6, "46.48, 30.73", "icon6", "Дублікат координат", false)
        )
        val index = ShelterIndex.fromJson(json)
        assertNotNull(index)
        assertEquals(3, index!!.size)
        assertEquals("Вулиця Дерибасівська, 1", index.nearest(46.48, 30.73, 1).single().shelter.name)
    }

    @Test
    fun `parses compact 3-element payload format`() {
        val json = payload(
            arrayOf(1, "46.48, 30.73", "Вулиця Дерибасівська, 1"),
            arrayOf(2, "50.45, 30.52", "Укриття: ст. м. Хрещатик")
        )
        val index = ShelterIndex.fromJson(json)
        assertNotNull(index)
        assertEquals(2, index!!.size)
        val nearestKyiv = index.nearest(50.45, 30.52, 1).single()
        assertEquals("Укриття: ст. м. Хрещатик", nearestKyiv.shelter.name)
        assertEquals(ShelterType.BUNKER, nearestKyiv.shelter.type)
    }

    @Test
    fun `fromJson returns null on unreadable input`() {
        assertNull(ShelterIndex.fromJson("not json"))
        assertNull(ShelterIndex.fromJson("{\"result\":\"OK\"}"))
    }

    @Test
    fun `nearest ranks by distance and honors the limit`() {
        val index = ShelterIndex.fromJson(
            payload(
                arrayOf(1, "46.50,30.70", "i", "далеко", false),
                arrayOf(2, "46.48,30.73", "i", "близько", false),
                arrayOf(3, "46.50,30.75", "i", "середньо", false)
            )
        )!!
        val top = index.nearest(46.48, 30.73, 2)
        assertEquals(listOf("близько", "середньо"), top.map { it.shelter.name })
        assertTrue(top.first().distanceMeters < top.last().distanceMeters)
        assertEquals(2, top.size)
    }

    @Test
    fun `withinRegion reflects the parsed data extent`() {
        val index = ShelterIndex.fromJson(payload(
            arrayOf(1, "46.48, 30.73", "i", "Odesa", false),
            arrayOf(2, "50.45, 30.52", "i", "Kyiv", false)
        ))!!
        assertTrue(index.withinRegion(46.48, 30.73))   // Odesa — in data
        assertTrue(index.withinRegion(50.45, 30.52))   // Kyiv — in data
        assertTrue(!index.withinRegion(49.84, 24.03))  // Lviv — not in data
        assertTrue(!index.withinRegion(51.50, -0.12))  // London — not in data
    }

    @Test
    fun `shelterNameEn translates glossary terms and transliterates correctly`() {
        assertEquals("Shelter: metro st. Khreshchatyk", shelterNameEn("Укриття: ст. м. Хрещатик"))
        assertEquals("CDS No.10542 (St Bohdana Khmelnytskoho, 15)", shelterNameEn("ЗСЦЗ №10542 (вул. Богдана Хмельницького, 15)"))
        assertEquals("Primary (mobile) shelter (Ave Tsentralnyi, 24)", shelterNameEn("Первинне (мобільне) укриття (просп. Центральний, 24)"))
    }

    @Test
    fun `walk times scale with speed and distance`() {
        val near = NearestShelter(Shelter("1", "x", 46.48, 30.73), 500.0)
        // 500 m at ~5 km/h = 6 min; at ~3 km/h = 10 min.
        assertEquals(6, near.walkMinutesAdult)
        assertEquals(10, near.walkMinutesKid)
        // A far shelter is strictly slower than the same distance covered by an adult.
        val far = NearestShelter(Shelter("2", "y", 46.48, 30.73), 5000.0)
        assertTrue(far.walkMinutesKid > far.walkMinutesAdult)
        assertEquals(0, NearestShelter(Shelter("3", "z", 46.48, 30.73), 0.0).walkMinutesAdult)
    }

    @Test
    fun `distance matches the top-level haversine`() {
        val shelter = Shelter("1", "x", 46.53, 30.73)
        val expect = distanceFlat(46.48, 30.73, 46.53, 30.73)
        assertEquals(expect, shelter.distanceFlat(46.48, 30.73), 1e-9)
    }
}
