package ua.ukrainedrones

import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.distanceFlat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitiesTest {

    @Test
    fun `same-named towns are distinct places far apart`() {
        // Ukraine has a few same-named towns in different oblasts; entries may share a name
        // only if they are genuinely different map places (well beyond label-collision range).
        val groups = Cities.ALL.groupBy { it.nameUa }.filterValues { it.size > 1 }
        for ((name, group) in groups) {
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val a = group[i]
                    val b = group[j]
                    val km = distanceFlat(a.lat, a.lon, b.lat, b.lon) / 1000.0
                    assertTrue("same name '$name' too close: ${a.nameUa} vs ${b.nameUa}", km >= 20.0)
                }
            }
            assertEquals("name lookups must resolve to the largest holder", group.maxByOrNull { it.pop }, Cities.byUa[name])
            assertTrue(Cities.uaToEn[name]!!.isNotBlank())
            assertTrue(Cities.cityOblast.containsKey(name))
        }
    }

    @Test
    fun `every city has an oblast attribution`() {
        for (c in Cities.ALL) {
            assertTrue("missing cityOblast for ${c.nameUa}", Cities.cityOblast.containsKey(c.nameUa))
        }
    }

    @Test
    fun `en names derive from transliteration and are non-empty`() {
        for (c in Cities.ALL) {
            assertEquals(Transliteration.transliterate(c.nameUa), c.nameEn)
            assertTrue(c.nameEn.isNotBlank())
        }
    }

    @Test
    fun `list is large enough for country-scale context`() {
        val majors = Cities.ALL.count { it.tier == CityTier.MAJOR }
        val mediums = Cities.ALL.count { it.tier == CityTier.MEDIUM }
        val minors = Cities.ALL.count { it.tier == CityTier.MINOR }
        assertEquals(26, majors)
        assertEquals(20, mediums)
        assertTrue("expected ~400+ minors, got $minors", minors >= 400)
    }

    @Test
    fun `every city is inside Ukraine's bounding box`() {
        for (c in Cities.ALL) {
            assertTrue("lat out of range for ${c.nameUa}: ${c.lat}", c.lat in 44.0..53.5)
            assertTrue("lon out of range for ${c.nameUa}: ${c.lon}", c.lon in 22.0..41.0)
        }
    }

    @Test
    fun `nearestCity never returns a minor`() {
        // Stand exactly on the minor city Chornomorsk; attribution must still land on Odesa.
        val near = Cities.nearestCity(46.3036, 30.6566)
        assertEquals("Одеса", near?.nameUa)
        assertTrue(near?.major == true)
    }

    @Test
    fun `focusAttribution near a minor city uses the major banner`() {
        val fa = focusAttribution(
            followMe = true,
            userLocation = LatLng(46.3036, 30.6566),
            pinned = null
        )
        assertEquals("Одеськ", fa.token)
        assertEquals("Одеса", fa.bannerCityUa)
        assertEquals("Odesa", fa.bannerCityEn)
    }

    @Test
    fun `focusAttribution without location falls back to the Odesa display default`() {
        val fa = focusAttribution(
            followMe = true,
            userLocation = null,
            pinned = null
        )
        assertEquals(Cities.cityOblast[Cities.ODESA.nameUa], fa.token)
        assertEquals(Cities.ODESA.nameUa, fa.bannerCityUa)
        assertEquals(Cities.ODESA.nameEn, fa.bannerCityEn)
    }
}
