package ua.ukrainedrones

import android.graphics.Point
import kotlin.math.abs
import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import ua.ukrainedrones.engine.destinationPoint

/**
 * Single owner of camera-move policy. All pan/fit/zoom decisions in the map layer funnel through
 * here instead of touching the map controller directly, so competing drivers (follow-me, reveal,
 * zone refits, user taps) can't stomp each other with stale or racing animations. It owns:
 *
 *  - the *pending fit*: the reveal/anchor frame that owes one refinement pass once the popup
 *    card's real height is measured a frame later (the source of the old "camera flies to GPS"
 *    bug — a stale target was re-animated by any later card opening);
 *  - the *follow-me lock*: after an anchor/reveal fit, GPS-drift camera follows hold still until
 *    the fit's animation has settled.
 */
internal class MapCameraCoordinator {

    /** A one-shot frame awaiting the popup card's measured height (refined exactly once). */
    data class PendingFit(
        val id: String?,
        val lat: Double,
        val lon: Double,
        val focusLat: Double,
        val focusLon: Double,
        val tick: Long
    )

    /** Non-null while a reveal/anchor fit still owes its refinement pass. Cleared on refinement,
     *  on deselect, and when selection moves to a different threat. */
    var pendingFit: PendingFit? = null
        private set

    /** Until this time the follow-me camera must hold still so it can't fight a fit in flight. */
    var lockUntilMs: Long = 0
        private set

    /** Single-threat anchor: the selected threat always settles at ANCHOR_Y_FRACTION of the
     *  visible band, horizontally centred, keeping the current zoom (pan-only). */
    fun anchorThreat(map: MapView, lat: Double, lon: Double, topCoverPx: Int, bottomCoverPx: Int) {
        if (map.width <= 0 || map.height <= 0) return
        val band = map.height - topCoverPx - bottomCoverPx
        if (band <= 0) {
            map.controller.animateTo(GeoPoint(lat, lon))
            return
        }
        val p = Point()
        map.projection.toPixels(GeoPoint(lat, lon), p)
        val targetY = topCoverPx + band * ANCHOR_Y_FRACTION
        val dyPx = targetY - p.y
        if (abs(dyPx) < 1f) return // already at the anchor — don't micro-jitter
        val res = TileSystem.GroundResolution(lat.coerceIn(-85.0, 85.0), map.zoomLevelDouble)
        val dyDeg = dyPx * res / DEGREES_PER_METER
        map.controller.animateTo(
            GeoPoint((lat - dyDeg).coerceIn(-89.9, 89.9), lon)
        )
    }

    /** Dual-frame reveal fit: the focus point (GPS/city) near the top, the threat near the
     *  bottom, spaced and zoomed to the gap between them. May be refined by [refinePendingFit]
     *  once the popup card is measured. */
    fun fitReveal(
        map: MapView,
        lat: Double,
        lon: Double,
        focusLat: Double,
        focusLon: Double,
        topCoverPx: Int,
        bottomCoverPx: Int
    ) {
        if (map.width <= 0 || map.height <= 0) return
        if (!focusLat.isFinite() || !focusLon.isFinite()) {
            map.zoomToBoundingBox(singleThreatBox(lat, lon), true)
            return
        }
        map.zoomToBoundingBox(
            revealBoundingBox(lat, lon, focusLat, focusLon, map.height, topCoverPx, bottomCoverPx),
            true
        )
    }

    /** One-shot host record for a reveal/anchor; also re-arms the follow-me suppression. */
    fun armFit(id: String?, lat: Double, lon: Double, focusLat: Double, focusLon: Double, tick: Long) {
        pendingFit = PendingFit(id, lat, lon, focusLat, focusLon, tick)
        lockUntilMs = System.currentTimeMillis() + CAMERA_FIT_LOCK_MS
    }

    /** Re-run the pending fit with the just-measured popup card height (refined exactly once). */
    fun refinePendingFit(map: MapView, selectedId: String?, topCoverPx: Int, bottomCoverPx: Int) {
        val p = pendingFit ?: return
        if (p.id != selectedId) return
        if (map.width <= 0 || map.height <= 0) return
        if (p.focusLat.isFinite() && p.focusLon.isFinite()) {
            fitReveal(map, p.lat, p.lon, p.focusLat, p.focusLon, topCoverPx, bottomCoverPx)
        } else {
            anchorThreat(map, p.lat, p.lon, topCoverPx, bottomCoverPx)
        }
        consumeFit()
    }

    /** The pending fit was refined (or never would be); drop it and release the follow lock. */
    fun consumeFit() {
        pendingFit = null
        lockUntilMs = 0
    }

    /** Selection moved elsewhere — the pending target is stale and must never animate later. */
    fun clearFit() {
        pendingFit = null
    }

    /** The follow-me camera may pan again after an anchor/reveal lock window. */
    fun isFollowLocked(nowMs: Long) = nowMs < lockUntilMs

    /** Fit a bounding box that may have been shifted for a bottom overlay (zone fit, etc.). */
    fun fitBox(map: MapView, box: BoundingBox, animate: Boolean = true) {
        if (map.width <= 0 || map.height <= 0) return
        map.zoomToBoundingBox(box, animate)
    }

    /** Centre + zoom so the whole circle of [radiusKm] around [geo] sits on screen. */
    fun fitZone(map: MapView, geo: IGeoPoint, radiusKm: Double, animate: Boolean = true) {
        if (map.width <= 0 || map.height <= 0) return
        map.zoomToBoundingBox(zoneBox(geo, radiusKm), animate)
    }

    /** Centre + zoom so the yellow zone sits in the visible area ABOVE the zones sheet. */
    fun fitZoneToPanel(map: MapView, geo: IGeoPoint, slowYellowKm: Double, sheetCoverPx: Int) {
        if (map.width <= 0 || map.height <= 0) return
        val zone = zoneBox(geo, slowYellowKm)
        val visibleFrac = if (map.height > 0 && sheetCoverPx > 0) {
            (1f - sheetCoverPx / map.height.toFloat()).coerceIn(0.3f, 1f)
        } else 0.6f
        val dLat = zone.latNorth - geo.latitude
        val southPad = dLat * 2 * ((1f / visibleFrac) - 1f)
        map.zoomToBoundingBox(
            BoundingBox(zone.latNorth, zone.lonEast, zone.latSouth - southPad, zone.lonWest),
            true
        )
    }

    /** Centre the camera on [geo] at an explicit zoom (shelter entry / fallback pans). */
    fun animateTo(map: MapView, geo: IGeoPoint, zoom: Double, ms: Long) {
        if (map.width <= 0 || map.height <= 0) return
        map.controller.animateTo(geo, zoom, ms)
    }

    companion object {
        /** Selected threat settles 75% down the band between the top card and bottom sheet. */
        private const val ANCHOR_Y_FRACTION = 0.75f
        /** How long after an anchor/reveal fit the follow-me camera stays out of the way. */
        private const val CAMERA_FIT_LOCK_MS = 1_200L
        private const val DEGREES_PER_METER = 111_320.0
        private const val REVEAL_SPAN_LAT_CAP = 40.0
        private const val REVEAL_SPAN_LON_CAP = 80.0
    }

    /** Latitude/longitude box that fits a zone circle of [radiusKm] around [center], with the
     *  same 5% margin the map previously used. */
    private fun zoneBox(center: IGeoPoint, radiusKm: Double): BoundingBox {
        if (radiusKm <= 0.0) return singleThreatBox(center.latitude, center.longitude)
        val marginM = radiusKm * 1000.0 * 1.05
        val north = destinationPoint(center.latitude, center.longitude, marginM, 0.0)
        val east = destinationPoint(center.latitude, center.longitude, marginM, 90.0)
        val south = destinationPoint(center.latitude, center.longitude, marginM, 180.0)
        val west = destinationPoint(center.latitude, center.longitude, marginM, 270.0)
        return BoundingBox(north.lat, east.lon, south.lat, west.lon)
    }

    /** A tight-ish single-threat frame (no focus point) so a lone threat doesn't over-zoom. */
    private fun singleThreatBox(lat: Double, lon: Double): BoundingBox {
        val span = 0.5
        return BoundingBox(lat + span, lon + span, lat - span, lon - span)
    }

    /** Focus near the top, threat near the bottom, clamped span so a huge gap (or zero gap)
     *  still yields a valid zoomable box. The threat is always pinned to the bottom fraction;
     *  when the popup card (top) or zones sheet (bottom) has been measured, the fractions shrink
     *  to the actual visible band so the threat never hides under an overlay. */
    private fun revealBoundingBox(
        lat: Double,
        lon: Double,
        focusLat: Double,
        focusLon: Double,
        mapHeightPx: Int,
        topCoverPx: Int,
        bottomCoverPx: Int
    ): BoundingBox {
        val ft: Float
        val fb: Float
        if (mapHeightPx > 0 && (topCoverPx > 0 || bottomCoverPx > 0)) {
            val topFrac = (topCoverPx.toFloat() / mapHeightPx).coerceIn(0f, 0.45f)
            val bottomFrac = (bottomCoverPx.toFloat() / mapHeightPx).coerceIn(0f, 0.45f)
            ft = (topFrac + 0.05f).coerceAtMost(0.45f)
            fb = (1f - bottomFrac - 0.05f).coerceAtLeast(0.55f)
        } else {
            ft = 0.28f
            fb = 0.72f
        }
        val g = fb - ft
        val gapLat = abs(focusLat - lat)
        val spanLat = maxOf(gapLat / g, REVEAL_MIN_SPAN_LAT).coerceAtMost(REVEAL_SPAN_LAT_CAP)
        val north = maxOf(focusLat + ft * spanLat, lat + fb * spanLat)
        val south = minOf(focusLat - (1 - ft) * spanLat, lat - (1 - fb) * spanLat)
        val lonMid = (focusLon + lon) / 2
        val gapLon = abs(focusLon - lon)
        val spanLon = maxOf(gapLon / g, REVEAL_MIN_SPAN_LON).coerceAtMost(REVEAL_SPAN_LON_CAP)
        return BoundingBox(
            north.coerceAtMost(85.0), lonMid + spanLon / 2,
            south.coerceAtLeast(-85.0), lonMid - spanLon / 2
        )
    }
}