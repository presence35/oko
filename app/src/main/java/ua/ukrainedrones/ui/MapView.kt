package ua.ukrainedrones

import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.threatTypeInfoByString
import ua.ukrainedrones.engine.distanceFlat
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.source.RESOLVED_REPLAY_GRACE_MS
import ua.ukrainedrones.courseTargetPlace
import ua.ukrainedrones.community.CompactOblastBoundaries
import ua.ukrainedrones.community.CompactPolygon
import ua.ukrainedrones.community.CompactRaionBoundaries

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.api.IGeoPoint
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import ua.ukrainedrones.UA_TIGHT_MIN_LAT
import ua.ukrainedrones.UA_TIGHT_MAX_LAT
import ua.ukrainedrones.UA_TIGHT_MIN_LON
import ua.ukrainedrones.UA_TIGHT_MAX_LON
import ua.ukrainedrones.ODESA_LAT
import ua.ukrainedrones.ODESA_LON
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.TileSystem
import org.osmdroid.util.TileSystemWebMercator
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val CARTO_BASE_URLS =
    arrayOf("a", "b", "c", "d").map { sub -> "https://$sub.basemaps.cartocdn.com/dark_nolabels/" }.toTypedArray()

/** v2: the id bump invalidates cached "API KEY REQUIRED" error tiles from the
 *  unauthenticated period.
 *
 *  The API key must be appended AFTER z/x/y.png — osmdroid builds
 *  `baseUrl + zoom/x/y.png`, so a key on the base URL lands mid-path and every
 *  request 404s (all-black map). */
internal val DARK_TILE_SOURCE = object : OnlineTileSourceBase(
    "CartoDB_DarkNoLabels_v2", 0, 17, 256, ".png",
    CARTO_BASE_URLS,
    "© OpenStreetMap contributors © CARTO"
) {
    private val keySuffix = BuildConfig.CARTO_API_KEY
        .takeIf { it.isNotBlank() }
        ?.let { "?key=$it" }
        .orEmpty()

    override fun getTileURLString(pMapTileIndex: Long): String =
        getBaseUrl() +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) +
            ".png" + keySuffix
}

/** Odesa city centre — fallback camera target before the first GPS fix. */
private val DEFAULT_CENTER = GeoPoint(ODESA_LAT, ODESA_LON)

/** Max zoom outside shelter mode — the ~5 km threat-map viewport; deeper zoom is pointless
 *  for the threat map and just bloats the tile cache. */
private const val NORMAL_MAX_ZOOM = 14.5

/** Deep zoom, unlocked only while the shelter overlay is up (street-level shelter detail). */
private const val SHELTER_MAX_ZOOM = 19.0

/** Zooming below this level makes shelter pins clutter — auto-exit shelter mode. */
private const val SHELTER_AUTO_EXIT_ZOOM = 13.0

/** Ukraine (incl. Crimea) plus a ~0.5° margin — used to floor the zoom so Ukraine fills the screen. */
private val UA_VIEW_LIMITS = BoundingBox(UA_TIGHT_MAX_LAT, UA_TIGHT_MAX_LON, UA_TIGHT_MIN_LAT, UA_TIGHT_MIN_LON)

/** Pan boundary: the tight box plus ~1.5° more so the viewport can shift behind the top threat
 *  card / overlays when zoomed at a country edge, instead of the map getting stuck under them.
 *  Stays within the wide tile coverage (UA_WIDE ~2°), so the extra strip still renders. */
private val UA_PAN_LIMITS = BoundingBox(
    UA_TIGHT_MAX_LAT + 1.5, UA_TIGHT_MAX_LON + 1.5,
    UA_TIGHT_MIN_LAT - 1.5, UA_TIGHT_MIN_LON - 1.5
)

private val tileSystem = TileSystemWebMercator()

/** Bounding box that fits a zone circle centred on `center`, with a 5% margin. */
private fun zoneBoundingBox(center: IGeoPoint, radiusKm: Double): BoundingBox {
    val marginM = radiusKm * 1000.0 * 1.05
    val north = ua.ukrainedrones.engine.destinationPoint(center.latitude, center.longitude, marginM, 0.0)
    val east = ua.ukrainedrones.engine.destinationPoint(center.latitude, center.longitude, marginM, 90.0)
    val south = ua.ukrainedrones.engine.destinationPoint(center.latitude, center.longitude, marginM, 180.0)
    val west = ua.ukrainedrones.engine.destinationPoint(center.latitude, center.longitude, marginM, 270.0)
    return BoundingBox(north.lat, east.lon, south.lat, west.lon)
}

/** Bounding box over the nearest shelters, padded so every marker is comfortably in view. */
private fun sheltersBoundingBox(near: List<NearestShelter>): BoundingBox? {
    if (near.isEmpty()) return null
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var minLon = Double.MAX_VALUE
    var maxLon = -Double.MAX_VALUE
    for (n in near) {
        minLat = minOf(minLat, n.shelter.lat); maxLat = maxOf(maxLat, n.shelter.lat)
        minLon = minOf(minLon, n.shelter.lon); maxLon = maxOf(maxLon, n.shelter.lon)
    }
    val pad = maxOf((maxLat - minLat) * 0.15, (maxLon - minLon) * 0.15, 0.004)
    return BoundingBox(maxLat + pad, maxLon + pad, minLat - pad, minLon - pad)
}

private fun StringBuilder.appendThreatKey(t: NormalizedThreat) {
    // Identity + lifecycle only. Continuously-changing fields (lat/lon/courseDeg) are
    // deliberately excluded: they churn on nearly every WebSocket frame during an alert,
    // defeating the key's whole purpose (avoid clears + full rebuilds). Position smoothing
    // and course/staleness rendering happen in-place in the 1s marker loop instead.
    // Staleness is NOT included in the key (would churn on every tick) — marker loop
    // handles dimming in-place via alpha.
    append(t.id).append('@').append(t.status).append('@').append('L').append(';')
}

// Bounded cache for rendered marker-icon BITMAPS — key "type|iconSet|revealed". Rendering a
// fresh bitmap per call (marker rebuilds, reveal swaps, every replay bullet) churned
// allocations for identical results; density is fixed per process so it needs no key slot.
// Only the bitmap is shared: callers get their own BitmapDrawable wrapper because the death
// animation mutates its icon's alpha per frame and must never touch a live marker's icon.
private val threatIconCache = object : LruCache<String, Bitmap>(48) {}

/** Threat marker icon size tracks map zoom (1x at low zoom → 3x only by the final ~3 zoom
 *  levels before max, so icons stay small at normal view zooms and never balloon early),
 *  quantized to 8dp steps so a pinch changes size in few, deliberate jumps. */
private fun threatIconSizeDp(zoom: Double): Int {
    val scale = ((zoom - 11.5) / 3.0 * 2.0 + 1.0).coerceIn(1.0, 3.0)
    return (32.0 * scale / 8.0).roundToInt() * 8
}

/** Position for the "approaching, precision unknown" orbit: a point on the yellow ring around
 *  [center] (the destination city), advancing the angle over time so the icon patrols the ring. */
private fun orbitPosition(center: LatLng, radiusMeters: Double, angleRad: Double): LatLng {
    val bearing = (Math.toDegrees(angleRad) + 360.0) % 360.0
    return ua.ukrainedrones.engine.destinationPoint(center.lat, center.lon, radiusMeters, bearing)
}

/** The destination city an approximate-position threat is heading toward, resolved from its
 *  course text (e.g. "Шахеди курсом на Чорноморськ" → Chornomorsk's coords), or null. */
private fun orbitCenter(nt: NormalizedThreat): LatLng? {
    if (nt.areaOnly || nt.positionQuality != "approx") return null
    val place = courseTargetPlace(nt.explanationShort) ?: return null
    return Cities.findCity(place)?.let { LatLng(it.lat, it.lon) }
}

/** An approximate-position threat heading toward a known city circles that city's yellow ring
 *  (precision unknown = warning), but only while it is NOT yet inside the red zone — once it's
 *  close enough to be spotted, better coordinates exist and it should park on its raw fix. */
private fun shouldOrbitDestination(nt: NormalizedThreat, destination: LatLng, redKm: Int): Boolean {
    if (nt.areaOnly || nt.positionQuality != "approx") return false
    return distanceFlat(destination.lat, destination.lon, nt.lat, nt.lon) / 1000.0 > redKm
}

/**
 * Marker rotation that points a threat icon's nose along [courseDeg] (compass bearing, clockwise
 * from north) given the icon art's baked-in facing [baseDeg]. osmdroid renders `marker.rotation`
 * negated (its `Marker.draw` passes `-mBearing` into `Canvas.rotate`), so the rotation must be the
 * negative of the compass offset — otherwise east/west courses render mirrored (icon flying tail-first).
 */
internal fun threatMarkerRotation(courseDeg: Float, baseDeg: Float): Float =
    -((courseDeg - baseDeg + 360f) % 360f)

/** Threat marker icon at a size that scales with zoom. When [revealed], draws a small
 *  green dot in the icon's top-right corner — the notification-reveal marker — so it's a single
 *  tappable marker (no separate overlay intercepting the tap) that moves with the threat. */
private fun threatIconFor(
    context: Context,
    type: ThreatType,
    iconSet: ThreatIconSet,
    revealed: Boolean = false,
    areaOnly: Boolean = false,
    sizeDp: Int = 32
): Drawable {
    val key = "${type.name}|${iconSet.name}|$revealed|$areaOnly|$sizeDp"
    val cached = threatIconCache.get(key)
    val bmp: Bitmap
    if (cached != null) {
        bmp = cached
    } else {
        val src = ContextCompat.getDrawable(context, IconCatalog.res(type, iconSet))!!
        val density = context.resources.displayMetrics.density
        val targetW = (sizeDp * density).toInt().coerceAtLeast(2)
        val iw = src.intrinsicWidth.coerceAtLeast(1)
        val ih = src.intrinsicHeight.coerceAtLeast(1)
        val w = targetW
        val h = (ih.toFloat() * targetW / iw).toInt().coerceAtLeast(1)
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        src.setBounds(0, 0, w, h)
        src.draw(canvas)
        if (revealed || areaOnly) {
            val r = 4f * density
            val cx = w - r - 1.5f * density
            val cy = r + 1.5f * density
            // areaOnly gets an amber dot (oblast-level uncertainty); revealed gets green.
            val dotColor = if (areaOnly) Color.rgb(255, 183, 77) else Color.rgb(76, 175, 80)
            canvas.drawCircle(cx, cy, r, Paint().apply {
                isAntiAlias = true
                color = dotColor
            })
            // Small white core so the dot reads on any icon colour.
            canvas.drawCircle(cx, cy, r * 0.45f, Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
            })
        }
        threatIconCache.put(key, bmp)
    }
    return BitmapDrawable(context.resources, bmp)
}

private fun zoneColor(zone: ThreatZone?): Int = when (zone) {
    ThreatZone.INNER -> Color.rgb(255, 82, 82)
    ThreatZone.OUTER -> Color.rgb(255, 215, 64)
    null -> Color.rgb(158, 158, 158)
}

/** Classic "blue glowing dot" used as the GPS location icon once a fix exists; a muted gray
 *  dot stands in while the first fix hasn't arrived yet (the "locating you" state). */
private fun gpsDotBitmap(context: Context, hasFix: Boolean): Bitmap {
    val density = context.resources.displayMetrics.density
    val coreR = 4f * density
    val glowR = coreR * 2.8f
    val size = (glowR * 2).toInt().coerceAtLeast(2)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val (glowA, glowRgb) = if (hasFix) {
        Color.argb(120, 33, 150, 243) to intArrayOf(33, 150, 243)
    } else {
        Color.argb(110, 158, 158, 158) to intArrayOf(158, 158, 158)
    }
    val glow = Paint().apply {
        shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(glowA, Color.argb(0, glowRgb[0], glowRgb[1], glowRgb[2])),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(cx, cy, glowR, glow)
    canvas.drawCircle(cx, cy, coreR, Paint().apply {
        isAntiAlias = true
        color = if (hasFix) Color.rgb(33, 150, 243) else Color.rgb(158, 158, 158)
    })
    canvas.drawCircle(cx, cy, coreR * 0.55f, Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeWidth = 1.5f * density
        color = Color.WHITE
    })
    return bmp
}

/** Map pin with the tip at the bottom centre — anchors the pinned city precisely. */
private fun pinBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val w = (30 * density).toInt()
    val h = (42 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(w / 2f, h.toFloat())
        cubicTo(w * 0.24f, h * 0.62f, 0f, h * 0.38f, 0f, h * 0.30f)
        cubicTo(0f, h * 0.08f, w * 0.22f, 0f, w / 2f, 0f)
        cubicTo(w * 0.78f, 0f, w.toFloat(), h * 0.08f, w.toFloat(), h * 0.30f)
        cubicTo(w.toFloat(), h * 0.38f, w * 0.76f, h * 0.62f, w / 2f, h.toFloat())
        close()
    }
    canvas.drawPath(path, Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.rgb(0, 91, 187)
    })
    canvas.drawPath(path, Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    })
    val innerR = (4.6f * density)
    canvas.drawCircle(w / 2f, h * 0.28f, innerR, Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    })
    canvas.drawCircle(w / 2f, h * 0.28f, innerR * 0.55f, Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.rgb(255, 213, 0)
    })
    return bmp
}

/** Polygon approximation of a circle around [center] — osmdroid has no native circle overlay. */
private fun circlePoints(center: GeoPoint, radiusMeters: Double, segments: Int = 64): List<GeoPoint> {
    return List(segments) { i ->
        val bearing = 360.0 * i / segments
        val p = ua.ukrainedrones.engine.destinationPoint(center.latitude, center.longitude, radiusMeters, bearing)
        GeoPoint(p.lat, p.lon)
    }
}

/** A revealed threat that is still highlighted with the green dot badge. */
private data class NewRingState(val id: String?, val activeUntilMs: Long)

private const val NEW_RING_MS = 8_000L
/** How long the zone-slider camera refit waits after the value stops changing. */
private const val ZONE_REFIT_DEBOUNCE_MS = 350L
/** One full orbit of an approximate-position threat around its destination city's yellow ring. */
private const val ORBIT_PERIOD_MS = 15_000L
/** Orbit angle offset keyed by threat id so nearby threats don't patrol in lockstep. */
private fun orbitPhase(id: String): Double {
    val deg = Math.floorMod(id.hashCode(), 360)
    return Math.toRadians(deg.toDouble())
}

/** Orbit angle for [id] at wall-clock [now] (a full ring every ORBIT_PERIOD_MS). */
internal fun orbitAngle(now: Long, id: String): Double =
    (now / ORBIT_PERIOD_MS.toDouble()) * 2.0 * Math.PI + orbitPhase(id)

/** Compass bearing of the orbit's direction of travel at [angleRad]. The ring is patrolled
 *  clockwise — position is focus + r·(cos a north, sin a east), so the tangent is atan2(cos a, −sin a). */
internal fun orbitTangentBearing(angleRad: Double): Double {
    val deg = Math.toDegrees(atan2(cos(angleRad), -sin(angleRad)))
    return (deg + 360.0) % 360.0
}

/** How a threat is shown on the map right now. */
internal enum class ThreatPoseMode { PARKED, ORBIT, DRIFT }

/** Desired on-map pose for a threat: position + compass heading. Parked threats hold their raw
 *  fix with the reported course; orbiting threats follow the destination city's yellow ring
 *  (tangent heading); drifting threats dead-reckon along their course. Single source used by both
 *  the rebuild and the animation loop so placement and facing always agree. */
internal data class MarkerPose(
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val mode: ThreatPoseMode
)

internal fun resolveThreatPose(
    engine: ThreatEngine,
    t: NormalizedThreat,
    props: ThreatProps,
    redKm: Int,
    yellowKm: Int,
    now: Long
): MarkerPose {
    val drift = engine.canDrift(t, props, now)
    // An approximate-position threat heading toward a known city patrols that city's yellow ring
    // (the warning band where nobody has precise coords yet). The ring is centered on the
    // destination, not the user's focus, so editing zones never moves the threat to a different
    // city. Once it reaches the red zone, better coords are presumed and it parks on its fix.
    val destination = orbitCenter(t)
    if (destination != null) {
        if (!shouldOrbitDestination(t, destination, redKm)) {
            return MarkerPose(t.lat, t.lon, engine.courseDeg(t).toFloat(), ThreatPoseMode.PARKED)
        }
        if (drift) {
            val angle = orbitAngle(now, t.id)
            val pos = orbitPosition(destination, yellowKm * 1000.0, angle)
            return MarkerPose(pos.lat, pos.lon, orbitTangentBearing(angle).toFloat(), ThreatPoseMode.ORBIT)
        }
    }
    if (drift) {
        val predicted = engine.speedCache.estimate(t.id, t, props)
            ?.let { engine.predictPosition(t, it, props, now) }
        if (predicted != null) {
            return MarkerPose(predicted.lat, predicted.lon, engine.courseDeg(t).toFloat(), ThreatPoseMode.DRIFT)
        }
    }
    return MarkerPose(t.lat, t.lon, engine.courseDeg(t).toFloat(), ThreatPoseMode.PARKED)
}

/** Per-id de-overlap result: the marker position to use, or null when the threat is collapsed
 *  into a counted representative (COUNT mode) and should render no marker. [chip] is a count
 *  caption for representatives. */
private data class ThreatPlacement(val pos: GeoPoint?, val chip: String?)

/** Sub-description chip for a threat marker: the de-overlap count, prefixed with a SIM tag when
 *  the track is simulated so a fake threat is never mistaken for a live one. */
private fun chipLabel(t: NormalizedThreat, chip: String?): String? {
    val sim = if (t.simulated) "SIM" else null
    return when {
        sim != null && chip != null -> "$sim · $chip"
        sim != null -> sim
        else -> chip
    }
}

/** Deterministic screen-space de-overlap for threats sharing a coordinate: GRID spreads them on
 *  a small 2D grid, SPREAD fans them in a half-overlapping staggered row, COUNT collapses
 *  same-type stacks into one counted representative (mixed types auto-grid so each stays
 *  visible), DEFAULT keeps markers exactly on their positions. Returns id → placement. */
private fun deOverlapThreats(
    poses: List<Triple<String, LatLng, String>>,  // id, pose, type
    mapView: MapView,
    mode: OverlapMode,
    stepPx: Int
): Map<String, ThreatPlacement> {
    val raw = HashMap<String, ThreatPlacement>(poses.size)
    if (mode == OverlapMode.DEFAULT || mapView.width <= 0 || mapView.height <= 0) {
        for ((id, pose, _) in poses) raw[id] = ThreatPlacement(geoFromPixels(mapView, pose), null)
        return raw
    }

    val reuse = Point()
    val cells = HashMap<Pair<Int, Int>, MutableList<Triple<String, LatLng, String>>>()
    for (item in poses) {
        val (id, pose, _) = item
        mapView.projection.toPixels(GeoPoint(pose.lat, pose.lon), reuse)
        val key = (reuse.x / stepPx) to (reuse.y / stepPx)
        cells.getOrPut(key) { mutableListOf() }.add(item)
    }
    val out = HashMap<String, ThreatPlacement>(poses.size)
    for (members in cells.values) {
        val sorted = members.sortedBy { it.first }
        if (sorted.size == 1) {
            val (id, pose, _) = sorted[0]
            out[id] = ThreatPlacement(geoFromPixels(mapView, pose), null)
            continue
        }
        when (mode) {
            OverlapMode.GRID -> {
                val cols = kotlin.math.ceil(kotlin.math.sqrt(sorted.size.toDouble())).toInt().coerceAtLeast(1)
                val rows = kotlin.math.ceil(sorted.size / cols.toDouble()).toInt()
                sorted.forEachIndexed { i, (id, pose, _) ->
                    val dx = (i % cols - (cols - 1) / 2.0) * stepPx
                    val dy = (i / cols - (rows - 1) / 2.0) * stepPx
                    out[id] = ThreatPlacement(offsetFromPixels(mapView, pose, dx, dy), null)
                }
            }
            OverlapMode.SPREAD -> {
                val half = stepPx / 2.0
                sorted.forEachIndexed { i, (id, pose, _) ->
                    val dx = i * half
                    val dy = if (i % 2 == 0) 0.0 else half
                    out[id] = ThreatPlacement(offsetFromPixels(mapView, pose, dx, dy), null)
                }
            }
            OverlapMode.COUNT -> {
                val byType = LinkedHashMap<String, MutableList<Triple<String, LatLng, String>>>()
                for (m in sorted) byType.getOrPut(m.third) { mutableListOf() }.add(m)
                val reps = byType.values.map { it.first() }
                val cols = kotlin.math.ceil(kotlin.math.sqrt(reps.size.toDouble())).toInt().coerceAtLeast(1)
                val rows = kotlin.math.ceil(reps.size / cols.toDouble()).toInt()
                reps.forEachIndexed { i, (id, pose, type) ->
                    val dx = (i % cols - (cols - 1) / 2.0) * stepPx
                    val dy = (i / cols - (rows - 1) / 2.0) * stepPx
                    val count = byType.getValue(type).size
                    out[id] = ThreatPlacement(
                        offsetFromPixels(mapView, pose, dx, dy),
                        if (count > 1) "$count" else null
                    )
                }
                for (m in sorted) {
                    if (m.first !in out) out[m.first] = ThreatPlacement(null, null)
                }
            }
            else -> {}
        }
    }
    return out
}

private fun geoFromPixels(mapView: MapView, pose: LatLng): GeoPoint {
    val reuse = Point()
    mapView.projection.toPixels(GeoPoint(pose.lat, pose.lon), reuse)
    return GeoPoint(mapView.projection.fromPixels(reuse.x, reuse.y))
}

private fun offsetFromPixels(mapView: MapView, pose: LatLng, dxPx: Double, dyPx: Double): GeoPoint {
    val reuse = Point()
    mapView.projection.toPixels(GeoPoint(pose.lat, pose.lon), reuse)
    return GeoPoint(mapView.projection.fromPixels((reuse.x + dxPx).toInt(), (reuse.y + dyPx).toInt()))
}

/**
 * Instant single-tap detection for threat markers. osmdroid only delivers marker taps via
 * onSingleTapConfirmed, which waits out the ~300 ms double-tap window — a perceptible lag
 * before the haptic tick and the popup. This overlay sits on top of the threat markers and
 * fires on the immediate onSingleTapUp (finger-up) using the same Marker.hitTest the marker
 * itself would use, so the tick + card feel instant. It never consumes the touch stream
 * (pan/zoom/double-tap keep working); the markers' consume-only click listeners absorb
 * osmdroid's late confirmed tap so it never falls through to the map-tap overlay (which would
 * immediately close the popup).
 */
private class InstantThreatTapOverlay(
    private val mapView: MapView,
    private val markersProvider: () -> Collection<Marker>,
    private val hapticsOn: () -> Boolean,
    private val onThreatTap: (NormalizedThreat) -> Unit
) : Overlay() {
    private val detector = GestureDetector(mapView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            for (marker in markersProvider()) {
                if (marker.hitTest(e, mapView)) {
                    val threat = marker.relatedObject as? NormalizedThreat ?: continue
                    if (hapticsOn()) hapticTick(mapView.context)
                    onThreatTap(threat)
                    return true
                }
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        detector.onTouchEvent(event)
        return false
    }
}

private val shelterBitmapCache = mutableMapOf<String, Bitmap>()

/** Minimal hand-drawn chevron marker, stroke-only so it reads as a pin pointing at the spot.
 *  Selected (its card is open) switches to white; otherwise the shelter's type color. */
private fun shelterMarkerBitmap(
    context: Context,
    type: ShelterType,
    isSelected: Boolean
): Bitmap {
    val key = "${type.name}_$isSelected"
    shelterBitmapCache[key]?.let { return it }

    val density = context.resources.displayMetrics.density
    val typeColor = when (type) {
        ShelterType.MOBILE -> Color.rgb(255, 160, 0)  // Amber / Orange
        ShelterType.BASIC -> Color.rgb(76, 175, 80)   // Emerald Green
        ShelterType.BUNKER -> Color.rgb(33, 150, 243) // Royal Blue
    }
    val markerColor = if (isSelected) Color.WHITE else typeColor

val strokeW = 2.6f * density
    // Bigger bitmap than the visible pin: osmdroid hit-tests the icon bounds, so the
    // transparent margin above/around the teardrop makes the marker much easier to tap.
    val totalW = (40f * density).toInt().coerceAtLeast(1)
    val totalH = (36f * density).toInt().coerceAtLeast(1)
    val chevW = 16f * density
    val chevH = 18f * density
    val cx = totalW / 2f
    val bottom = totalH - 2f * density
    val r = chevW / 2f
    val top = bottom - chevH
    val bulbMidY = top + r

    val bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Teardrop pin: rounded bulb on top tapering to a point at the bottom-centre tip,
    // which is anchored on the shelter spot.
    val chevron = Path().apply {
        moveTo(cx, bottom)
        quadTo(cx + r, bulbMidY + r * 0.6f, cx + r, bulbMidY)
        quadTo(cx + r, top, cx, top)
        quadTo(cx - r, top, cx - r, bulbMidY)
        quadTo(cx - r, bulbMidY + r * 0.6f, cx, bottom)
    }
    canvas.drawPath(chevron, Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = markerColor
    })

    shelterBitmapCache[key] = bmp
    return bmp
}

/** Framing box for a notification reveal: focus near the top, threat near the bottom, with a
 *  clamped span so a huge gap (or a zero gap) still yields a valid, zoomable box. The threat is
 *  always pinned to the bottom fraction regardless of which is further north, so a northern
 *  threat (e.g. Kyiv with the focus on Odesa) never lands underneath the top popup card. When the
 *  popup card (top) or zones sheet (bottom) has been measured, the fractions shrink to fit the
 *  actual visible band so the threat never hides under an overlay. */
private fun buildRevealBoundingBox(
    threat: LatLng,
    focus: LatLng?,
    mapHeightPx: Int,
    topCoverPx: Int,
    bottomCoverPx: Int
): BoundingBox {
    if (focus == null) {
        val span = 0.5
        return BoundingBox(
            threat.lat + span, threat.lon + span,
            threat.lat - span, threat.lon - span
        )
    }
    val ft: Float
    val fb: Float
    if (mapHeightPx > 0 && (topCoverPx > 0 || bottomCoverPx > 0)) {
        val topFrac = (topCoverPx.toFloat() / mapHeightPx).coerceIn(0f, 0.45f)
        val bottomFrac = (bottomCoverPx.toFloat() / mapHeightPx).coerceIn(0f, 0.45f)
        ft = (topFrac + 0.05f).coerceAtMost(0.45f)
        fb = (1f - bottomFrac - 0.05f).coerceAtLeast(0.55f)
    } else {
        ft = 0.28f  // focus vertical fraction from the top
        fb = 0.72f  // threat vertical fraction from the top
    }
    val g = fb - ft
    val gapLat = Math.abs(focus.lat - threat.lat)
    val spanLat = Math.max(gapLat / g, REVEAL_MIN_SPAN_LAT).coerceAtMost(40.0)
    val north = Math.max(focus.lat + ft * spanLat, threat.lat + fb * spanLat)
    val south = Math.min(focus.lat - (1 - ft) * spanLat, threat.lat - (1 - fb) * spanLat)
    val lonMid = (focus.lon + threat.lon) / 2
    val gapLon = Math.abs(focus.lon - threat.lon)
    val spanLon = Math.max(gapLon / g, REVEAL_MIN_SPAN_LON).coerceAtMost(80.0)
    return BoundingBox(
        north.coerceAtMost(85.0), lonMid + spanLon / 2,
        south.coerceAtLeast(-85.0), lonMid - spanLon / 2
    )
}

/**
 * True when nothing covers the map (no paused modal, map on screen, no shelter overlay) and the
 * app is at least visible — i.e. the map owns the user's attention and death flourishes may play.
 */
internal fun mapIsUserFocus(
    paused: Boolean,
    mapVisible: Boolean,
    sheltersUp: Boolean,
    lifecycleState: Lifecycle.State
): Boolean = !paused && mapVisible && !sheltersUp && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

@Composable
@OptIn(ExperimentalCoroutinesApi::class)
fun NeptunMapView(
    uiState: UiState,
    selectedThreatId: StateFlow<String?>,
    lang: AppLanguage,
    iconSet: ThreatIconSet = ThreatIconSet.PHOTO,
    onScaleChange: (Double) -> Unit,
    onThreatTapped: (NormalizedThreat) -> Unit,
    onMapTapped: () -> Unit,
    fitUkraineTick: Int = 0,
    zoomZone: ThreatZone? = null,
    zoomTick: Int = 0,
    fitZonesTick: Int = 0,
    zonesSheetOpen: Boolean = false,
    popupCoverPx: Int = 0,
    zonesSheetCoverPx: Int = 0,
    revealRequest: RevealRequest? = null,
    paused: Boolean = false,
    mapVisible: Boolean = true,
    shelterZoomTick: Int = 0,
    shelterSelectTick: Int = 0,
    onNeutralize: (String) -> Unit = {},
    showNearbyShelters: Boolean = false,
    shelterIndex: ShelterIndex? = null,
    selectedShelter: NearestShelter? = null,
    onShelterTapped: (NearestShelter) -> Unit = {},
    onExitShelterMode: () -> Unit = {},
    onDeathActiveChange: (Boolean) -> Unit = {},
    onReplayProgressChange: (ReplayProgress?) -> Unit = {},
    onCountdownChange: (Int?) -> Unit = {},
    onAutoStrikeActiveChange: (Boolean) -> Unit = {},
    onStrikeTypeChange: (ThreatType?) -> Unit = {},
    onPendingStrikeCountChange: (Int) -> Unit = {},
    onCancelRequestTick: Int = 0,
    onFlourishEjected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val engine = remember { ThreatEngine(NEPTUN_TYPES) }
    val context = LocalContext.current
    val strings = Strings.get(lang)

    // Only rebuild overlays when the threat data actually changes. Pan/zoom and
    // unrelated recompositions (language, popup selection) must not clear + redraw
    // the map, which is what made the banner above it flicker.
    // Hoisted into remember so we only rebuild the key when its data dependencies change.
    // Note: staleness is handled in-place by the marker loop (alpha), so we don't need
    // currentTimeMillis in the key — that would defeat memoization.
    val overlayKey = remember(
        uiState.activeZone,
        iconSet,
        uiState.activeZoneParams.slowRedKm,
        uiState.activeZoneParams.slowYellowKm,
        uiState.followMe,
        uiState.pinnedCity?.nameUa,
        uiState.focusLocation,
        uiState.focusOblastAlertActive,
        uiState.showMediumCities,
        uiState.showSmallCities,
        uiState.showLargeCities,
        uiState.fillAlertRegions,
        uiState.showBorders,
        uiState.showRegionBorders,
        uiState.alertOblastTokens,
        uiState.alertRaionKeys,
        uiState.alertYellowOblastTokens,
        uiState.alertYellowRaionKeys,
        showNearbyShelters,
        selectedShelter?.shelter?.id,
        uiState.redCities,
        uiState.mapThreats,
        lang
    ) {
        buildString {
            append(lang).append('A').append(uiState.activeZone)
            append('I').append(iconSet)
            append('R').append(uiState.activeZoneParams.slowRedKm).append('Y').append(uiState.activeZoneParams.slowYellowKm)
            append('F').append(uiState.followMe).append('P').append(uiState.pinnedCity?.nameUa)
            append('G').append(uiState.focusLocation?.lat).append(',').append(uiState.focusLocation?.lon)
            append('O').append(uiState.focusOblastAlertActive)
            append('M').append(uiState.showMediumCities)
            append('N').append(uiState.showSmallCities)
            append('L').append(uiState.showLargeCities)
            append('K').append(uiState.fillAlertRegions)
            append('B').append(uiState.showBorders)
            append('R').append(uiState.showRegionBorders)
            for (stem in uiState.alertOblastTokens) append('W').append(stem).append(';')
            for ((stem, raion) in uiState.alertRaionKeys) append('J').append(stem).append('=').append(raion).append(';')
            for (stem in uiState.alertYellowOblastTokens) append('w').append(stem).append(';')
            for ((stem, raion) in uiState.alertYellowRaionKeys) append('j').append(stem).append('=').append(raion).append(';')
            append('S').append(showNearbyShelters)
            if (showNearbyShelters) {
                append('L').append(selectedShelter?.shelter?.id)
            }
            for (city in uiState.redCities) append('C').append(city).append(';')
            for (t in uiState.mapThreats) appendThreatKey(t) // staleness handled in marker loop
        }
    }
    val lastOverlayKey = remember { mutableStateOf<String?>(null) }
    // Per-id dedup of server-resolution strikes: a source re-sends a resolved/remove frame within
    // ~60s; without this the map re-strikes threats the user already saw (a "random" replay a
    // minute later). Pruned to the same RESOLVED_REPLAY_GRACE_MS window the source uses.
    val struckRemovalAt = remember { HashMap<String, Long>() }
    val lastFitUkraineTick = remember { mutableStateOf(fitUkraineTick) }
    val lastFollow = remember { mutableStateOf<LatLng?>(null) }
    val lastZoomTick = remember { mutableStateOf(-1) }
    val lastShelterSelectTick = remember { mutableStateOf(-1) }
    val lastFitZonesTick = remember { mutableStateOf(-1) }
    val lastFittedYellowKm = remember { mutableStateOf<Int?>(null) }
    val lastRevealTick = remember { mutableStateOf(-1) }
    val lastRevealPos = remember { mutableStateOf<LatLng?>(null) }
    val lastCenterTick = remember { mutableStateOf(-1) }
    val lastPopupCoverPx = remember { mutableStateOf(0) }
    val lastZonesCoverPx = remember { mutableStateOf(0) }
    val lastFlourishTick = remember { mutableStateOf(-1) }
    // Bumped on every lifecycle RESUME so the pending tally-tap replay is retried actively.
    val flourishRetryTick = remember { mutableStateOf(0) }
    val newRingState = remember { mutableStateOf<NewRingState?>(null) }
    val didDefaultFit = remember { mutableStateOf(false) }
    val lastPinnedCity = remember { mutableStateOf<String?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val markerRefs = remember { mutableStateOf<MutableMap<String, Marker>>(mutableMapOf()) }
    val markerIconDp = remember { mutableStateOf<MutableMap<String, Int>>(mutableMapOf()) }
    val hiddenByDeath = remember { mutableStateOf<MutableSet<String>>(mutableSetOf()) }
    // True while a finger is on the map: the animation loop freezes marker writes so icons stay
    // ground-fixed while panning instead of sliding along with the gesture.
    val mapTouching = remember { mutableStateOf(false) }
    val pausedState by rememberUpdatedState(paused)
    val mapVisibleState by rememberUpdatedState(mapVisible)
    val alertActiveState by rememberUpdatedState(uiState.alertActive)
    val showNearbySheltersState by rememberUpdatedState(showNearbyShelters)
    // While shelter mode is entered, the camera animates from its current zoom up to the
    // fitted range; intermediate frames dip below SHELTER_AUTO_EXIT_ZOOM and would trigger
    // the auto-exit listener mid-animation. Suppress that exit for a short window after entry.
        val shelterEntryGuardUntil = remember { mutableStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hiddenTypesState by rememberUpdatedState(uiState.hiddenTypes)
    val iconSetState by rememberUpdatedState(uiState.iconSet)
    val mapThreatsState by rememberUpdatedState(uiState.mapThreats)
    val slowRedKmState by rememberUpdatedState(uiState.activeZoneParams.slowRedKm)
    val slowYellowKmState by rememberUpdatedState(uiState.activeZoneParams.slowYellowKm)
    val threatIconZoomState by rememberUpdatedState(uiState.threatIconZoom)
    val selectedId by selectedThreatId.collectAsState()
    val selectedThreatIdState by rememberUpdatedState(selectedId)
    val focusLocationState by rememberUpdatedState(uiState.focusLocation)
    val deathAnimationEnabledState by rememberUpdatedState(uiState.deathAnimationEnabled)
        val followBulletState by rememberUpdatedState(uiState.followBullet)
    val hapticsOnState by rememberUpdatedState(LocalHapticsEnabled.current)
    // Re-size every threat marker to the size bucket matching the current zoom, keeping the
    // reveal/areaOnly dot. Called from the map's zoom listener so size tracks the gesture
    // immediately (no 3s poll lag); when the Just Fun toggle is off, size is pinned to 32dp.
    val resizeThreatIcons: () -> Unit = resize@{
        val mv = mapViewRef.value ?: return@resize
        val target = if (threatIconZoomState) threatIconSizeDp(mv.zoomLevelDouble) else 32
        val ring = newRingState.value
        val nowMs = System.currentTimeMillis()
        var dirty = false
        for (t in mapThreatsState) {
            val m = markerRefs.value[t.id] ?: continue
            if (markerIconDp.value[t.id] != target) {
                val revealed = ring != null && t.id == ring.id && nowMs < ring.activeUntilMs
                m.icon = threatIconFor(context, t.type.toThreatType(), iconSetState, revealed = revealed, areaOnly = t.areaOnly, sizeDp = target)
                markerIconDp.value[t.id] = target
                dirty = true
            }
        }
        if (dirty) mv.invalidate()
    }
    // Applying the Just Fun toggle right away: resize to the fixed 32dp (off) or the current
    // zoom bucket (on) without waiting for the next zoom gesture.
    LaunchedEffect(uiState.threatIconZoom) {
        resizeThreatIcons()
    }
    val mapScope = rememberCoroutineScope()
    val deathFx = remember {
        DeathFxController(
            context = context,
            mapView = { mapViewRef.value },
            iconFor = { type -> threatIconFor(context, type, iconSetState) },
            showDetail = { rec, grp -> String.format(strings.flourishLogDetailFormat, rec, grp) },
            scope = mapScope
        )
    }
        val zoneRefitJob = remember { mutableStateOf<Job?>(null) }

    // osmdroid owns a tile-fetch thread pool that must be paused/resumed with the host
    // lifecycle (and detached on release) — without this it keeps spinning in background.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapViewRef.value?.onResume()
                    // A tally-tap flourish may have arrived while the app was backgrounded —
                    // bump the retry tick so the pending replay is consumed promptly (Compose
                    // doesn't observe lifecycle, so nothing else would re-run the tick check).
                    flourishRetryTick.value++
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapViewRef.value?.onPause()
                    // Backgrounding ejects the flourish too — come back to a clean map.
                    deathFx.clear()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Centre + zoom so the whole yellow zone sits in the visible area ABOVE the zones sheet.
    // The bbox is extended downward so the zone occupies the top part of the viewport that is
    // actually visible above the sheet — measured from the sheet's real height when known,
    // falling back to a 60% assumption before the sheet has laid out.
    val fitZoneToPanel: (MapView, IGeoPoint) -> Unit = { mv, center ->
        val zone = zoneBoundingBox(center, uiState.activeZoneParams.slowYellowKm.toDouble())
        val visibleFrac = if (mv.height > 0 && zonesSheetCoverPx > 0) {
            (1f - zonesSheetCoverPx / mv.height.toFloat()).coerceIn(0.3f, 1f)
        } else 0.6f
        val dLat = zone.latNorth - center.latitude
        val southPad = dLat * 2 * ((1f / visibleFrac) - 1f)
        mv.zoomToBoundingBox(
            BoundingBox(zone.latNorth, zone.lonEast, zone.latSouth - southPad, zone.lonWest),
            true
        )
    }

    val deathFrame = remember { mutableIntStateOf(0) }
    LaunchedEffect(deathFx) {
        while (true) {
            withFrameNanos {}
            if (deathFx.isActive) deathFrame.intValue++
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            // Keep the tile cache in the OS cache dir so Android treats it as "Cache"
            // (evictable, not counted as user data) and cap its size.
            Configuration.getInstance().osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            Configuration.getInstance().osmdroidTileCache = File(ctx.cacheDir, "osmdroid")
            Configuration.getInstance().tileFileSystemCacheMaxBytes = 64L * 1024 * 1024
            Configuration.getInstance().tileFileSystemCacheTrimBytes = 48L * 1024 * 1024
            MapView(ctx).apply {
                setTileProvider(UkraineTileProvider(ctx))
                setBackgroundColor(Color.BLACK)
                overlayManager.tilesOverlay.setLoadingBackgroundColor(Color.BLACK)
                overlayManager.tilesOverlay.setLoadingLineColor(Color.BLACK)
                setMultiTouchControls(true)
                // Observe-only: never consumes (returns false), so osmdroid's own gesture
                // pipeline (pan/zoom/double-tap) is untouched. While a finger is down, the
                // animation loop freezes marker writes so icons stay ground-fixed during a pan;
                // it resumes from the live clock the frame after release.
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> mapTouching.value = true
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mapTouching.value = false
                    }
                    false
                }
                // No +/– buttons — everyone uses pinch. Contours stay clean on the map.
                setBuiltInZoomControls(false)
                // Cap normal zoom at the ~5 km viewport level; deep zoom (street-level shelter
                // detail) is unlocked only while the shelter overlay is up (see the shelter
                // LaunchedEffect). This keeps the tile cache to the viewport the threat map
                // actually needs.
                maxZoomLevel = NORMAL_MAX_ZOOM
                // Clamp panning to the extended Ukraine box (tight bounds + ~1.5°). The extra
                // strip is what lets a zoomed viewport shift content out from under the top
                // threat card; the min-zoom floor still uses UA_VIEW_LIMITS so the far-out
                // view never shows neighbouring territory.
                setScrollableAreaLimitDouble(UA_PAN_LIMITS)
                controller.setCenter(DEFAULT_CENTER)
                // Start at a country-level zoom instead of a close city view: the first GPS fix
                // then zooms IN to the user's yellow zone (didDefaultFit), avoiding the jarring
                // "fully in, then fully out" dance on every startup.
                controller.setZoom(6.0)

                // Feed ground meters-per-pixel to the Compose scale bar while panning/zooming.
                    addMapListener(object : MapListener {
                        private fun mpp(): Double =
                            TileSystem.GroundResolution(this@apply.mapCenter.latitude, this@apply.zoomLevelDouble)
                        override fun onScroll(event: ScrollEvent?): Boolean { onScaleChange(mpp()); return false }
                        override fun onZoom(event: ZoomEvent?): Boolean {
                            onScaleChange(mpp())
                            // Zooming far out makes the shelter pins clutter — auto-exit shelter mode.
                            // Skip the check right after entering shelter mode: the zoom-to-fit
                            // animation passes through sub-threshold zoom levels and must not self-cancel.
                            if (showNearbySheltersState &&
                                System.currentTimeMillis() >= shelterEntryGuardUntil.value &&
                                this@apply.zoomLevelDouble < SHELTER_AUTO_EXIT_ZOOM
                            ) {
                                onExitShelterMode()
                            }
                            resizeThreatIcons()
                            return false
                        }
                    })
            }
        },
        update = { mapView ->
            mapViewRef.value = mapView

            // Floor the zoom-out so you can't zoom past "Ukraine fills the screen".
            if (mapView.width > 0 && mapView.height > 0) {
                val floorZoom =
                    tileSystem.getBoundingBoxZoom(UA_VIEW_LIMITS, mapView.width, mapView.height)
                if (mapView.minZoomLevel != floorZoom) mapView.setMinZoomLevel(floorZoom)
            }
            onScaleChange(
                TileSystem.GroundResolution(mapView.mapCenter.latitude, mapView.zoomLevelDouble)
            )

            // Camera follows the focus point (GPS while following, pinned city otherwise).
            // A tally-tap replay owns the camera while queued or running: hold still (and
            // leave the default fit pending) so the show jumps straight onto its targets
            // instead of panning home first.
            val replayOwnsCamera = deathFx.isReplayActive ||
                (uiState.flourish != null && uiState.flourish.tick != lastFlourishTick.value)
            val focus = uiState.focusLocation
            if (focus != null && lastFollow.value != focus) {
                lastFollow.value = focus
                if (!replayOwnsCamera) mapView.controller.animateTo(GeoPoint(focus.lat, focus.lon))
            } else if (focus == null && lastFollow.value != null) {
                lastFollow.value = null
            }

            // Default view: once we have a focus point, open zoomed to fit the whole
            // yellow zone (camera then just follows it without re-zooming).
            if (!didDefaultFit.value && focus != null && !replayOwnsCamera) {
                didDefaultFit.value = true
                mapView.zoomToBoundingBox(
                    zoneBoundingBox(GeoPoint(focus.lat, focus.lon), uiState.activeZoneParams.slowYellowKm.toDouble()),
                    true
                )
            }

            // Pin change: jump to the city and refit to its yellow zone.
            val pinned = uiState.pinnedCity
            if (!uiState.followMe && pinned != null && lastPinnedCity.value != pinned.nameUa) {
                lastPinnedCity.value = pinned.nameUa
                mapView.zoomToBoundingBox(
                    zoneBoundingBox(GeoPoint(pinned.lat, pinned.lon), uiState.activeZoneParams.slowYellowKm.toDouble()),
                    true
                )
            } else if (uiState.followMe) {
                lastPinnedCity.value = null
            }

            // Header tap: zoom out so the whole of Ukraine fills the screen.
            if (fitUkraineTick != lastFitUkraineTick.value) {
                lastFitUkraineTick.value = fitUkraineTick
                mapView.zoomToBoundingBox(UA_VIEW_LIMITS, true)
            }

            // Zone-button tap: zoom the camera to fit that zone circle with a 5% margin.
            if (zoomZone != null && zoomTick != lastZoomTick.value) {
                lastZoomTick.value = zoomTick
                val center = focus?.let { GeoPoint(it.lat, it.lon) } ?: mapView.mapCenter
                val radiusKm = when (zoomZone) {
                    ThreatZone.INNER -> uiState.activeZoneParams.slowRedKm.toDouble()
                    else -> uiState.activeZoneParams.slowYellowKm.toDouble()
                }
                mapView.zoomToBoundingBox(zoneBoundingBox(center, radiusKm), false)
            }

            // Shelter marker tapped: highlight + open its card, but keep the camera where it
            // is — panning onto every tapped shelter makes the map jump around.
            if (shelterSelectTick != lastShelterSelectTick.value) {
                lastShelterSelectTick.value = shelterSelectTick
            }

            // Alert-zones panel opened: centre + zoom so the whole yellow zone sits in
            // the visible area ABOVE the panel. The bbox is extended downward so the
            // zone occupies the top 60% of the viewport (the sheet covers ~40% below).
            if (fitZonesTick != lastFitZonesTick.value) {
                lastFitZonesTick.value = fitZonesTick
                val center = focus?.let { GeoPoint(it.lat, it.lon) } ?: mapView.mapCenter
                lastFittedYellowKm.value = uiState.activeZoneParams.slowYellowKm
                fitZoneToPanel(mapView, center)
            }

            // Zone-slider change while the sheet is open: the yellow circle grew (or shrank)
            // on the map, so refit it into the visible area above the panel again. Only
            // refits once the sheet is open and after the initial default fit. Debounced so a
            // quick up-and-down drag doesn't make the camera jitter with every slider tick.
            val fitted = lastFittedYellowKm.value
            if (zonesSheetOpen && didDefaultFit.value && focus != null && fitted != null &&
                fitted != uiState.activeZoneParams.slowYellowKm
            ) {
                lastFittedYellowKm.value = uiState.activeZoneParams.slowYellowKm
                zoneRefitJob.value?.cancel()
                zoneRefitJob.value = mapScope.launch {
                    delay(ZONE_REFIT_DEBOUNCE_MS)
                    mapViewRef.value?.let { mv ->
                        focusLocationState?.let { fitZoneToPanel(mv, GeoPoint(it.lat, it.lon)) }
                    }
                }
            }

            // Notification tap: pan + zoom so the focus point (GPS/city) sits near the top
            // and the revealed threat near the bottom, with space between. The span scales
            // with the gap, so the zoom reflects how far the threat is. Also mark it with
            // the green dot.
            val reveal = revealRequest
            if (reveal != null && reveal.tick != lastRevealTick.value) {
                // Clear the previous reveal dot by refreshing the old marker icon
                val prevId = newRingState.value?.id
                if (prevId != null && prevId != reveal.id) {
                    markerRefs.value[prevId]?.let { m ->
                        val prevThreat = uiState.mapThreats.firstOrNull { it.id == prevId }
                        if (prevThreat != null) {
                            m.icon = threatIconFor(context, prevThreat.type.toThreatType(), iconSetState, revealed = false, sizeDp = markerIconDp.value[prevId] ?: 32)
                        }
                    }
                }
                lastRevealTick.value = reveal.tick
                val threat = LatLng(reveal.lat, reveal.lon)
                lastRevealPos.value = threat
                newRingState.value = NewRingState(
                    reveal.id, System.currentTimeMillis() + NEW_RING_MS
                )
                if (mapView.width > 0 && mapView.height > 0) {
                    // Harden: a bad framing box (or a not-yet-laid-out map) must never crash
                    // the composition thread — fall back to a plain centre-on-threat pan.
                    try {
                        mapView.zoomToBoundingBox(
                            buildRevealBoundingBox(
                                threat,
                                uiState.focusLocation,
                                mapView.height,
                                popupCoverPx,
                                zonesSheetCoverPx
                            ), true
                        )
                    } catch (_: Exception) {
                        mapView.controller.animateTo(GeoPoint(threat.lat, threat.lon))
                    }
                }
                // The reveal dot is baked into the threat's own icon (top-right corner), so a
                // marker that already exists gets its badge now; the rebuild path applies it at
                // build time too. If the threat isn't mapped yet (cold start), the marker appears
                // badged once the stream delivers it.
                reveal.id?.let { id ->
                    markerRefs.value[id]?.let { m ->
                        val t = uiState.mapThreats.firstOrNull { it.id == id }
                        if (t != null) {
                            m.icon = threatIconFor(context, t.type.toThreatType(), iconSetState, revealed = true, sizeDp = markerIconDp.value[id] ?: 32)
                            mapView.invalidate()
                        }
                    }
                }
            }

            // Locate button: centre the map on the threat with a tight threat-only framing
            // (no reveal dot, no focus-point inclusion).
            val center = uiState.centerRequest
            if (center != null && center.tick != lastCenterTick.value) {
                lastCenterTick.value = center.tick
                if (mapView.width > 0 && mapView.height > 0) {
                    val centerPoint = GeoPoint(center.lat, center.lon)
                    // ~20 km radius tight box around the threat, leaving room for the popup
                    // card above and zones sheet below.
                    val radiusM = 12_000.0
                    val bbox = zoneBoundingBox(centerPoint, radiusM / 1000.0)
                    mapView.zoomToBoundingBox(bbox, true)
                }
            }

            if (overlayKey == lastOverlayKey.value) {
                // No change — skip clearing + redrawing the map (avoids banner flicker).
            } else if (deathFx.isActive) {
                // A death animation is mid-flight: defer the clear+rebuild until it finishes.
                // clearing mapView.overlays (of which deathFx is a member) while the 16ms
                // invalidate loop is drawing can race the overlay list. The death-active flow
                // flips false when the show ends, which recomposes this update block and fires
                // the deferred rebuild.
            } else {
                lastOverlayKey.value = overlayKey

                mapView.overlays.clear()
                markerRefs.value.clear()
                markerIconDp.value.clear()

                // Bottom-most overlay: single-tap on empty map closes the popup, while
                // markers added after it keep tap priority. Long-press is handled by the
                // top-most overlay (markers swallow their own long-presses).
                mapView.overlays.add(
                    0,
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            onMapTapped()
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean = true
                    })
                )

                val outerHullSet = HashSet<Long>(UKRAINE_BORDER.size * 2).apply {
                    for (pt in UKRAINE_BORDER) {
                        val latI = kotlin.math.round(pt.latitude * 1000.0).toInt()
                        val lonI = kotlin.math.round(pt.longitude * 1000.0).toInt()
                        add((latI.toLong() shl 32) or (lonI.toLong() and 0xffffffffL))
                    }
                }
                // Yellow (tactical) fills — illustrative only, drawn under red.
                if (uiState.fillAlertRegions && uiState.alertYellowOblastTokens.isNotEmpty()) {
                    for (stem in uiState.alertYellowOblastTokens) {
                        val polygon = CompactOblastBoundaries.get(stem) ?: continue
                        for (ring in polygon.rings) {
                            if (ring.pointCount < 3) continue
                            val points = ring.toPoints().map { pt -> GeoPoint(pt.lat, pt.lon) }
                            mapView.overlays.add(Polygon(mapView).apply {
                                this.points = points
                                fillColor = Color.argb(40, 255, 213, 0)
                                strokeColor = Color.TRANSPARENT
                                strokeWidth = 0f
                                title = ""
                                setInfoWindow(null)
                            })
                        }
                    }
                }
                if (uiState.fillAlertRegions && uiState.alertYellowRaionKeys.isNotEmpty()) {
                    for ((stem, raion) in uiState.alertYellowRaionKeys) {
                        val polygon = CompactRaionBoundaries.forKey(stem, raion) ?: continue
                        for (ring in polygon.rings) {
                            if (ring.pointCount < 3) continue
                            val points = ring.toPoints().map { pt -> GeoPoint(pt.lat, pt.lon) }
                            mapView.overlays.add(Polygon(mapView).apply {
                                this.points = points
                                fillColor = Color.argb(40, 255, 213, 0)
                                strokeColor = Color.TRANSPARENT
                                strokeWidth = 0f
                                title = ""
                                setInfoWindow(null)
                            })
                        }
                    }
                }
                // Oblast region fill: when fillAlertRegions is on, shade alerting oblasts
                // with a subtle red fill instead of coloring city labels red.
                // Stroke is transparent on the outer hull to avoid red bleeding outside Ukraine;
                // inner edges get a hairline to seal inter-oblast simplification gaps.
if (uiState.fillAlertRegions && uiState.alertOblastTokens.isNotEmpty()) {
                    for (stem in uiState.alertOblastTokens) {
                        val polygon = CompactOblastBoundaries.get(stem) ?: continue
                        for (ring in polygon.rings) {
                            if (ring.pointCount < 3) continue
                            val pts = ring.toPoints()
                            val geo = pts.map { pt -> GeoPoint(pt.lat, pt.lon) }
                            mapView.overlays.add(Polygon(mapView).apply {
                                this.points = geo
                                fillColor = Color.argb(55, 255, 60, 60)
                                strokeColor = Color.TRANSPARENT
                                strokeWidth = 0f
                                title = ""
                                setInfoWindow(null)
                            })
                            var cur = mutableListOf<GeoPoint>()
                            fun flush() {
                                if (cur.size >= 2) {
                                    mapView.overlays.add(Polyline(mapView).apply {
                                        setPoints(ArrayList(cur))
                                        color = Color.argb(70, 255, 60, 60)
                                        width = 1f
                                    })
                                }
                                cur = mutableListOf()
                            }
                            for (i in pts.indices) {
                                val a = pts[i]
                                val b = pts[(i + 1) % pts.size]
                                val aKey = (kotlin.math.round(a.lat * 1000.0).toInt().toLong() shl 32) or (kotlin.math.round(a.lon * 1000.0).toInt().toLong() and 0xffffffffL)
                                val bKey = (kotlin.math.round(b.lat * 1000.0).toInt().toLong() shl 32) or (kotlin.math.round(b.lon * 1000.0).toInt().toLong() and 0xffffffffL)
                                val isOuter = aKey in outerHullSet || bKey in outerHullSet
                                if (isOuter) {
                                    flush()
                                } else {
                                    if (cur.isEmpty()) cur.add(GeoPoint(a.lat, a.lon))
                                    cur.add(GeoPoint(b.lat, b.lon))
                                }
                            }
                            flush()
                        }
                    }
                }

                // Raion region fill: the engine derives (stem, raion) keys from the same alert→city
                // coverage as the red cities, so every filled raion backs red city labels.
                if (uiState.fillAlertRegions && uiState.alertRaionKeys.isNotEmpty()) {
                    for ((stem, raion) in uiState.alertRaionKeys) {
                        val polygon = CompactRaionBoundaries.forKey(stem, raion) ?: continue
                        for (ring in polygon.rings) {
                            if (ring.pointCount < 3) continue
                            val points = ring.toPoints().map { pt -> GeoPoint(pt.lat, pt.lon) }
                            mapView.overlays.add(Polygon(mapView).apply {
                                this.points = points
                                fillColor = Color.argb(55, 255, 60, 60)
                                strokeColor = Color.TRANSPARENT
                                strokeWidth = 0f
                                title = ""
                                setInfoWindow(null)
                            })
                        }
                    }
                }

                // Oblast boundary outlines — controlled by the "Show borders" toggle.
                if (uiState.showBorders) {
                    val oblastStroke = Color.argb(120, 180, 180, 200)
                    for (stem in CompactOblastBoundaries.allStems) {
                        val polygon = CompactOblastBoundaries.get(stem) ?: continue
                        for (ring in polygon.rings) {
                            if (ring.pointCount < 3) continue
                            mapView.overlays.add(Polyline(mapView).apply {
                                setPoints(ring.toPoints().map { pt -> GeoPoint(pt.lat, pt.lon) })
                                color = oblastStroke
                                width = 2f
                            })
                        }
                    }
                }

                // Raion (district) boundary outlines — sub-setting under "Show borders", drawn
                // thinner and lighter than the oblast borders.
                if (uiState.showBorders && uiState.showRegionBorders) {
                    val raionStroke = Color.argb(70, 180, 180, 200)
                    for ((_, ring) in CompactRaionBoundaries.all) {
                        for (r in ring.rings) {
                            if (r.pointCount < 3) continue
                            mapView.overlays.add(Polyline(mapView).apply {
                                setPoints(r.toPoints().map { pt -> GeoPoint(pt.lat, pt.lon) })
                                color = raionStroke
                                width = 1f
                            })
                        }
                    }
                }

                // City labels (English names on top of label-free tiles). Region-precise red:
                // in fill mode the wide-oblast and raion fills already cover the region, so skip
                // red labels for cities inside a filled oblast or a filled raion; cities only
                // covered by a city-level alert (no polygon) still read red via their labels.
                val redLabels = if (uiState.fillAlertRegions) {
                    uiState.redCities.filter { c ->
                        val stem = Cities.cityOblast[c] ?: return@filter true
                        if (stem in uiState.alertOblastTokens) return@filter false
                        val raion = CityRaions.cityRaion[c] ?: return@filter true
                        (stem to raion.lowercase()) !in uiState.alertRaionKeys
                    }.toSet()
                } else uiState.redCities
                mapView.overlays.add(
                    CityLabelOverlay(
                        context, lang,
                        redCityNames = redLabels,
                        uiState.showLargeCities, uiState.showMediumCities, uiState.showSmallCities,
                        forceShowAllProvider = { deathFx.forceShowAllCities.value }
                    )
                )

                // Subtle outline of Ukraine's land border — an open polyline, so it hugs the
                // land borders tightly (river borders included) and never crosses the sea.
                // Skipped when oblast borders are on: they already trace the same outer ring
                // (same source, now same epsilon), otherwise two parallel white lines appear.
                if (!uiState.showBorders) {
                    mapView.overlays.add(Polyline(mapView).apply {
                        setPoints(ArrayList(UKRAINE_LAND_BORDER))
                        color = Color.argb(70, 255, 255, 255)
                        width = 2f
                    })
                }

                // Focus-centered alert zones: yellow ring (outer) and red circle (inner) for
                // the SLOW distance thresholds — outlines only, no fill so the map stays clean.
                if (focus != null) {
                    val zoneCenter = GeoPoint(focus.lat, focus.lon)
                    val yellowAlert = uiState.activeZone == ThreatZone.OUTER
                    val redAlert = uiState.activeZone == ThreatZone.INNER
                    mapView.overlays.add(Polygon(mapView).apply {
                        points = circlePoints(zoneCenter, uiState.activeZoneParams.slowYellowKm * 1000.0)
                        fillColor = Color.TRANSPARENT
                        strokeColor = if (yellowAlert) Color.argb(235, 255, 213, 0)
                        else Color.argb(150, 255, 213, 0)
                        strokeWidth = if (yellowAlert) 4f else 2.5f
                        title = strings.yellowZoneLabel
                        setInfoWindow(null)
                    })
                    mapView.overlays.add(Polygon(mapView).apply {
                        points = circlePoints(zoneCenter, uiState.activeZoneParams.slowRedKm * 1000.0)
                        fillColor = Color.TRANSPARENT
                        strokeColor = if (redAlert) Color.argb(235, 255, 60, 60)
                        else Color.argb(160, 255, 82, 82)
                        strokeWidth = if (redAlert) 4f else 3f
                        title = strings.redZoneLabel
                        setInfoWindow(null)
                    })
                }

                // Threats anywhere in the country — tappable, type icon; stale/expired ones
                // render dimmed (still tappable) until they pass the hard ghost cap.
                // De-overlap first: same-coordinate threats spread/grid/count per the setting.
                val overlapDensity = context.resources.displayMetrics.density
                val stepPx = ((if (uiState.threatIconZoom) threatIconSizeDp(mapView.zoomLevelDouble) else 32) * overlapDensity).toInt().coerceAtLeast(24)
                val placements = deOverlapThreats(
                    uiState.mapThreats.map { t ->
                        val p = engine.propsFor(t.type)
                        val pose = resolveThreatPose(
                            engine, t, p,
                            uiState.activeZoneParams.slowRedKm, uiState.activeZoneParams.slowYellowKm,
                            System.currentTimeMillis()
                        )
                        Triple(t.id, LatLng(pose.lat, pose.lon), t.type)
                    },
                    mapView, uiState.overlapMode, stepPx
                )
                for (t in uiState.mapThreats) {
                    // A user-shot drone stays hidden while its death animation plays; the
                    // next redraw after the animation brings it back in place.
                    if (deathFx.isActiveFor(t.id)) continue
                    val placement = placements[t.id] ?: continue
                    val pos = placement.pos ?: continue // collapsed into a count representative
                    val nt = t
                    val props = engine.propsFor(nt.type)
                    engine.speedCache.record(nt.id, nt.updatedAtMillis ?: System.currentTimeMillis(), nt.lat, nt.lon)
                    val typeInfo = threatTypeInfoByString(nt.type)!!
                    val typeLabel = if (lang == AppLanguage.UA) typeInfo.labelUa else typeInfo.labelEn
                    val rawRegion = t.region ?: t.district ?: t.locality ?: strings.noRegion
                    val regionLabel = if (lang == AppLanguage.EN) Cities.uaToEn[rawRegion] ?: rawRegion else rawRegion
                    // Place markers at their from-clock pose straight away (matching the animation loop) so a
                    // rebuild never snaps a moving marker back to its raw fix and returning to the
                    // app doesn't flash stale fixes before the loop corrects.
                    val pose = resolveThreatPose(
                        engine, nt, props,
                        uiState.activeZoneParams.slowRedKm, uiState.activeZoneParams.slowYellowKm,
                        System.currentTimeMillis()
                    )
                    val stale = engine.isStale(nt, props, System.currentTimeMillis())
                    val nowMs = System.currentTimeMillis()
                    val ring = newRingState.value
                    val revealed = ring != null && t.id == ring.id && nowMs < ring.activeUntilMs
                    val marker = Marker(mapView).apply {
                        position = pos
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        placement.chip?.let { setSubDescription(it) }
                        if (t.simulated) {
                            setSubDescription(chipLabel(t, placement.chip))
                        }
                        icon = threatIconFor(
                            context, t.type.toThreatType(), iconSet, revealed = revealed, areaOnly = t.areaOnly,
                            sizeDp = if (uiState.threatIconZoom) threatIconSizeDp(mapView.zoomLevelDouble) else 32
                        )
                        alpha = if (stale) 0.45f else 1.0f
                        title = typeLabel
                        snippet = regionLabel
                        // Rotate to show course, mirroring NEPTUN's predict().heading: the orbit tangent while
                        // patrolling a ring, else the reported course. The classic icons face up at
                        // 0°; the photo/army sets have a baked-in facing angle, so their rotation is
                        // the heading minus that base.
                        rotation = if (nt.areaOnly) 0f else {
                            val base = IconCatalog.baseDeg(t.type.toThreatType(), iconSet)
                            threatMarkerRotation(pose.headingDeg, base)
                        }
                        // Instant taps are handled by InstantThreatTapOverlay (onSingleTapUp,
                        // no double-tap wait). This listener only absorbs osmdroid's late
                        // onSingleTapConfirmed so it never falls through to the map-tap overlay.
                        setOnMarkerClickListener { _, _ -> true }
                        relatedObject = t
                    }
                    mapView.overlays.add(marker)
                    markerRefs.value[t.id] = marker
                    markerIconDp.value[t.id] = if (uiState.threatIconZoom) threatIconSizeDp(mapView.zoomLevelDouble) else 32
                }

                // Nearby shelters — rendered when toggled on, centered around the user/pinned focus.
                if (showNearbyShelters && focus != null && shelterIndex != null) {
                    val nearList = shelterIndex.nearest(focus.lat, focus.lon, limit = 25)
                    for (nearItem in nearList) {
                        val isSelected = selectedShelter?.shelter?.id == nearItem.shelter.id
                        mapView.overlays.add(Marker(mapView).apply {
                            position = GeoPoint(nearItem.shelter.lat, nearItem.shelter.lon)
                            setAnchor(Marker.ANCHOR_CENTER, 1.0f)
                            icon = BitmapDrawable(
                                context.resources,
                                shelterMarkerBitmap(context, nearItem.shelter.type, isSelected)
                            )
                            title = nearItem.shelter.name
                            setInfoWindow(null)
                            setOnMarkerClickListener { _, _ ->
                                onShelterTapped(nearItem)
                                true
                            }
                        })
                    }
                }

                // GPS dot — a plain marker driven by LocationTracker's coarse fix. No separate
                // location provider here (that was the battery-heavy blue accuracy circle).
                // Only shown while following; when pinned to a city your real position (possibly
                // far away) would just confuse the view. Before the first fix it sits on the
                // fallback focus (Odesa) in gray — the "locating you" state.
                if (uiState.followMe) {
                    val pos = uiState.userLocation?.let { GeoPoint(it.lat, it.lon) }
                        ?: focus?.let { GeoPoint(it.lat, it.lon) }
                    if (pos != null) {
                        mapView.overlays.add(Marker(mapView).apply {
                            position = pos
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = BitmapDrawable(
                                context.resources, gpsDotBitmap(context, uiState.gpsFixAvailable)
                            )
                            setInfoWindow(null)
                        })
                    }
                }

                // Pinned-city pin — tip of the marker sits above the city label text.
                if (!uiState.followMe) {
                    uiState.pinnedCity?.let { city ->
                        mapView.overlays.add(Marker(mapView).apply {
                            position = GeoPoint(city.lat, city.lon)
                            setAnchor(Marker.ANCHOR_CENTER, 1.5f)
                            icon = BitmapDrawable(context.resources, pinBitmap(context))
                            setInfoWindow(null)
                        })
                    }
                }

                // Top-most touch overlay: markers swallow their own long-presses, so a
                // separate overlay gets them first. Taps fall through to the map/markers.
                // Long-pressing a threat marker fires the death animation on demand — the
                // same flourish a real resolution plays. Empty-ground long-presses are ignored.
                mapView.overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                        override fun longPressHelper(p: GeoPoint): Boolean {
                            // Never let a playful kill during a red/official alert — the user
                            // could accidentally shoot down the very object they need to watch,
                            // and the 30s user-shot grace would keep its alerts quiet.
                            if (alertActiveState) return false
                            val pressPx = Point()
                            mapView.projection.toPixels(p, pressPx)
                            val density = mapView.context.resources.displayMetrics.density
                            val threshold = 48 * density
                            var nearest: Marker? = null
                            var nearestD = threshold
                            for (t in uiState.mapThreats) {
                                val m = markerRefs.value[t.id] ?: continue
                                val mp = m.position ?: continue
                                val tp = Point()
                                mapView.projection.toPixels(mp, tp)
                                val dx = tp.x - pressPx.x
                                val dy = tp.y - pressPx.y
                                val d = sqrt((dx * dx + dy * dy).toFloat())
                                if (d <= nearestD) {
                                    nearest = m
                                    nearestD = d
                                }
                            }
                            if (nearest != null) {
                                // If the pressed threat is the selected one, self-destruct
                                // its card too (reuses the real neutralized-card flow).
                                val pressedId = markerRefs.value.entries
                                    .firstOrNull { it.value === nearest }?.key
                                // The easter egg is gated on the death-animation toggle here; the
                                // engine independently gates the Just Fun master and reports
                                // whether a strike actually launched. Only when it did do we touch
                                // the marker or the alert grace — otherwise the long-press is a
                                // pure no-op (no hide, no vibration, no user-shot grace).
                                if (deathAnimationEnabledState) {
                                    val target = nearest.position ?: GeoPoint(p.latitude, p.longitude)
                                    val played = if (deathFx.isActiveFor(pressedId)) {
                                        // Already being struck — a follow-up projectile just
                                        // flies off-screen instead of exploding twice.
                                        deathFx.strikeDud(pressedId, target)
                                    } else {
                                        // Fresh icon copy so the death animation's per-frame
                                        // alpha mutations don't touch the marker's own drawable.
                                        val threatType = uiState.mapThreats
                                            .firstOrNull { it.id == pressedId }?.type
                                            ?.toThreatType() ?: return@longPressHelper true
                                        val icon = threatIconFor(
                                            context, threatType, iconSetState,
                                            sizeDp = markerIconDp.value[pressedId] ?: 32
                                        )
                                        deathFx.strike(
                                            id = pressedId,
                                            geo = target,
                                            icon = icon,
                                            rotationDeg = -nearest.rotation,
                                            alpha = nearest.alpha
                                        )
                                    }
                                    if (played) {
                                        // Hide the marker (alpha=0) so the death animation overlay
                                        // can draw its own copy without visual conflict. The marker
                                        // stays in overlays/markerRefs and reappears after the
                                        // animation.
                                        nearest.alpha = 0f
                                        if (pressedId != null) hiddenByDeath.value.add(pressedId)
                                        mapView.invalidate()
                                        if (pressedId != null && pressedId == selectedThreatIdState) {
                                            onNeutralize(pressedId)
                                        }
                                        // Remember the shot so a same-id respawn within the grace
                                        // window doesn't re-alert (the object itself is never removed).
                                        if (pressedId != null) AppSources.registry.markUserShot(pressedId)
                                        deathFx.strikeHaptics()
                                    }
                                }
                                return true
                            }
                            return false
                        }
                    })
                )

                // Instant threat-marker taps: on top of the markers, below the long-press
                // overlay (which is itself below only the drawing overlays). The overlay never
                // consumes, so osmdroid's own gesture pipeline (pan/double-tap) is untouched.
                mapView.overlays.add(
                    InstantThreatTapOverlay(
                        mapView = mapView,
                        markersProvider = { markerRefs.value.values },
                        hapticsOn = { hapticsOnState },
                        onThreatTap = onThreatTapped
                    )
                )

                mapView.invalidate()
            }
        },
        onRelease = { map ->
            map.onDetach()
            mapViewRef.value = null
        }
    )

        // Death flourish renderer: a sibling Compose layer over the map, driven by its own
        // vsync tick. Reading deathFrame subscribes THIS node to redraws only — the MapView
        // below never re-paints, and the layer consumes no touch input.
        Canvas(modifier = Modifier.matchParentSize()) {
            deathFrame.intValue
            val mv = mapViewRef.value
            if (deathFx.isActive && mv != null) {
                drawIntoCanvas { d -> deathFx.overlay.draw(d.nativeCanvas, mv, false) }
            }
        }
    }

    // The popup card's height lands a frame AFTER the reveal fires (the card isn't laid out yet
    // on the same frame). Once it's measured, reframe the revealed threat so it stays visible
    // below the card. Only reframes on the 0→>0 transition (a card first appearing): a card
    // already open means the reveal was framed with its height known, and resizing an open card
    // must not re-pan the camera.
    LaunchedEffect(popupCoverPx) {
        if (popupCoverPx <= 0) {
            lastPopupCoverPx.value = 0
            return@LaunchedEffect
        }
        val prev = lastPopupCoverPx.value
        lastPopupCoverPx.value = popupCoverPx
        if (prev != 0) return@LaunchedEffect
        val pos = lastRevealPos.value ?: return@LaunchedEffect
        val mv = mapViewRef.value ?: return@LaunchedEffect
        if (mv.width <= 0 || mv.height <= 0) return@LaunchedEffect
        val focusPt = focusLocationState ?: return@LaunchedEffect
        try {
            mv.zoomToBoundingBox(
                buildRevealBoundingBox(pos, focusPt, mv.height, popupCoverPx, zonesSheetCoverPx),
                true
            )
        } catch (_: Exception) {}
    }

    // The zones sheet's height is measured a frame after the sheet opens — the initial fit ran
    // with the 60% fallback. Once the real height is known, refit so the yellow zone truly sits
    // in the visible area above the sheet. Only on the 0→>0 transition (sheet first appearing).
    LaunchedEffect(zonesSheetCoverPx) {
        if (zonesSheetCoverPx <= 0) {
            lastZonesCoverPx.value = 0
            return@LaunchedEffect
        }
        val prev = lastZonesCoverPx.value
        lastZonesCoverPx.value = zonesSheetCoverPx
        if (prev != 0 || !zonesSheetOpen) return@LaunchedEffect
        val mv = mapViewRef.value ?: return@LaunchedEffect
        if (mv.width <= 0 || mv.height <= 0) return@LaunchedEffect
        val center = focusLocationState?.let { GeoPoint(it.lat, it.lon) } ?: mv.mapCenter
        fitZoneToPanel(mv, center)
    }

    // Shelter mode: while the overlay is up, unlock deep zoom (street-level shelter detail)
    // and zoom the camera to fit the full nearby-shelter range plus a buffer, so every marker
    // is visible at a glance. Leaving shelter mode re-caps the zoom at the threat-map viewport.
    // Runs as a dedicated effect (not inside the recompose-driven update block) so it fires
    // reliably after the overlay rebuild has placed the shelter markers.
    LaunchedEffect(showNearbyShelters, shelterZoomTick, focusLocationState, shelterIndex) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        mapView.maxZoomLevel = if (showNearbyShelters) SHELTER_MAX_ZOOM else NORMAL_MAX_ZOOM
        if (!showNearbyShelters) return@LaunchedEffect
        // Arm the guard: let the entry zoom animation run without tripping the auto-exit gate.
        shelterEntryGuardUntil.value = System.currentTimeMillis() + 1500
        val near = focusLocationState?.let { f -> shelterIndex?.nearest(f.lat, f.lon, limit = 25) }
        val box = near?.let { sheltersBoundingBox(it) }
        if (box != null) {
            mapView.zoomToBoundingBox(box, false)
        } else {
            val center = focusLocationState?.let { GeoPoint(it.lat, it.lon) } ?: mapView.mapCenter
            mapView.controller.animateTo(center, 18.0, 400L)
        }
    }

        // Death animations: real resolved/remove frames. The threat's own marker icon keeps
        // rendering in the overlay through the full flight and fades out across the explosion;
        // without a live marker, fall back to the raw fix + a fresh icon.
        // Subscribed ONLY while the shoot-down animation is enabled — turning it off means this
        // collector doesn't exist at all (no per-frame checks, no coroutines).
        LaunchedEffect(Unit) {
            snapshotFlow { uiState.deathAnimationEnabled }
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (!enabled) emptyFlow() else AppSources.registry.removedThreats
                }
                .collect { r ->
                    // NEPTUN re-sends a resolution within its 60s grace window — strike each
                    // threat once, so an already-witnessed resolution never re-strikes later
                    // (which read as a "random" replay ~1 min after the tally tap).
                    val nowMs = System.currentTimeMillis()
                    struckRemovalAt.entries.removeIf { nowMs - it.value > RESOLVED_REPLAY_GRACE_MS }
                    if (struckRemovalAt.containsKey(r.id)) return@collect
                    struckRemovalAt[r.id] = nowMs
                    // Skip resolutions that arrived while the map wasn't visible (Settings open,
                    // Shelter/Guide covering it, or app backgrounded), while an alert is live, or
                    // while the shelter overlay is up — nothing should grab the user's attention
                    // away from the shelters: no stale half-consumed animations on return, and no
                    // "bullet to nowhere" duds from threats that appeared and resolved unseen.
                    // During an alert the flourish plays ONLY if the threat was already in camera —
                    // a resolution that happened off-screen must not jerk the view mid-siren.
                    if (!mapIsUserFocus(pausedState, mapVisibleState, showNearbySheltersState, lifecycle.currentState)) {
                        return@collect
                    }
                    if (alertActiveState) {
                        val mapView = mapViewRef.value
                        if (mapView == null || !mapView.boundingBox.contains(r.lat, r.lon)) return@collect
                    }
                    if (r.type in hiddenTypesState) return@collect
                    val marker = markerRefs.value[r.id]
                    val anchor0 = GeoPoint(r.lat, r.lon)
                    if (marker == null || deathFx.isActiveFor(r.id)) {
                        // Already destroyed — a prior bullet landed (the server re-sent the
                        // resolution), so don't explode where the threat used to be: a follow-up
                        // projectile just streaks across and off-screen, then is dropped.
                        deathFx.strikeDud(r.id, anchor0)
                    } else {
                        val anchor = marker.position ?: anchor0
                        val base = IconCatalog.baseDeg(r.type, iconSetState)
                        val rotation = -(marker.rotation ?: (-(r.courseDeg.toFloat() - base + 360f) % 360f))
                        val icon = threatIconFor(
                            context, r.type, iconSetState
                        )
                        val markerAlpha = marker.alpha ?: 1f
                        val followBullet = followBulletState
                        val pressedId = r.id
                        deathFx.startAutoCountdown(r.type) {
                            // Countdown finished — unhook the marker, fire the strike.
                            mapViewRef.value?.overlays?.remove(marker)
                            markerRefs.value.entries.removeAll { it.value === marker }
                            deathFx.followStrike(anchor, followBullet)
                            mapViewRef.value?.invalidate()
                            deathFx.strike(
                                id = pressedId,
                                geo = anchor,
                                icon = icon,
                                rotationDeg = rotation,
                                alpha = markerAlpha
                            )
                            deathFx.strikeHaptics()
                        }
                    }
                }
        }

        // Tally-tap replay flourish: the whole show (viewport clustering, per-group zoom, staggered
        // bullets, haptics, camera return) is orchestrated by [DeathFxController]. The tick is
        // consumed ONLY when a decision is actually made — transient blockers (cold start,
        // Settings open, shelter overlay) leave it pending so it retries on the next
        // recomposition instead of silently dropping the show. A live official alert does NOT
        // block the replay — it's an explicit user action; only a NEW alert onset mid-show
        // ejects it (see the ejection effect below).
        val flourishShow = uiState.flourish
        if (flourishShow != null && flourishShow.tick != lastFlourishTick.value) {
            // Reading flourishRetryTick here subscribes this block to lifecycle RESUMEs, so a
            // pending replay is consumed promptly instead of waiting for a spontaneous
            // recomposition (Compose doesn't observe lifecycle).
            val playable = mapViewRef.value != null && (flourishRetryTick.value >= 0) &&
                mapIsUserFocus(pausedState, mapVisibleState, showNearbySheltersState, lifecycle.currentState)
            if (!playable) {
                // Transient — retried on a later recomposition; nothing consumed yet.
            } else {
                lastFlourishTick.value = flourishShow.tick
                if (!deathAnimationEnabledState) {
                    // Permanent blocker: tell the user why nothing played.
                    showToast(
                        String.format(strings.flourishDisabledToastFormat, strings.deathAnimationTitle)
                    )
                    DebugLog.recordFlourish(DebugLogReason.TOGGLE_OFF, now = System.currentTimeMillis())
                } else {
                    deathFx.startReplay(flourishShow.records)
                }
            }
        }

        // A red alert ejects the flourish immediately: cancel the pending show and erase the
        // already-drawn bullets so nothing playful distracts from the real alarm.
        LaunchedEffect(uiState.alertActive) {
            if (uiState.alertActive) deathFx.clear()
        }

        // Any modal covering the map (Settings, shelter overlay, …) ejects a RUNNING flourish
        // instantly — same rule as red alerts. A merely QUEUED replay stays queued and plays
        // when the map is uncovered again. The hint callback fires only when something was
        // actually interrupted/queued AND the animation toggle is on (they wanted to see it).
        LaunchedEffect(paused, mapVisible, showNearbyShelters) {
            if (paused || !mapVisible || showNearbyShelters) {
                val interrupted = deathFx.isActive
                val queuedPending = uiState.flourish?.let { it.tick != lastFlourishTick.value } == true
                if ((interrupted || queuedPending) && deathAnimationEnabledState) onFlourishEjected()
                deathFx.clear()
            }
        }

        // Surface the death-animation state up so the footer can swap its copy while a bullet
        // is flying / an explosion is on screen.
        LaunchedEffect(Unit) {
            deathFx.active.collect { active -> onDeathActiveChange(active) }
        }

        // Surface the auto-strike countdown so the UI can show a "tap to cancel" overlay.
        LaunchedEffect(Unit) {
            deathFx.countdown.collect { c -> onCountdownChange(c) }
        }

        // Surface whether an auto-strike death animation is in flight (after countdown fired).
        LaunchedEffect(Unit) {
            deathFx.autoStrikeActive.collect { active -> onAutoStrikeActiveChange(active) }
        }

        // Surface the threat type being targeted by the auto-countdown.
        LaunchedEffect(Unit) {
            deathFx.strikeType.collect { type -> onStrikeTypeChange(type) }
        }

        // Surface how many auto-strikes are pending/in-flight so the countdown strip can show
        // a real count (N during a wave, never 0 while a strike is actually queued).
        LaunchedEffect(Unit) {
            deathFx.pendingStrikeCount.collect { n -> onPendingStrikeCountChange(n) }
        }

        // User tapped the emergency-eject stop: cancel the countdown, eject any in-flight death
        // animation, stop the replay, and return the camera home — one clear() handles every
        // playful phase (countdown, auto-strike, manual long-press, tally replay).
        LaunchedEffect(onCancelRequestTick) {
            if (onCancelRequestTick == 0) return@LaunchedEffect
            deathFx.clear()
        }

        // After a user-shot death animation finishes, wait 2.1s then restore the hidden
        // marker with a scale-from-zero flourish. Server-resolved threats are NOT in
        // hiddenByDeath (they use the destroy path), so this only affects playful kills.
        LaunchedEffect(Unit) {
            var wasActive = false
            deathFx.active.collect { active ->
                if (wasActive && !active) {
                    delay(2100)
                    val ids = hiddenByDeath.value.toList()
                    hiddenByDeath.value.clear()
                    val mv = mapViewRef.value ?: return@collect
                    for (id in ids) {
                        val marker = markerRefs.value[id] ?: continue
                        val t = mapThreatsState.firstOrNull { it.id == id }
                        if (t == null) { marker.alpha = 1f; continue }
                        val targetIcon = marker.icon
                        val scaleDrawable = ScaleDrawable(targetIcon) { mv.invalidate() }
                        marker.icon = scaleDrawable
                        marker.alpha = 1f
                        mv.invalidate()
                        val anim = ValueAnimator.ofFloat(0f, 1f)
                        anim.duration = 300
                        anim.interpolator = DecelerateInterpolator()
                        anim.addUpdateListener { scaleDrawable.scale = it.animatedValue as Float }
                        anim.start()
                    }
                }
                wasActive = active
            }
        }

        // Surface the tally-tap replay progress so the footer can read "Resolving N threats"
        // per group; null clears the copy.
        LaunchedEffect(Unit) {
            deathFx.replayProgress.collect { onReplayProgressChange(it) }
        }

    // Markers are a pure function of the wall clock: orbit/drift/parked positions and headings
    // all derive from `now` (resolveThreatPose). One loop recomputes every marker's pose each
    // frame while anything is moving (30fps), idles at 1s otherwise, and freezes writes while the
    // map is hidden, paused, or a finger is on it (icons stay ground-fixed during pan — motion
    // resumes from the live clock the frame after release). Data changes rebuild markers via
    // overlayKey; this loop only touches in-place state (pose, rotation, staleness alpha).
    LaunchedEffect(Unit) {
        while (true) {
            if (pausedState || !mapVisibleState || mapTouching.value) {
                delay(150)
                continue
            }
            val mapView = mapViewRef.value
            if (mapView == null) {
                delay(1000)
                continue
            }
            val now = System.currentTimeMillis()
            var dirty = false
            var moving = false
            // De-overlap the live poses each frame so spread/grid/count stay consistent with the rebuild.
            val stepPx = ((if (threatIconZoomState) threatIconSizeDp(mapView.zoomLevelDouble) else 32) *
                context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
            val placements = deOverlapThreats(
                mapThreatsState.map { t ->
                    val p = engine.propsFor(t.type)
                    val pose = resolveThreatPose(engine, t, p, slowRedKmState, slowYellowKmState, now)
                    Triple(t.id, LatLng(pose.lat, pose.lon), t.type)
                },
                mapView, uiState.overlapMode, stepPx
            )
            for (t in mapThreatsState) {
                val marker = markerRefs.value[t.id] ?: continue
                if (t.id in hiddenByDeath.value) continue
                val placement = placements[t.id] ?: continue
                val targetPos = placement.pos ?: continue // collapsed into a count representative
                val props = engine.propsFor(t.type)
                // Staleness dimming + pose update in-place (both excluded from overlayKey, so a
                // full rebuild no longer runs for them).
                val targetAlpha = if (engine.isStale(t, props, now)) 0.45f else 1.0f
                if (marker.alpha != targetAlpha) {
                    marker.alpha = targetAlpha
                    dirty = true
                }
                val pose = resolveThreatPose(engine, t, props, slowRedKmState, slowYellowKmState, now)
                if (pose.mode != ThreatPoseMode.PARKED) moving = true
                val targetRot = if (t.areaOnly) 0f else {
                    val base = IconCatalog.baseDeg(t.type.toThreatType(), iconSetState)
                    threatMarkerRotation(pose.headingDeg, base)
                }
                if (marker.rotation != targetRot) {
                    marker.rotation = targetRot
                    dirty = true
                }
                if (chipLabel(t, placement.chip) != marker.subDescription) {
                    marker.setSubDescription(chipLabel(t, placement.chip))
                    dirty = true
                }
                val cur = marker.position
                if (cur != null &&
                    distanceFlat(cur.latitude, cur.longitude, targetPos.latitude, targetPos.longitude) > 1.0
                ) {
                    marker.position = targetPos
                    dirty = true
                }
            }
            if (dirty) mapView.invalidate()
            // Reveal badge: when the 8s window ends, strip the green dot off the revealed
            // marker (the dot is baked into the icon, so expiry is just an icon swap).
            val ring = newRingState.value
            if (ring != null && now >= ring.activeUntilMs) {
                newRingState.value = null
                ring.id?.let { id ->
                    markerRefs.value[id]?.let { m ->
                        val t = mapThreatsState.firstOrNull { it.id == id }
                        if (t != null) {
                            m.icon = threatIconFor(context, t.type.toThreatType(), iconSetState, areaOnly = t.areaOnly, sizeDp = markerIconDp.value[id] ?: 32)
                            dirty = true
                        }
                    }
                }
            }
            if (dirty) mapView.invalidate()
            delay(if (moving) 33 else 1000)
        }
    }
}

/** Wraps an icon drawable and draws it at a uniform [scale] around its center. Used for the
 *  scale-from-zero flourish when a user-shot threat marker reappears. */
private class ScaleDrawable(
    private val inner: Drawable,
    private val onInvalidate: () -> Unit
) : Drawable() {
    var scale = 0f
        set(value) { field = value; onInvalidate() }

    override fun draw(canvas: Canvas) {
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        inner.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { inner.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = inner.opacity
    override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
    override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
}
