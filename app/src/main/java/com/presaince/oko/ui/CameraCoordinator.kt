package com.presaince.oko

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.cos
import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.destinationPoint
import com.presaince.oko.ui.MapLibreBridge

/** Bounding box for camera fitting operations. */
data class CameraBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double
)

/**
 * Single owner of camera-move policy. All pan/fit/zoom decisions in the map layer funnel through
 * here instead of touching the map controller directly, so competing drivers (follow-me, reveal,
 * zone refits, user taps) can't stomp each other with stale or racing animations.
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

    /** Monotonic bump on every [armFit] — lets the map retrigger the single deferred camera
     *  move for each new reveal/anchor request (first open, switch, reveal on a selected
     *  threat, reopen). */
    var pendingVersion: Int = 0
        private set

    /** MapLibre camera fit for dual-frame reveal. */
    fun fitReveal(
        bridge: MapLibreBridge,
        lat: Double,
        lon: Double,
        focusLat: Double,
        focusLon: Double,
        mapHeightPx: Int,
        topCoverPx: Int,
        bottomCoverPx: Int
    ) {
        if (!focusLat.isFinite() || !focusLon.isFinite()) {
            val box = singleThreatBox(lat, lon)
            bridge.zoomToBounds(box.north, box.east, box.south, box.west)
            return
        }
        val box = revealBoundingBox(lat, lon, focusLat, focusLon, mapHeightPx, topCoverPx, bottomCoverPx)
        bridge.zoomToBounds(box.north, box.east, box.south, box.west)
    }

    /** MapLibre camera anchor for single threat. */
    fun anchorThreat(bridge: MapLibreBridge, lat: Double, lon: Double) {
        bridge.animateTo(lat, lon)
    }

    /** One-shot host record for a reveal/anchor; also re-arms the follow-me suppression. */
    fun armFit(id: String?, lat: Double, lon: Double, focusLat: Double, focusLon: Double, tick: Long) {
        pendingFit = PendingFit(id, lat, lon, focusLat, focusLon, tick)
        lockUntilMs = System.currentTimeMillis() + CAMERA_FIT_LOCK_MS
        pendingVersion++
    }

    /** Re-run the pending fit on MapLibre with measured card height. */
    fun refinePendingFit(bridge: MapLibreBridge, selectedId: String?, mapHeightPx: Int, topCoverPx: Int, bottomCoverPx: Int) {
        val p = pendingFit ?: return
        if (p.id != selectedId) return
        if (p.focusLat.isFinite() && p.focusLon.isFinite()) {
            fitReveal(bridge, p.lat, p.lon, p.focusLat, p.focusLon, mapHeightPx, topCoverPx, bottomCoverPx)
        } else {
            anchorThreat(bridge, p.lat, p.lon)
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

    /** MapLibre fit for bounding box. */
    fun fitBox(
        bridge: MapLibreBridge,
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        paddingPx: Int = 80,
        durationMs: Int = 400
    ) {
        bridge.zoomToBounds(north, east, south, west, paddingPx, durationMs)
    }

    /** MapLibre fit for zone circle of [radiusKm] around (lat, lon). */
    fun fitZone(bridge: MapLibreBridge, lat: Double, lon: Double, radiusKm: Double, animate: Boolean = true) {
        val box = zoneBox(lat, lon, radiusKm)
        bridge.zoomToBounds(box.north, box.east, box.south, box.west, durationMs = if (animate) 400 else 0)
    }

    /** MapLibre centre + zoom so the yellow zone sits in the visible area ABOVE the zones sheet. */
    fun fitZoneToPanel(
        bridge: MapLibreBridge,
        lat: Double,
        lon: Double,
        slowYellowKm: Double,
        sheetCoverPx: Int
    ) {
        val height = bridge.height
        val zone = zoneBox(lat, lon, slowYellowKm)
        val visibleFrac = if (height > 0 && sheetCoverPx > 0) {
            (1f - sheetCoverPx / height.toFloat()).coerceIn(0.3f, 1f)
        } else 0.6f
        val dLat = zone.north - lat
        val southPad = dLat * 2 * ((1f / visibleFrac) - 1f)
        bridge.zoomToBounds(zone.north, zone.east, zone.south - southPad, zone.west)
    }

    /** MapLibre animate camera to [lat], [lon] at [zoom]. */
    fun animateTo(bridge: MapLibreBridge, lat: Double, lon: Double, zoom: Double? = null, ms: Long = 400) {
        bridge.animateTo(lat, lon, zoom, ms.toInt())
    }

    companion object {
        /** How long after an anchor/reveal fit the follow-me camera stays out of the way. */
        private const val CAMERA_FIT_LOCK_MS = 1_200L
        private const val REVEAL_MIN_SPAN_LAT = 0.8
        private const val REVEAL_MIN_SPAN_LON = 1.2
        private const val REVEAL_SPAN_LAT_CAP = 40.0
        private const val REVEAL_SPAN_LON_CAP = 80.0
    }

    /** Latitude/longitude box that fits a zone circle of [radiusKm] around (lat, lon). */
    private fun zoneBox(lat: Double, lon: Double, radiusKm: Double): CameraBounds {
        if (radiusKm <= 0.0) return singleThreatBox(lat, lon)
        val marginM = radiusKm * 1000.0 * 1.05
        val north = destinationPoint(lat, lon, marginM, 0.0)
        val east = destinationPoint(lat, lon, marginM, 90.0)
        val south = destinationPoint(lat, lon, marginM, 180.0)
        val west = destinationPoint(lat, lon, marginM, 270.0)
        return CameraBounds(north.lat, east.lon, south.lat, west.lon)
    }

    /** A tight-ish single-threat frame (no focus point) so a lone threat doesn't over-zoom. */
    private fun singleThreatBox(lat: Double, lon: Double): CameraBounds {
        val span = 0.5
        return CameraBounds(lat + span, lon + span, lat - span, lon - span)
    }

    /** Focus near the top, threat near the bottom, clamped span so a huge gap (or zero gap)
     *  still yields a valid zoomable box. */
    private fun revealBoundingBox(
        lat: Double,
        lon: Double,
        focusLat: Double,
        focusLon: Double,
        mapHeightPx: Int,
        topCoverPx: Int,
        bottomCoverPx: Int
    ): CameraBounds {
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
        return CameraBounds(
            north.coerceAtMost(85.0), lonMid + spanLon / 2,
            south.coerceAtLeast(-85.0), lonMid - spanLon / 2
        )
    }
}