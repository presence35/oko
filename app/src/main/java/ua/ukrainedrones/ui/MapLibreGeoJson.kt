package ua.ukrainedrones.ui

import ua.ukrainedrones.UKRAINE_LAND_BORDER
import ua.ukrainedrones.community.CompactOblastBoundaries
import ua.ukrainedrones.community.CompactRaionBoundaries
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.engine.destinationPoint

/**
 * Builds GeoJSON representations of static outlines, administrative boundaries,
 * active threat alert regions, and focus-centered range circles for MapLibre Native.
 */
object MapLibreGeoJson {

    /**
     * Logs a fill-rendering problem both to Logcat and into the in-app Logs screen
     * (Settings → Logs, amber "Fill debug" entries) so it's visible without adb/logcat.
     */
    private fun fillDebugLog(message: String) {
        android.util.Log.w("MapLibreGeoJson", message)
        ApiMonitor.record(
            SystemEntry(
                atMillis = System.currentTimeMillis(),
                kind = SystemEntryKind.FILL_DEBUG,
                detail = "[MapLibreGeoJson] $message"
            )
        )
    }

    /** Empty FeatureCollection sentinel. */
    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /**
     * Inverted mask covering everything outside Ukraine's boundary,
     * dimming/blacking out unnecessary foreign detail.
     */
    fun outsideUkraineMask(): String {
        val border = ua.ukrainedrones.UKRAINE_BORDER
        val closedRing = if (border.isNotEmpty() && (border.first().lat != border.last().lat || border.first().lon != border.last().lon)) {
            border + border.first()
        } else {
            border
        }
        val ukraineRing = closedRing.joinToString(",") { "[${it.lon},${it.lat}]" }
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
     * Oblast IDs and raion-key parents are canonical boundary IDs — exact set equality applies.
     */
    fun alertRegions(
        oblastIds: Set<String>,
        raionKeys: Set<Pair<String, String>> = emptySet()
    ): String {
        if (oblastIds.isEmpty() && raionKeys.isEmpty()) return EMPTY

        val features = mutableListOf<String>()

        // Track which oblast IDs actually produced a drawable fill — never trust set
        // membership alone. An oblast ID can be present (it resolved a canonical ID fine
        // upstream in ThreatEngine) yet still draw zero features here if its polygon has no
        // ring with >=3 points. Raions below must only be skipped when the parent oblast
        // *really* rendered, or a boundary/ring failure on the oblast silently blacks out
        // every raion inside it — which is exactly what happened to Donetsk: 8 correctly
        // resolved raion polygons were dropped because "donetska" was also in oblastIds,
        // with no fallback when the oblast polygon didn't actually draw.
        val renderedOblastIds = mutableSetOf<String>()
        for (id in oblastIds) {
            val poly = CompactOblastBoundaries.get(id)
            if (poly == null) {
                fillDebugLog("alertRegions: no boundary polygon for oblast id=$id")
                continue
            }
            var addedAny = false
            for (ring in poly.rings) {
                if (ring.pointCount < 3) continue
                val pts = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$pts]]}}""")
                addedAny = true
            }
            if (addedAny) {
                renderedOblastIds.add(id)
            } else {
                fillDebugLog("alertRegions: oblast id=$id resolved but every ring had <3 points")
            }
        }

        for ((id, raion) in raionKeys) {
            if (id in renderedOblastIds) continue
            val poly = CompactRaionBoundaries.forKey(id, raion)
            if (poly == null) {
                fillDebugLog("alertRegions: no boundary polygon for raion id=$id/$raion")
                continue
            }
            for (ring in poly.rings) {
                if (ring.pointCount < 3) continue
                val pts = ring.toPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$pts]]}}""")
            }
        }

        if (features.isEmpty() && (oblastIds.isNotEmpty() || raionKeys.isNotEmpty())) {
            fillDebugLog("alertRegions: produced 0 features from oblastIds=$oblastIds raionKeys=$raionKeys — fill will be invisible")
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