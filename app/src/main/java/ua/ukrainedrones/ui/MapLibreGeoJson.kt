package ua.ukrainedrones.ui

import ua.ukrainedrones.UKRAINE_LAND_BORDER
import ua.ukrainedrones.community.CompactOblastBoundaries
import ua.ukrainedrones.community.CompactRaionBoundaries
import ua.ukrainedrones.engine.destinationPoint

/**
 * Builds GeoJSON representations of static outlines, administrative boundaries,
 * active threat alert regions, and focus-centered range circles for MapLibre Native.
 */
object MapLibreGeoJson {

    /** Empty FeatureCollection sentinel. */
    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /**
     * Inverted mask covering everything outside Ukraine's boundary,
     * dimming/blacking out unnecessary foreign detail.
     */
    fun outsideUkraineMask(): String {
        val ukraineRing = ua.ukrainedrones.UKRAINE_BORDER.joinToString(",") { "[${it.lon},${it.lat}]" }
        val worldOuter = "[-180.0,-85.0],[180.0,-85.0],[180.0,85.0],[-180.0,85.0],[-180.0,-85.0]"
        return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$worldOuter],[$ukraineRing]]}}]}"""
    }

    /** Ukraine land border outline — hugs land/river borders and skips open sea coastline. */
    fun landBorder(): String {
        val coords = UKRAINE_LAND_BORDER.joinToString(",") { "[${it.lon},${it.lat}]" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    }

    /** Administrative outlines of all Ukrainian oblasts. */
    fun oblastBorders(): String {
        val features = mutableListOf<String>()
        for (stem in CompactOblastBoundaries.allStems) {
            val poly = CompactOblastBoundaries.get(stem) ?: continue
            for (ring in poly.rings) {
                if (ring.pointCount < 3) continue
                val coords = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}""")
            }
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    /** Administrative outlines of Ukrainian raions (districts). */
    fun raionBorders(): String {
        val features = mutableListOf<String>()
        for ((_, poly) in CompactRaionBoundaries.all) {
            for (ring in poly.rings) {
                if (ring.pointCount < 3) continue
                val coords = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}""")
            }
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    /**
     * Alerting region polygons (oblast-wide and individual raions) for fill/stroke overlays.
     */
    fun alertRegions(
        oblastTokens: Set<String>,
        raionKeys: Set<Pair<String, String>> = emptySet()
    ): String {
        if (oblastTokens.isEmpty() && raionKeys.isEmpty()) return EMPTY

        val features = mutableListOf<String>()
        for (stem in oblastTokens) {
            val poly = CompactOblastBoundaries.get(stem) ?: continue
            for (ring in poly.rings) {
                if (ring.pointCount < 3) continue
                val pts = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$pts]]}}""")
            }
        }
        for ((stem, raion) in raionKeys) {
            val poly = CompactRaionBoundaries.forKey(stem, raion) ?: continue
            for (ring in poly.rings) {
                if (ring.pointCount < 3) continue
                val pts = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$pts]]}}""")
            }
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    /**
     * Single range warning circle centered on the user's active focus point.
     */
    fun singleCircle(
        centerLat: Double?,
        centerLon: Double?,
        radiusKm: Double,
        segments: Int = 64
    ): String {
        if (centerLat == null || centerLon == null || !centerLat.isFinite() || !centerLon.isFinite() || radiusKm <= 0.0) {
            return EMPTY
        }
        val radiusM = radiusKm * 1000.0
        val coords = (0..segments).map { i ->
            val bearing = 360.0 * (i % segments) / segments
            val pt = destinationPoint(centerLat, centerLon, radiusM, bearing)
            "[${pt.lon},${pt.lat}]"
        }.joinToString(",")
        return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}]}"""
    }
}
