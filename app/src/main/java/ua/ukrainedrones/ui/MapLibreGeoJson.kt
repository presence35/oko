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
            val ringStrings = poly.rings.filter { it.pointCount >= 3 }.map { ring ->
                val pts = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                "[$pts]"
            }
            if (ringStrings.isNotEmpty()) {
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[${ringStrings.joinToString(",")}]}}""")
            }
        }
        for ((stem, raion) in raionKeys) {
            val poly = CompactRaionBoundaries.forKey(stem, raion) ?: continue
            val ringStrings = poly.rings.filter { it.pointCount >= 3 }.map { ring ->
                val pts = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                "[$pts]"
            }
            if (ringStrings.isNotEmpty()) {
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[${ringStrings.joinToString(",")}]}}""")
            }
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    /**
     * Range warning circles (outer yellow and inner red) centered on the user's active focus point.
     */
    fun zoneCircles(
        centerLat: Double?,
        centerLon: Double?,
        redRadiusKm: Double,
        yellowRadiusKm: Double,
        segments: Int = 64
    ): String {
        if (centerLat == null || centerLon == null || !centerLat.isFinite() || !centerLon.isFinite()) {
            return EMPTY
        }
        fun makeCircle(radiusKm: Double, zoneName: String): String {
            val radiusM = radiusKm * 1000.0
            val coords = (0..segments).map { i ->
                val bearing = 360.0 * (i % segments) / segments
                val pt = destinationPoint(centerLat, centerLon, radiusM, bearing)
                "[${pt.lon},${pt.lat}]"
            }.joinToString(",")
            return """{"type":"Feature","properties":{"zone":"$zoneName"},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
        }
        val redFeature = makeCircle(redRadiusKm, "red")
        val yellowFeature = makeCircle(yellowRadiusKm, "yellow")
        return """{"type":"FeatureCollection","features":[$redFeature,$yellowFeature]}"""
    }
}
