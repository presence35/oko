package ua.ukrainedrones

import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.engine.threatTypeInfoByString
import ua.ukrainedrones.engine.distanceFlat
import ua.ukrainedrones.engine.NEPTUN_TYPES

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.LruCache
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
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
import org.osmdroid.views.overlay.Polygon

import java.io.File
import kotlin.math.cos
import kotlin.math.pow
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

/** Ukraine (incl. Crimea) plus a ~0.5° margin — the map can't pan past this. */
private val UA_VIEW_LIMITS = BoundingBox(UA_TIGHT_MAX_LAT, UA_TIGHT_MAX_LON, UA_TIGHT_MIN_LAT, UA_TIGHT_MIN_LON)

private val tileSystem = TileSystemWebMercator()

/** Bounding box that fits a zone circle centred on `center`, with a 5% margin. */
private fun zoneBoundingBox(center: IGeoPoint, radiusKm: Double): BoundingBox {
    val marginKm = radiusKm * 1.05
    val dLat = marginKm * 1000.0 / 110_574.0
    val dLon = marginKm * 1000.0 /
        (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
    return BoundingBox(
        center.latitude + dLat,
        center.longitude + dLon,
        center.latitude - dLat,
        center.longitude - dLon
    )
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

/** Threat marker icon at a fixed size regardless of map zoom. When [revealed], draws a small
 *  green dot in the icon's top-right corner — the notification-reveal marker — so it's a single
 *  tappable marker (no separate overlay intercepting the tap) that moves with the threat. */
private fun threatIconFor(
    context: Context,
    type: ThreatType,
    iconSet: ThreatIconSet,
    revealed: Boolean = false,
    areaOnly: Boolean = false
): Drawable {
    val key = "${type.name}|${iconSet.name}|$revealed|$areaOnly"
    val cached = threatIconCache.get(key)
    val bmp: Bitmap
    if (cached != null) {
        bmp = cached
    } else {
        val src = ContextCompat.getDrawable(context, IconCatalog.res(type, iconSet))!!
        val density = context.resources.displayMetrics.density
        val targetW = (32 * density).toInt().coerceAtLeast(2)
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
    val dLat = radiusMeters / 110_574.0
    val dLon = radiusMeters / (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
    return List(segments) { i ->
        val a = 2.0 * Math.PI * i / segments
        GeoPoint(center.latitude + dLat * sin(a), center.longitude + dLon * cos(a))
    }
}

/** A revealed threat that is still highlighted with the green dot badge. */
private data class NewRingState(val id: String?, val activeUntilMs: Long)

private const val NEW_RING_MS = 8_000L
/** How long the zone-slider camera refit waits after the value stops changing. */
private const val ZONE_REFIT_DEBOUNCE_MS = 350L

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
 *  threat (e.g. Kyiv with the focus on Odesa) never lands underneath the top popup card. */
private fun buildRevealBoundingBox(threat: LatLng, focus: LatLng?): BoundingBox {
    if (focus == null) {
        val span = 0.5
        return BoundingBox(
            threat.lat + span, threat.lon + span,
            threat.lat - span, threat.lon - span
        )
    }
    val ft = 0.28f  // focus vertical fraction from the top
    val fb = 0.72f  // threat vertical fraction from the top
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
            append('S').append(showNearbyShelters)
            if (showNearbyShelters) {
                append('L').append(selectedShelter?.shelter?.id)
            }
            for (city in uiState.redCities) append('C').append(city).append(';')
            for (t in uiState.mapThreats) appendThreatKey(t) // staleness handled in marker loop
        }
    }
    val lastOverlayKey = remember { mutableStateOf<String?>(null) }
    val lastFitUkraineTick = remember { mutableStateOf(-1) }
    val lastFollow = remember { mutableStateOf<LatLng?>(null) }
    val lastZoomTick = remember { mutableStateOf(-1) }
    val lastShelterSelectTick = remember { mutableStateOf(-1) }
    val lastFitZonesTick = remember { mutableStateOf(-1) }
    val lastFittedYellowKm = remember { mutableStateOf<Int?>(null) }
    val lastRevealTick = remember { mutableStateOf(-1) }
    val lastFlourishTick = remember { mutableStateOf(-1) }
    val newRingState = remember { mutableStateOf<NewRingState?>(null) }
    val didDefaultFit = remember { mutableStateOf(false) }
    val lastPinnedCity = remember { mutableStateOf<String?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val markerRefs = remember { mutableStateOf<MutableMap<String, Marker>>(mutableMapOf()) }
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
    val selectedId by selectedThreatId.collectAsState()
    val selectedThreatIdState by rememberUpdatedState(selectedId)
    val focusLocationState by rememberUpdatedState(uiState.focusLocation)
    val deathAnimationEnabledState by rememberUpdatedState(uiState.deathAnimationEnabled)
        val followBulletState by rememberUpdatedState(uiState.followBullet)
    val hapticsOnState by rememberUpdatedState(LocalHapticsEnabled.current)
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
                Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
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
    // The bbox is extended downward so the zone occupies the top 60% of the viewport (the
    // sheet covers ~40% below).
    val fitZoneToPanel: (MapView, IGeoPoint) -> Unit = { mv, center ->
        val zone = zoneBoundingBox(center, uiState.activeZoneParams.slowYellowKm.toDouble())
        val visibleFrac = 0.6f
        val dLat = zone.latNorth - center.latitude
        val southPad = dLat * 2 * ((1f / visibleFrac) - 1f)
        mv.zoomToBoundingBox(
            BoundingBox(zone.latNorth, zone.lonEast, zone.latSouth - southPad, zone.lonWest),
            true
        )
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
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
                // No +/– buttons — everyone uses pinch. Contours stay clean on the map.
                setBuiltInZoomControls(false)
                // Cap normal zoom at the ~5 km viewport level; deep zoom (street-level shelter
                // detail) is unlocked only while the shelter overlay is up (see the shelter
                // LaunchedEffect). This keeps the tile cache to the viewport the threat map
                // actually needs.
                maxZoomLevel = NORMAL_MAX_ZOOM
                // Clamp the viewport to Ukraine (incl. Crimea) plus a small margin so
                // the map can't pan out into foreign territory.
                setScrollableAreaLimitDouble(UA_VIEW_LIMITS)
                controller.setCenter(DEFAULT_CENTER)
                // Start at a city-level zoom instead of osmdroid's default whole-globe view;
                // once the first GPS fix lands, didDefaultFit re-zooms to the yellow zone.
                controller.setZoom(12.0)

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
                mapView.zoomToBoundingBox(zoneBoundingBox(center, radiusKm), true)
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
                lastRevealTick.value = reveal.tick
                val threat = LatLng(reveal.lat, reveal.lon)
                newRingState.value = NewRingState(
                    reveal.id, System.currentTimeMillis() + NEW_RING_MS
                )
                if (mapView.width > 0 && mapView.height > 0) {
                    // Harden: a bad framing box (or a not-yet-laid-out map) must never crash
                    // the composition thread — fall back to a plain centre-on-threat pan.
                    try {
                        mapView.zoomToBoundingBox(
                            buildRevealBoundingBox(threat, uiState.focusLocation), true
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
                            m.icon = threatIconFor(context, t.type.toThreatType(), iconSetState, revealed = true)
                            mapView.invalidate()
                        }
                    }
                }
            }

            if (overlayKey == lastOverlayKey.value) {
                // No change — skip clearing + redrawing the map (avoids banner flicker).
            } else if (deathFx.isActive) {
                // A death animation is mid-flight: defer the clear+rebuild until it finishes.
                // clearing mapView.overlays (of which deathFx is a member) while the 16ms
                // invalidate loop is drawing can race the overlay list. The 1s UI-state tick
                // recomposes this update block, so the deferred rebuild fires right after.
            } else {
                lastOverlayKey.value = overlayKey

                mapView.overlays.clear()
                markerRefs.value.clear()

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

                // City labels (English names on top of label-free tiles)
                mapView.overlays.add(
                    CityLabelOverlay(
                        context, lang, uiState.redCities,
                        uiState.showMediumCities, uiState.showSmallCities,
                        forceShowAllProvider = { deathFx.forceShowAllCities.value }
                    )
                )

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
                for (t in uiState.mapThreats) {
                    // A user-shot drone stays hidden while its death animation plays; the
                    // next redraw after the animation brings it back in place.
                    if (deathFx.isActiveFor(t.id)) continue
                    val nt = t
                    val props = engine.propsFor(nt.type)
                    engine.speedCache.record(nt.id, nt.updatedAtMillis ?: System.currentTimeMillis(), nt.lat, nt.lon)
                    val typeInfo = threatTypeInfoByString(nt.type)!!
                    val typeLabel = if (lang == AppLanguage.UA) typeInfo.labelUa else typeInfo.labelEn
                    val rawRegion = t.region ?: t.district ?: t.locality ?: strings.noRegion
                    val regionLabel = if (lang == AppLanguage.EN) Cities.uaToEn[rawRegion] ?: rawRegion else rawRegion
                    // Place markers at their dead-reckoned position straight away (matching the
                    // animation loop) so a rebuild never snaps a moving marker back to its raw fix
                    // and returning to the app doesn't flash stale fixes before the loop corrects.
                    val predicted = engine.speedCache.estimate(nt.id, nt, props)?.let {
                        engine.predictPosition(nt, it, props, System.currentTimeMillis())
                    }
                    val pos = predicted?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(nt.lat, nt.lon)
                    val stale = engine.isStale(nt, props, System.currentTimeMillis())
                    val nowMs = System.currentTimeMillis()
                    val ring = newRingState.value
                    val revealed = ring != null && t.id == ring.id && nowMs < ring.activeUntilMs
                    val marker = Marker(mapView).apply {
                        position = pos
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = threatIconFor(context, t.type.toThreatType(), iconSet, revealed = revealed, areaOnly = t.areaOnly)
                        alpha = if (stale) 0.45f else 1.0f
                        title = typeLabel
                        snippet = regionLabel
                        // Rotate to show course, mirroring NEPTUN's predict().heading: velocity
                        // bearing while live, else reported heading, else their A(id) pseudo-course.
                        // The classic icons face up at 0°; the photo/army sets have a baked-in
                        // facing angle, so their rotation is the course minus that base (0..360).
                        rotation = if (nt.areaOnly) 0f else {
                            val course = engine.courseDeg(nt).toFloat()
                            val base = IconCatalog.baseDeg(t.type.toThreatType(), iconSet)
                            (course - base + 360f) % 360f
                        }
                                                setOnMarkerClickListener { _, _ ->
                            // Immediate tick — the tap must feel instant; the card composes after.
                            if (hapticsOnState) hapticTick(context)
                            onThreatTapped(t)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                    markerRefs.value[t.id] = marker
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
                            setAnchor(Marker.ANCHOR_CENTER, 0.1f)
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
                                // Unhook the real marker so the normal pipeline stops drawing
                                // it; the overlay now renders its icon through the flight and
                                // drops it when the explosion starts.
                                mapView.overlays.remove(nearest)
                                markerRefs.value.entries.removeAll { it.value === nearest }
                                mapView.invalidate()
                                if (pressedId != null && pressedId == selectedThreatIdState) {
                                    onNeutralize(pressedId)
                                }
                                // Remember the shot so a same-id respawn within the grace window
                                // doesn't re-alert (the object itself is never removed).
                                if (pressedId != null) ConnectionHolder.getClient(context).markUserShot(pressedId)
                                if (deathAnimationEnabledState) {
                                    val target = nearest.position ?: GeoPoint(p.latitude, p.longitude)
                                    if (deathFx.isActiveFor(pressedId)) {
                                        // Already being struck — a follow-up projectile just
                                        // flies off-screen instead of exploding twice.
                                        deathFx.strikeDud(pressedId, target)
                                    } else {
                                        // User-initiated strike: never move the camera — the
                                        // user is already looking at the threat they shot.
                                        deathFx.strike(
                                            id = pressedId,
                                            geo = target,
                                            icon = nearest.icon,
                                            rotationDeg = nearest.rotation,
                                            alpha = nearest.alpha
                                        )
                                        deathFx.strikeHaptics()
                                    }
                                }
                                return true
                            }
                            return false
                        }
                    })
                )

                // Death flourish on top of everything else.
                mapView.overlays.add(deathFx.overlay)

                mapView.invalidate()
            }
        },
        onRelease = { map ->
            map.onDetach()
            mapViewRef.value = null
        }
    )

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
            mapView.zoomToBoundingBox(box, true)
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
                    if (!enabled) emptyFlow() else ConnectionHolder.getClient(context).removedThreats
                }
                .collect { r ->
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
                        // Unhook the real marker right away — the overlay draws its own copy of the
                        // icon, so keeping the shared marker would render the same drawable twice
                        // (its bounds/alpha are mutated per frame, and the marker's own draw fights
                        // back, making the icon flip or change direction mid-flight).
                        mapViewRef.value?.overlays?.remove(marker)
                        markerRefs.value.entries.removeAll { it.value === marker }
                        val anchor = marker.position ?: anchor0
                        val base = IconCatalog.baseDeg(r.type, iconSetState)
                        val rotation = marker.rotation ?: (r.courseDeg.toFloat() - base + 360f) % 360f
                        // A fresh copy, not the marker's shared drawable.
                        val icon = threatIconFor(
                            context, r.type, iconSetState
                        )
                        // With follow-the-bullet on, the camera glides onto the target so the
                        // strike is actually seen.
                        deathFx.followStrike(anchor, followBulletState)
                        mapViewRef.value?.invalidate()
                        deathFx.strike(
                            id = r.id,
                            geo = anchor,
                            icon = icon,
                            rotationDeg = rotation,
                            alpha = marker.alpha ?: 1f
                        )
                        deathFx.strikeHaptics()
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
            val playable = mapViewRef.value != null &&
                mapIsUserFocus(pausedState, mapVisibleState, showNearbySheltersState, lifecycle.currentState)
            if (!playable) {
                // Transient — retried on a later recomposition; nothing consumed yet.
            } else {
                lastFlourishTick.value = flourishShow.tick
                if (!deathAnimationEnabledState) {
                    // Permanent blocker: tell the user why nothing played.
                    showToast(
                        context,
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

        // Surface the tally-tap replay progress so the footer can read "Resolving N threats"
        // per group; null clears the copy.
        LaunchedEffect(Unit) {
            deathFx.replayProgress.collect { onReplayProgressChange(it) }
        }

        // Redraw the map at ~60fps while a death animation is playing and the map is visible,
        // so the overlay animates; otherwise idle at a slow tick (no battery cost).
        LaunchedEffect(Unit) {
            while (true) {
                if (deathFx.isActive && !pausedState && lifecycle.currentState >= Lifecycle.State.STARTED) {
                    mapViewRef.value?.invalidate()
                    // 30fps while a flourish plays: invalidate redraws the WHOLE overlay stack
                    // (tiles, markers, polygons, labels), and these effects are slow-moving —
                    // halving the cadence halves that cost with no visible difference.
                    delay(33)
                } else {
                    delay(1000)
                }
            }
        }

    // Smoothly advance markers between the (sparse) server fixes: predict each threat's
    // current position from its heading + estimated speed and move the marker in-place,
    // without clearing the whole map. Only invalidates when something actually moved.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            // The map is fully hidden behind Settings — skip the marker smoothing to save battery.
            if (pausedState) continue
            val mapView = mapViewRef.value ?: continue
            val now = System.currentTimeMillis()
            var dirty = false
            for (t in mapThreatsState) {
                val marker = markerRefs.value[t.id] ?: continue
                val nt = t
                val props = engine.propsFor(nt.type)
                // Staleness dimming + course rotation update in-place too (they're excluded
                // from overlayKey, so a full rebuild no longer runs for them).
                val targetAlpha = if (engine.isStale(nt, props, now)) 0.45f else 1.0f
                if (marker.alpha != targetAlpha) {
                    marker.alpha = targetAlpha
                    dirty = true
                }
                val targetRot = if (nt.areaOnly) 0f else {
                    val course = engine.courseDeg(nt).toFloat()
                    val base = IconCatalog.baseDeg(t.type.toThreatType(), iconSetState)
                    (course - base + 360f) % 360f
                }
                if (marker.rotation != targetRot) {
                    marker.rotation = targetRot
                    dirty = true
                }
                val speed = engine.speedCache.estimate(nt.id, nt, props) ?: continue
                val predicted = engine.predictPosition(nt, speed, props, now) ?: continue
                val cur = marker.position
                if (cur != null &&
                    distanceFlat(cur.latitude, cur.longitude, predicted.lat, predicted.lon) > 1.0
                ) {
                    marker.position = GeoPoint(predicted.lat, predicted.lon)
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
                            m.icon = threatIconFor(context, t.type.toThreatType(), iconSetState)
                            dirty = true
                        }
                    }
                }
            }
            if (dirty) mapView.invalidate()
        }
    }
}
