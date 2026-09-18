package ua.ukrainedrones.ui

import ua.ukrainedrones.UKRAINE_LAND_BORDER
import ua.ukrainedrones.community.CompactOblastBoundaries
import ua.ukrainedrones.community.CompactRaionBoundaries
import ua.ukrainedrones.community.LatLon
import ua.ukrainedrones.community.ScaledRing
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import ua.ukrainedrones.engine.destinationPoint
import kotlin.math.abs

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
        // Logcat write is best-effort: android.util.Log throws in plain JVM unit tests,
        // where only the in-app ApiMonitor record matters.
        runCatching { android.util.Log.w("MapLibreGeoJson", message) }
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
     * Minimum ring area (square degrees) for a ring to be emitted as a fill polygon.
     * 1e-4 sq deg is ~0.8 km² (under a kilometer across) — far below any visible
     * administrative polygon, so only sub-pixel quantization slivers are dropped.
     */
    private const val MIN_RING_AREA_SQ_DEG = 1e-4

    /** Signed ring area via the shoelace formula. Positive = counter-clockwise. */
    private fun signedArea(points: List<LatLon>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            sum += points[i].lon * points[j].lat - points[j].lon * points[i].lat
        }
        return sum / 2.0
    }

    /**
     * Returns ring points normalized for emission as a single-ring GeoJSON Polygon,
     * or null when the ring is degenerate and must not be emitted.
     *
     * Per RFC 7946 a Polygon exterior ring must wind counter-clockwise. The compact
     * boundary data stores multi-ring oblasts (islands, coastline fragments, slivers)
     * with arbitrary winding, and some rings are near-zero-area quantization slivers.
     * Emitting those verbatim poisons MapLibre's native tile builder for the whole
     * source — blanking both fill and line layers — so they are dropped here and
     * every surviving ring is forced to CCW.
     */
    private fun normalizedRingPoints(ring: ScaledRing, label: String): List<LatLon>? {
        if (ring.pointCount < 3) return null
        val pts = ring.toPoints()
        val area = signedArea(pts)
        if (abs(area) < MIN_RING_AREA_SQ_DEG) {
            fillDebugLog("alertRegions: dropping degenerate ring (area=$area, points=${ring.pointCount}) for $label")
            return null
        }
        return if (area < 0) pts.asReversed() else pts
    }

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
            for ((index, ring) in poly.rings.withIndex()) {
                val pts = normalizedRingPoints(ring, "oblast id=$id ring=$index") ?: continue
                val coords = pts.joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]}}""")
                addedAny = true
            }
            if (addedAny) {
                renderedOblastIds.add(id)
            } else {
                fillDebugLog("alertRegions: oblast id=$id resolved but every ring degenerate or <3 points")
            }
        }

        for ((id, raion) in raionKeys) {
            if (id in renderedOblastIds) continue
            val poly = CompactRaionBoundaries.forKey(id, raion)
            if (poly == null) {
                fillDebugLog("alertRegions: no boundary polygon for raion id=$id/$raion")
                continue
            }
            for ((index, ring) in poly.rings.withIndex()) {
                val pts = normalizedRingPoints(ring, "raion id=$id/$raion ring=$index") ?: continue
                val coords = pts.joinToString(",") { "[${it.lon},${it.lat}]" }
                features.add("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]}}""")
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