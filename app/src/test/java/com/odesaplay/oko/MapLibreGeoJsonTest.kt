package com.odesaplay.oko

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.odesaplay.oko.community.CompactOblastBoundaries
import com.odesaplay.oko.ui.MapLibreGeoJson

class MapLibreGeoJsonTest {

    @Test
    fun `geojson - strict standard dot decimals under Ukrainian comma-decimal locale`() {
        val prevLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("uk", "UA"))

            // Zone circles
            val circleJson = MapLibreGeoJson.singleCircle(50.45, 30.52, 10.0)
            assertFalse("Circle GeoJSON must not contain comma-decimal numbers", circleJson.contains(Regex("""\d,\d""")))
            assertTrue("Circle GeoJSON must contain dot-decimal numbers", circleJson.contains(Regex("""\d\.\d""")))

            // Oblast alerts and borders
            val alertJson = MapLibreGeoJson.alertRegions(setOf("kyivska"))
            assertFalse("Alert GeoJSON must not contain comma-decimal numbers", alertJson.contains(Regex("""\d,\d""")))
            assertTrue("Alert GeoJSON must contain dot-decimal numbers", alertJson.contains(Regex("""\d\.\d""")))

            val borderJson = MapLibreGeoJson.oblastBorders()
            assertFalse("Border GeoJSON must not contain comma-decimal numbers", borderJson.contains(Regex("""\d,\d""")))
            assertTrue("Border GeoJSON must contain dot-decimal numbers", borderJson.contains(Regex("""\d\.\d""")))
        } finally {
            Locale.setDefault(prevLocale)
        }
    }

    @Test
    fun `alertRegions - raion still fills when parent oblast id present but resolves to nothing`() {
        // Simulate the Donetsk case: oblast id is in the set (e.g. NEPTUN sent a whole-oblast
        // alert alongside the raion ones) but its boundary polygon can't produce a fill.
        val geoJson = MapLibreGeoJson.alertRegions(
            oblastIds = setOf("__unresolvable_oblast_id__"),
            raionKeys = setOf("__unresolvable_oblast_id__" to "bakhmutskyi")
        )
        assertTrue(geoJson.contains("\"type\":\"Polygon\""))
    }

    @Test
    fun `alertRegions - donetska emits exactly one polygon, degenerate sliver dropped`() {
        // donetska has 2 rings: a 259-pt exterior and a 5-pt near-zero-area sliver.
        // The sliver must be dropped so MapLibre's tile builder isn't poisoned.
        val geoJson = MapLibreGeoJson.alertRegions(oblastIds = setOf("donetska"))
        assertEquals(1, polygonFeatures(geoJson).size)
    }

    @Test
    fun `alertRegions - every emitted polygon winds counter-clockwise`() {
        // RFC 7946: Polygon exterior rings must be CCW. The compact boundary data
        // stores rings with arbitrary winding; alertRegions must normalize.
        val geoJson = MapLibreGeoJson.alertRegions(
            oblastIds = CompactOblastBoundaries.allStems
        )
        val features = polygonFeatures(geoJson)
        assertTrue("expected polygons for all oblasts, got ${features.size}", features.isNotEmpty())
        for ((index, coords) in features.withIndex()) {
            assertTrue("feature $index has <3 points", coords.size >= 3)
            assertTrue("feature $index winds clockwise, must be CCW", signedArea(coords) > 0)
        }
    }

    private data class Coord(val lon: Double, val lat: Double)

    private val coordRegex = Regex("""\[(-?\d+\.?\d*),(-?\d+\.?\d*)]""")

    private fun polygonFeatures(geoJson: String): List<List<Coord>> =
        geoJson.split("{\"type\":\"Feature\"").drop(1).map { chunk ->
            coordRegex.findAll(chunk).map {
                Coord(it.groupValues[1].toDouble(), it.groupValues[2].toDouble())
            }.toList()
        }

    private fun signedArea(coords: List<Coord>): Double {
        var sum = 0.0
        for (i in coords.indices) {
            val j = (i + 1) % coords.size
            sum += coords[i].lon * coords[j].lat - coords[j].lon * coords[i].lat
        }
        return sum / 2.0
    }
}
