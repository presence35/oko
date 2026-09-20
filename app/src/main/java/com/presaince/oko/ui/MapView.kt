package com.presaince.oko
import com.presaince.oko.theme.AppPalette

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import com.presaince.oko.engine.AlertLevel
import com.presaince.oko.engine.LatLng
import com.presaince.oko.engine.NormalizedThreat
import com.presaince.oko.engine.ThreatEngine
import com.presaince.oko.engine.ThreatZone
import com.presaince.oko.engine.coversCityRaion
import com.presaince.oko.engine.destinationPoint
import com.presaince.oko.engine.threatTypeInfoByString
import com.presaince.oko.engine.toThreatType
import com.presaince.oko.community.CompactOblastBoundaries
import com.presaince.oko.community.CompactRaionBoundaries
import com.presaince.oko.ui.MapLibreBridge
import com.presaince.oko.ui.MapLibreHostView
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Odesa city centre — fallback camera target before the first GPS fix. */
private const val DEFAULT_CENTER_LAT = ODESA_LAT
private const val DEFAULT_CENTER_LON = ODESA_LON

/** Max zoom — ~5 km threat-map viewport; shelters no longer unlock deeper street-level zoom. */
private const val NORMAL_MAX_ZOOM = 14.5
private const val SHELTER_MAX_ZOOM = 14.5

/** Ukraine bounding limits. */
private const val UA_MIN_LAT = UA_TIGHT_MIN_LAT
private const val UA_MAX_LAT = UA_TIGHT_MAX_LAT
private const val UA_MIN_LON = UA_TIGHT_MIN_LON
private const val UA_MAX_LON = UA_TIGHT_MAX_LON

/** Bounding box over the nearest shelters, padded so every marker is comfortably in view. */
private fun sheltersBoundingBox(near: List<NearestShelter>): CameraBounds? {
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
    return CameraBounds(maxLat + pad, maxLon + pad, minLat - pad, minLon - pad)
}

// Bounded cache for rendered marker-icon BITMAPS — key "type|iconSet|revealed".
private val threatIconCache = object : LruCache<String, Bitmap>(48) {}

/** Threat marker icon size tracks map zoom — continuous so zoom interpolation doesn't jump. */
private fun threatIconSizeDp(zoom: Double): Int {
    val scale = ((zoom - 11.5) / 3.0 * 2.0 + 1.0).coerceIn(1.0, 3.0)
    return (32.0 * scale).roundToInt().coerceIn(32, 96)
}

/** Marker rotation that points a threat icon's nose along compass bearing. */
internal fun threatMarkerRotation(courseDeg: Float, baseDeg: Float): Float =
    (courseDeg - baseDeg + 360f) % 360f

/** Threat marker icon at a size that scales with zoom. */
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
            val dotColor = if (areaOnly) AppPalette.AreaOnlyDot.toInt() else AppPalette.SafeGreen.toInt()
            canvas.drawCircle(cx, cy, r, Paint().apply {
                isAntiAlias = true
                color = dotColor
            })
            canvas.drawCircle(cx, cy, r * 0.45f, Paint().apply {
                isAntiAlias = true
                color = Color.WHITE
            })
        }
        threatIconCache.put(key, bmp)
    }
    return BitmapDrawable(context.resources, bmp)
}

/**
 * "GPS dot" used as the My-Location marker, styled to look like Google Maps: a solid blue
 * core with a white outer ring. Dimmed to grey while a fix is acquiring. Drawn scaled by
 * [dotScaleForZoom] so it never grows large enough to overtake the alert zone rings at
 * low zoom (the dot stays a pin, not a billboard).
 */
private fun gpsDotBitmap(context: Context, hasFix: Boolean): Bitmap {
    val density = context.resources.displayMetrics.density
    val coreR = 6f * density
    val whiteRingHalf = 1.2f * density          // 2.4dp ring visual, centered on the core edge
    val strokeWidth = 2.4f * density
    val size = (((coreR + whiteRingHalf) + strokeWidth / 2f) * 2).toInt().coerceAtLeast(2)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val coreColor = if (hasFix) AppPalette.GpsBlue.toInt() else AppPalette.TextSecondary.toInt()
    val ringColor = if (hasFix) Color.WHITE else AppPalette.TextSecondary.toInt()
    canvas.drawCircle(cx, cy, coreR, Paint().apply { isAntiAlias = true; color = coreColor })
    canvas.drawCircle(cx, cy, coreR + whiteRingHalf, Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        color = ringColor
    })
    return bmp
}

/** Scale the location dot down at low zoom so it never dwarfs alert-zone rings. */
private fun dotScaleForZoom(zoom: Double): Float {
    // full size by zoom ~9, half size at/below zoom ~4 (far-out map view).
    val t = ((zoom - 4.0) / 5.0).coerceIn(0.0, 1.0).toFloat()
    return 0.5f + 0.5f * t
}

private data class NewRingState(val id: String?, val activeUntilMs: Long)

private const val NEW_RING_MS = 8_000L
private const val ZONE_REFIT_DEBOUNCE_MS = 350L

internal data class ThreatScreenPlacement(
    val offsetDx: Float = 0f,
    val offsetDy: Float = 0f,
    val chip: String? = null,
    val visible: Boolean = true
)

private fun chipLabel(t: NormalizedThreat, chip: String?, showThreatIds: Boolean): String? {
    val sim = if (t.simulated) "SIM" else null
    if (!showThreatIds && sim == null && chip == null) return null
    val shortId = if (showThreatIds) t.id.takeLast(4) else null
    return when {
        sim != null && shortId != null && chip != null -> "$shortId · $sim · $chip"
        sim != null && shortId != null -> "$shortId · $sim"
        sim != null && chip != null -> "$sim · $chip"
        sim != null -> sim
        shortId != null && chip != null -> "$shortId · $chip"
        shortId != null -> "$shortId"
        chip != null -> chip
        else -> null
    }
}

/** Deterministic screen-space de-overlap for threats sharing a coordinate or screen area. */
private fun deOverlapThreats(
    items: List<Triple<String, LatLng, String>>, // id, outcome, type
    project: (Double, Double) -> PointF?,
    mode: OverlapMode,
    stepPx: Int,
    viewWidth: Int,
    viewHeight: Int
): Map<String, ThreatScreenPlacement> {
    val out = HashMap<String, ThreatScreenPlacement>(items.size)
    if (mode == OverlapMode.DEFAULT || viewWidth <= 0 || viewHeight <= 0) {
        for ((id, _, _) in items) {
            out[id] = ThreatScreenPlacement()
        }
        return out
    }

    val clusters = mutableListOf<MutableList<Triple<String, LatLng, String>>>()
    val thresholdSq = (stepPx * stepPx).toFloat()
    for (item in items) {
        val pt = project(item.second.lat, item.second.lon) ?: continue
        var placed = false
        for (cluster in clusters) {
            val firstPt = project(cluster[0].second.lat, cluster[0].second.lon) ?: continue
            val dx = pt.x - firstPt.x
            val dy = pt.y - firstPt.y
            if (dx * dx + dy * dy <= thresholdSq) {
                cluster.add(item)
                placed = true
                break
            }
        }
        if (!placed) {
            clusters.add(mutableListOf(item))
        }
    }

    for (members in clusters) {
        val sorted = members.sortedBy { it.first }
        if (sorted.size == 1) {
            out[sorted[0].first] = ThreatScreenPlacement()
            continue
        }
        when (mode) {
            OverlapMode.GRID -> {
                val cols = kotlin.math.ceil(kotlin.math.sqrt(sorted.size.toDouble())).toInt().coerceAtLeast(1)
                val rows = kotlin.math.ceil(sorted.size / cols.toDouble()).toInt()
                sorted.forEachIndexed { i, (id, _, _) ->
                    val dx = (i % cols - (cols - 1) / 2.0) * stepPx
                    val dy = (i / cols - (rows - 1) / 2.0) * stepPx
                    out[id] = ThreatScreenPlacement(dx.toFloat(), dy.toFloat(), null, true)
                }
            }
            OverlapMode.SPREAD -> {
                val half = stepPx * 0.75f
                sorted.forEachIndexed { i, (id, _, _) ->
                    val dx = (i - (sorted.size - 1) / 2.0) * half
                    val dy = if (i % 2 == 0) -half * 0.25f else half * 0.25f
                    out[id] = ThreatScreenPlacement(dx.toFloat(), dy.toFloat(), null, true)
                }
            }
            OverlapMode.COUNT -> {
                val byType = LinkedHashMap<String, MutableList<Triple<String, LatLng, String>>>()
                for (m in sorted) byType.getOrPut(m.third) { mutableListOf() }.add(m)
                val reps = byType.values.map { it.first() }
                val cols = kotlin.math.ceil(kotlin.math.sqrt(reps.size.toDouble())).toInt().coerceAtLeast(1)
                val rows = kotlin.math.ceil(reps.size / cols.toDouble()).toInt()
                reps.forEachIndexed { i, (id, _, type) ->
                    val dx = (i % cols - (cols - 1) / 2.0) * stepPx
                    val dy = (i / cols - (rows - 1) / 2.0) * stepPx
                    val count = byType.getValue(type).size
                    out[id] = ThreatScreenPlacement(
                        dx.toFloat(), dy.toFloat(),
                        if (count > 1) "$count" else null,
                        true
                    )
                }
                for (m in sorted) {
                    if (m.first !in out) out[m.first] = ThreatScreenPlacement(visible = false)
                }
            }
            else -> {
                for (m in sorted) out[m.first] = ThreatScreenPlacement()
            }
        }
    }
    return out
}

private val shelterBitmapCache = mutableMapOf<String, Bitmap>()

/** Minimal hand-drawn chevron marker for shelters. */
private fun shelterMarkerBitmap(
    context: Context,
    type: ShelterType,
    isSelected: Boolean
): Bitmap {
    val key = "${type.name}_$isSelected"
    shelterBitmapCache[key]?.let { return it }

    val density = context.resources.displayMetrics.density
    val typeColor = when (type) {
        ShelterType.MOBILE -> AppPalette.ShelterMobile.toInt()
        ShelterType.BASIC -> AppPalette.SafeGreen.toInt()
        ShelterType.BUNKER -> AppPalette.GpsBlue.toInt()
    }
    val markerColor = if (isSelected) Color.WHITE else typeColor
    val strokeW = 2.6f * density
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

/** True when the map screen is foregrounded — shelters no longer block morale. */
internal fun mapIsUserFocus(
    paused: Boolean,
    mapVisible: Boolean,
    @Suppress("UNUSED_PARAMETER") sheltersUp: Boolean,
    lifecycleState: Lifecycle.State
): Boolean = !paused && mapVisible && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

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
    popupCoverPxState: State<Int> = remember { mutableIntStateOf(0) },
    zonesSheetCoverPxState: State<Int> = remember { mutableIntStateOf(0) },
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
    modifier: Modifier = Modifier
) {
    val typeCatalog by AppSources.registry.typeCatalog.collectAsState()
    val engine = remember(typeCatalog) { ThreatEngine(typeCatalog) }
    val context = LocalContext.current
    val strings = Strings.get(lang)

    val bridgeState = remember { mutableStateOf<MapLibreBridge?>(null) }
    val mapScope = rememberCoroutineScope()
    val camera = remember { MapCameraCoordinator() }

    val deathFx = remember {
        DeathFxController(
            context = context,
            bridge = { bridgeState.value },
            iconFor = { type -> threatIconFor(context, type, iconSet) },
            showDetail = { rec, grp -> String.format(strings.flourishLogDetailFormat, rec, grp) },
            scope = mapScope
        )
    }
    val lastFitUkraineTick = remember { mutableStateOf(fitUkraineTick) }
    val lastFollow = remember { mutableStateOf<LatLng?>(null) }
    val lastZoomTick = remember { mutableStateOf(-1) }
    val lastShelterSelectTick = remember { mutableStateOf(-1) }
    val lastFitZonesTick = remember { mutableStateOf(-1) }
    val lastFittedYellowKm = remember { mutableStateOf<Int?>(null) }
    val lastRevealTick = remember { mutableStateOf(-1) }
    val lastCenterTick = remember { mutableStateOf(-1) }
    val lastArmTick = remember { mutableStateOf(0) }
    val lastZonesCoverPx = remember { mutableStateOf(0) }
    val lastFlourishTick = remember { mutableStateOf(-1) }
    val flourishRetryTick = remember { mutableStateOf(0) }
    val newRingState = remember { mutableStateOf<NewRingState?>(null) }
    val didDefaultFit = remember { mutableStateOf(false) }
    val lastPinnedCity = remember { mutableStateOf<String?>(null) }

    val hiddenByDeath = remember { mutableStateOf<MutableSet<String>>(mutableSetOf()) }
    val restoringScales = remember { mutableStateMapOf<String, Float>() }

    val pausedState by rememberUpdatedState(paused)
    val mapVisibleState by rememberUpdatedState(mapVisible)
    val alertActiveState by rememberUpdatedState(uiState.alertActive)
    val showNearbySheltersState by rememberUpdatedState(showNearbyShelters)
    val shelterSnapshot = remember { mutableStateOf<List<NearestShelter>>(emptyList()) }
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
    val showThreatIdsOnMapState by rememberUpdatedState(uiState.showThreatIdsOnMap)
    val hapticsOnState by rememberUpdatedState(LocalHapticsEnabled.current)
    val liveUiState by rememberUpdatedState(uiState)

    val threatOutcomes = remember { mutableStateMapOf<String, BehaviorOutcome>() }
    val threatPlacements = remember { mutableStateMapOf<String, ThreatScreenPlacement>() }
    val zoneRefitJob = remember { mutableStateOf<Job?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    flourishRetryTick.value++
                }
                Lifecycle.Event.ON_PAUSE -> {
                    deathFx.clear()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Sync GPU layers with UI state. Split three ways so a change in one group
    // never re-runs the others (a mode toggle must not redo borders/zones).
    // The first alerts apply waits one frame: the dark style frame composites
    // before the ~400ms fill upload blocks Main on cold start, so cold start
    // shows a dark map with fills popping in instead of a white stall.
    var firstAlertsApplied by remember { mutableStateOf(false) }
    LaunchedEffect(
        bridgeState.value,
        bridgeState.value?.layersReady?.value,
        uiState.alertRegionMode,
        uiState.alertOblastIds,
        uiState.alertRaionKeys,
        uiState.alertYellowOblastIds,
        uiState.alertYellowRaionKeys
    ) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        if (!bridge.layersReady.value) return@LaunchedEffect
        if (!firstAlertsApplied) {
            withFrameNanos { }
            firstAlertsApplied = true
        }
        bridge.updateAlerts(
            oblastIds = uiState.alertOblastIds,
            raionKeys = uiState.alertRaionKeys,
            yellowOblastIds = uiState.alertYellowOblastIds,
            yellowRaionKeys = uiState.alertYellowRaionKeys,
            alertRegionMode = uiState.alertRegionMode
        )
    }
    LaunchedEffect(
        bridgeState.value,
        bridgeState.value?.layersReady?.value,
        uiState.showBorders,
        uiState.showRegionBorders,
        uiState.alertRegionMode
    ) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        if (!bridge.layersReady.value) return@LaunchedEffect
        bridge.updateBorders(
            showBorders = uiState.showBorders,
            showRegionBorders = uiState.showRegionBorders,
            alertRegionMode = uiState.alertRegionMode
        )
    }
    LaunchedEffect(
        bridgeState.value,
        bridgeState.value?.layersReady?.value,
        uiState.focusLocation,
        uiState.activeZoneParams.slowYellowKm,
        uiState.activeZoneParams.slowRedKm,
        uiState.activeZone
    ) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        if (!bridge.layersReady.value) return@LaunchedEffect
        bridge.updateZones(
            centerLat = uiState.focusLocation?.lat,
            centerLon = uiState.focusLocation?.lon,
            slowRedKm = uiState.activeZoneParams.slowRedKm.toDouble(),
            slowYellowKm = uiState.activeZoneParams.slowYellowKm.toDouble()
        )
    }

    // Camera updates driven by UI state
    LaunchedEffect(
        bridgeState.value,
        uiState.focusLocation,
        uiState.followMe,
        uiState.pinnedCity,
        fitUkraineTick,
        zoomTick,
        fitZonesTick,
        uiState.centerRequest
    ) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        val replayOwnsCamera = deathFx.isReplayActive ||
            (uiState.flourish != null && uiState.flourish.tick != lastFlourishTick.value)
        val focus = uiState.focusLocation

        // Follow focus point
        if (focus != null && (lastFollow.value?.lat != focus.lat || lastFollow.value?.lon != focus.lon)) {
            lastFollow.value = focus
            if (!replayOwnsCamera && !camera.isFollowLocked(System.currentTimeMillis())) {
                bridge.animateTo(focus.lat, focus.lon)
            }
        } else if (focus == null && lastFollow.value != null) {
            lastFollow.value = null
        }

        // Default fit
        if (!didDefaultFit.value && focus != null && !replayOwnsCamera) {
            didDefaultFit.value = true
            camera.fitZone(bridge, focus.lat, focus.lon, uiState.activeZoneParams.slowYellowKm.toDouble())
        }

        // Pinned city change
        val pinned = uiState.pinnedCity
        if (!uiState.followMe && pinned != null && lastPinnedCity.value != pinned.nameUa) {
            lastPinnedCity.value = pinned.nameUa
            camera.fitZone(bridge, pinned.lat, pinned.lon, uiState.activeZoneParams.slowYellowKm.toDouble())
        } else if (uiState.followMe) {
            lastPinnedCity.value = null
        }

        // Fit Ukraine
        if (fitUkraineTick != lastFitUkraineTick.value) {
            lastFitUkraineTick.value = fitUkraineTick
            camera.fitBox(bridge, UA_MAX_LAT, UA_MAX_LON, UA_MIN_LAT, UA_MIN_LON)
        }

        // Zone button zoom
        if (zoomZone != null && zoomTick != lastZoomTick.value) {
            lastZoomTick.value = zoomTick
            val centerLat = focus?.lat ?: bridge.latitude
            val centerLon = focus?.lon ?: bridge.longitude
            val radiusKm = when (zoomZone) {
                ThreatZone.INNER -> uiState.activeZoneParams.slowRedKm.toDouble()
                else -> uiState.activeZoneParams.slowYellowKm.toDouble()
            }
            camera.fitZone(bridge, centerLat, centerLon, radiusKm, animate = false)
        }

        // Fit zones sheet panel
        if (fitZonesTick != lastFitZonesTick.value) {
            lastFitZonesTick.value = fitZonesTick
            val centerLat = focus?.lat ?: bridge.latitude
            val centerLon = focus?.lon ?: bridge.longitude
            lastFittedYellowKm.value = uiState.activeZoneParams.slowYellowKm
            camera.fitZoneToPanel(
                bridge, centerLat, centerLon,
                uiState.activeZoneParams.slowYellowKm.toDouble(),
                zonesSheetCoverPxState.value
            )
        }

        // Center threat request
        val center = uiState.centerRequest
        if (center != null && center.tick != lastCenterTick.value) {
            lastCenterTick.value = center.tick
            camera.animateTo(bridge, center.lat, center.lon, NORMAL_MAX_ZOOM)
        }
    }

    // Debounced slider refit
    LaunchedEffect(uiState.activeZoneParams.slowYellowKm, zonesSheetOpen) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        val focus = uiState.focusLocation ?: return@LaunchedEffect
        val fitted = lastFittedYellowKm.value
        if (zonesSheetOpen && didDefaultFit.value && fitted != null && fitted != uiState.activeZoneParams.slowYellowKm) {
            lastFittedYellowKm.value = uiState.activeZoneParams.slowYellowKm
            zoneRefitJob.value?.cancel()
            zoneRefitJob.value = mapScope.launch {
                delay(ZONE_REFIT_DEBOUNCE_MS)
                camera.fitZoneToPanel(
                    bridge, focus.lat, focus.lon,
                    uiState.activeZoneParams.slowYellowKm.toDouble(),
                    zonesSheetCoverPxState.value
                )
            }
        }
    }

    // Reveal request
    LaunchedEffect(revealRequest) {
        val reveal = revealRequest ?: return@LaunchedEffect
        if (reveal.tick != lastRevealTick.value) {
            lastRevealTick.value = reveal.tick
            newRingState.value = NewRingState(reveal.id, System.currentTimeMillis() + NEW_RING_MS)
            val focus = uiState.focusLocation
            camera.armFit(
                reveal.id, reveal.lat, reveal.lon,
                focus?.lat ?: Double.NaN, focus?.lon ?: Double.NaN,
                reveal.tick.toLong()
            )
            lastArmTick.value = camera.pendingVersion
        }
    }

    // Tapping a threat marker only updates the selection state and presents the
// popup card — it no longer pans the map. Deselecting clears any in-flight
// reveal fit (and its follow-me lock) so a stale pending fit can't linger.
LaunchedEffect(selectedId) {
    if (selectedId == null) camera.clearFit()
}

    // Refine pending camera fit once popup card is measured
    LaunchedEffect(lastArmTick.value, popupCoverPxState.value) {
        val cover = popupCoverPxState.value
        if (cover <= 0) return@LaunchedEffect
        val bridge = bridgeState.value ?: return@LaunchedEffect
        camera.refinePendingFit(bridge, selectedThreatIdState, bridge.height, cover, zonesSheetCoverPxState.value)
    }

    // Refine zone sheet fit
    LaunchedEffect(zonesSheetCoverPxState.value) {
        val cover = zonesSheetCoverPxState.value
        if (cover <= 0) {
            lastZonesCoverPx.value = 0
            return@LaunchedEffect
        }
        val prev = lastZonesCoverPx.value
        lastZonesCoverPx.value = cover
        if (prev != 0 || !zonesSheetOpen) return@LaunchedEffect
        val bridge = bridgeState.value ?: return@LaunchedEffect
        val focus = focusLocationState ?: return@LaunchedEffect
        camera.fitZoneToPanel(
            bridge, focus.lat, focus.lon,
            uiState.activeZoneParams.slowYellowKm.toDouble(),
            cover
        )
    }

    // Shelter mode zoom & fit. Pins are bounded to the red-zone radius so a sparse focus
    // (a pinned city with no local shelters) never pads the list with far-away shelters,
    // blows up the fit box and trips the zoom-out auto-exit on the next pan or zoom.
    // The 25 are snapshotted at entry — pan/zoom then cannot change the set, only move it.
    LaunchedEffect(showNearbyShelters, shelterZoomTick, focusLocationState, shelterIndex, slowRedKmState) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        bridge.setMaxZoom(if (showNearbyShelters) SHELTER_MAX_ZOOM else NORMAL_MAX_ZOOM)
        if (!showNearbyShelters) {
            shelterSnapshot.value = emptyList()
            return@LaunchedEffect
        }
        val focus = focusLocationState
        val near = focus?.let { f ->
            shelterIndex?.nearest(f.lat, f.lon, limit = 25, maxDistanceMeters = slowRedKmState * 1000.0)
        }
        if (near.isNullOrEmpty()) {
            shelterSnapshot.value = emptyList()
            if (focus != null && shelterIndex != null) {
                showToast(strings.shelterEmpty)
                onExitShelterMode()
                return@LaunchedEffect
            }
            camera.animateTo(bridge, bridge.latitude, bridge.longitude, 16.0, 400L)
            return@LaunchedEffect
        }
        shelterSnapshot.value = near
        val box = sheltersBoundingBox(near)
        if (box != null) {
            camera.fitBox(bridge, box.north, box.east, box.south, box.west, durationMs = 0)
        } else {
            camera.animateTo(bridge, focus.lat, focus.lon, 16.0, 400L)
        }
    }

    LaunchedEffect(deathFx) {
        deathFx.bindAutoStrike(
            outerScope = this,
            removedThreats = AppSources.registry.removedThreats,
            deathAnimationEnabled = snapshotFlow { uiState.deathAnimationEnabled },
            isMapInFocus = { mapIsUserFocus(pausedState, mapVisibleState, showNearbySheltersState, lifecycle.currentState) },
            hiddenTypes = { hiddenTypesState },
            resolveOutcome = { id -> threatOutcomes[id] },
            resolveIcon = { type -> threatIconFor(context, type, iconSetState) },
            resolveRotation = { r ->
                val outcome = threatOutcomes[r.id]
                val base = IconCatalog.baseDeg(r.type, iconSetState)
                outcome?.headingDeg ?: ((r.courseDeg.toFloat() - base + 360f) % 360f)
            }
        )
    }

    // Tally-tap replay flourish
    val flourishShow = uiState.flourish
    if (flourishShow != null && flourishShow.tick != lastFlourishTick.value) {
        val playable = bridgeState.value != null && (flourishRetryTick.value >= 0) &&
            mapIsUserFocus(pausedState, mapVisibleState, showNearbySheltersState, lifecycle.currentState)
        if (playable) {
            lastFlourishTick.value = flourishShow.tick
            if (!deathAnimationEnabledState) {
                showToast(String.format(strings.flourishDisabledToastFormat, strings.deathAnimationTitle))
                DebugLog.recordFlourish(DebugLogReason.TOGGLE_OFF, now = System.currentTimeMillis())
            } else {
                deathFx.startReplay(flourishShow.records)
            }
        }
    }

    LaunchedEffect(uiState.alertActive) {
        if (uiState.alertActive) deathFx.clear()
    }

    LaunchedEffect(paused, mapVisible) {
        if (paused || !mapVisible) {
            deathFx.clear()
        }
    }

    LaunchedEffect(Unit) { deathFx.active.collect { active -> onDeathActiveChange(active) } }
    LaunchedEffect(Unit) { deathFx.countdown.collect { c -> onCountdownChange(c) } }
    LaunchedEffect(Unit) { deathFx.autoStrikeActive.collect { active -> onAutoStrikeActiveChange(active) } }
    LaunchedEffect(Unit) { deathFx.strikeType.collect { type -> onStrikeTypeChange(type) } }
    LaunchedEffect(Unit) { deathFx.pendingStrikeCount.collect { n -> onPendingStrikeCountChange(n) } }

    LaunchedEffect(onCancelRequestTick) {
        if (onCancelRequestTick == 0) return@LaunchedEffect
        deathFx.clear()
    }

    // User-shot death animation restore flourish
    LaunchedEffect(Unit) {
        var wasActive = false
        deathFx.active.collect { active ->
            if (active) {
                bridgeState.value?.invalidateOverlay()
            }
            if (wasActive && !active) {
                delay(2100)
                val ids = hiddenByDeath.value.toList()
                hiddenByDeath.value.clear()
                for (id in ids) {
                    val anim = ValueAnimator.ofFloat(0f, 1f)
                    anim.duration = 300
                    anim.interpolator = DecelerateInterpolator()
                    anim.addUpdateListener {
                        restoringScales[id] = it.animatedValue as Float
                    }
                    anim.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            restoringScales.remove(id)
                        }
                    })
                    anim.start()
                }
            }
            wasActive = active
        }
    }

    LaunchedEffect(Unit) {
        deathFx.replayProgress.collect { onReplayProgressChange(it) }
    }

    // Behavior update ticker
    LaunchedEffect(Unit) {
        while (true) {
            if (pausedState || !mapVisibleState) {
                delay(150)
                continue
            }
            val bridge = bridgeState.value
            if (bridge == null) {
                delay(1000)
                continue
            }
            val now = System.currentTimeMillis()
            val behaviors = listOf<ThreatBehavior>(
                OrbitBehavior(slowRedKmState, slowYellowKmState),
                StaleDriftBehavior
            )
            var moving = false
            val currentIds = mapThreatsState.map { it.id }.toSet()
            for (id in threatOutcomes.keys.toList()) {
                if (id !in currentIds) threatOutcomes.remove(id)
            }
            for (t in mapThreatsState) {
                engine.speedCache.record(t.id, t.updatedAtMillis ?: now, t.lat, t.lon)
                val outcome = resolveThreatBehavior(engine, t, behaviors, now)
                threatOutcomes[t.id] = outcome
                if (outcome.moving) moving = true
            }

            // De-overlap in screen space
            val stepPx = ((if (threatIconZoomState) threatIconSizeDp(bridge.zoom) else 32) *
                context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
            val outcomesList = mapThreatsState.map { t ->
                val outcome = threatOutcomes[t.id] ?: BehaviorOutcome(t.lat, t.lon, 0f, moving = false)
                Triple(t.id, LatLng(outcome.lat, outcome.lon), t.type)
            }
            val placements = deOverlapThreats(
                outcomesList,
                { lat, lon -> bridge.project(lat, lon) },
                uiState.overlapMode,
                stepPx,
                bridge.width,
                bridge.height
            )
            threatPlacements.clear()
            threatPlacements.putAll(placements)

            // Reveal badge expiry
            val ring = newRingState.value
            if (ring != null && now >= ring.activeUntilMs) {
                newRingState.value = null
            }

            delay(if (moving) 33 else 1000)
            bridge.invalidateOverlay()
        }
    }

    // City alerts mapping
    val displayAlerts = remember(
        uiState.cityAlerts,
        uiState.alertRegionMode,
        uiState.alertOblastIds,
        uiState.alertYellowOblastIds,
        uiState.alertRaionKeys,
        uiState.alertYellowRaionKeys
    ) {
        if (uiState.alertRegionMode != AlertRegionMode.FILL) {
            buildMap {
                putAll(uiState.cityAlerts)
                for (city in Cities.ALL) {
                    if (city.nameUa in uiState.cityAlerts) continue
                    val stem = Cities.cityOblast[city.nameUa] ?: continue
                    val id = CompactOblastBoundaries.canonicalId(stem) ?: continue
                    when {
                        id in uiState.alertOblastIds -> put(city.nameUa, AlertLevel.RED)
                        coversCityRaion(city.nameUa, id, uiState.alertRaionKeys) -> put(city.nameUa, AlertLevel.RED)
                        id in uiState.alertYellowOblastIds -> put(city.nameUa, AlertLevel.YELLOW)
                        coversCityRaion(city.nameUa, id, uiState.alertYellowRaionKeys) -> put(city.nameUa, AlertLevel.YELLOW)
                    }
                }
            }
        } else uiState.cityAlerts
    }

    val cityLabelOverlay = remember(context, lang, displayAlerts, uiState.alertRegionMode, uiState.showLargeCities, uiState.showMediumCities, uiState.showSmallCities) {
        CityLabelOverlay(
            context, lang,
            cityAlertLevels = displayAlerts,
            alertRegionMode = uiState.alertRegionMode,
            uiState.showLargeCities, uiState.showMediumCities, uiState.showSmallCities,
            forceShowAllProvider = { deathFx.forceShowAllCities.value }
        )
    }
    val cityLabelOverlayState = androidx.compose.runtime.rememberUpdatedState(cityLabelOverlay)

    LaunchedEffect(cityLabelOverlay) {
        bridgeState.value?.invalidateOverlay()
    }

    val chipPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textSize = 11f * context.resources.displayMetrics.density
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        MapLibreHostView(
            modifier = Modifier.fillMaxSize(),
            onCameraChange = {},
            onBridgeReady = { bridge ->
                bridgeState.value = bridge
                bridge.onDrawOverlay = { canvas ->
                    val projLambda: (Double, Double) -> PointF? = { lat, lon ->
                        bridge.project(lat, lon)
                    }
                    val currentZoom = bridge.zoom
                    val nowMs = System.currentTimeMillis()
                    val ring = newRingState.value

                    // 1. City labels (culled by visible viewport bounding box queried once per frame)
                    val bounds = bridge.visibleGeoBounds(paddingX = 240f, paddingY = 60f)
                    val overlay = cityLabelOverlayState.value
                    if (bounds != null) {
                        overlay.draw(
                            canvas, currentZoom, projLambda,
                            bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon
                        )
                    } else {
                        overlay.draw(canvas, currentZoom, projLambda)
                    }

                    // 2. Nearby shelters — drawn from the snapshot taken at entry so pan/zoom can't change the set.
                    if (showNearbySheltersState && shelterSnapshot.value.isNotEmpty()) {
                        for (item in shelterSnapshot.value) {
                            val pt = bridge.project(item.shelter.lat, item.shelter.lon) ?: continue
                            val isSelected = selectedShelter?.shelter?.id == item.shelter.id
                            val bmp = shelterMarkerBitmap(context, item.shelter.type, isSelected)
                            canvas.drawBitmap(bmp, pt.x - bmp.width / 2f, pt.y - bmp.height.toFloat(), null)
                        }
                    }

                    // 3. GPS dot — marks the zone epicentre in every mode.
                    val dotPos = liveUiState.userLocation ?: focusLocationState
                    if (dotPos != null) {
                        val pt = bridge.project(dotPos.lat, dotPos.lon)
                        if (pt != null) {
                            val bmp = gpsDotBitmap(context, liveUiState.gpsFixAvailable)
                            val scale = dotScaleForZoom(currentZoom)
                            val pw = bmp.width / 2f
                            val ph = bmp.height / 2f
                            canvas.save()
                            canvas.translate(pt.x, pt.y)
                            canvas.scale(scale, scale)
                            canvas.drawBitmap(bmp, -pw, -ph, null)
                            canvas.restore()
                        }
                    }

                    // 5. Threat markers
                    val matrix = Matrix()
                    val paint = Paint().apply { isAntiAlias = true }
                    for (t in mapThreatsState) {
                        if (deathFx.isActiveFor(t.id) || t.id in hiddenByDeath.value) continue
                        val placement = threatPlacements[t.id]
                        if (placement != null && !placement.visible) continue
                        val outcome = threatOutcomes[t.id] ?: BehaviorOutcome(t.lat, t.lon, 0f, moving = false)
                        val pt = bridge.project(outcome.lat, outcome.lon) ?: continue
                        val sx = pt.x + (placement?.offsetDx ?: 0f)
                        val sy = pt.y + (placement?.offsetDy ?: 0f)
                        val props = engine.propsFor(t.type)
                        val stale = engine.isStale(t, props, nowMs)
                        val revealed = ring != null && t.id == ring.id && nowMs < ring.activeUntilMs
                        val sizeDp = if (threatIconZoomState) threatIconSizeDp(currentZoom) else 32
                        val iconDrawable = threatIconFor(
                            context, t.type.toThreatType(), iconSetState,
                            revealed = revealed, areaOnly = t.areaOnly, sizeDp = sizeDp
                        )
                        val bmp = (iconDrawable as? BitmapDrawable)?.bitmap ?: continue
                        val base = IconCatalog.baseDeg(t.type.toThreatType(), iconSetState)
                        val rot = if (t.areaOnly) 0f else threatMarkerRotation(outcome.headingDeg, base)
                        val scale = restoringScales[t.id] ?: 1f

                        paint.alpha = ((if (stale) 0.45f else 1.0f) * scale * 255).toInt().coerceIn(0, 255)

                        matrix.reset()
                        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                        matrix.postRotate(rot)
                        if (scale < 1f) matrix.postScale(scale, scale)
                        matrix.postTranslate(sx, sy)
                        canvas.drawBitmap(bmp, matrix, paint)

                        // Sub-description / count chip
                        val chip = chipLabel(t, placement?.chip, showThreatIdsOnMapState)
                        if (chip != null) {
                            canvas.drawText(chip, sx, sy + bmp.height / 2f + 14f * context.resources.displayMetrics.density, chipPaint)
                        }
                    }

                    // 6. Death FX & Flourish overlay
                    if (deathFx.isActive) {
                        deathFx.overlay.draw(
                            canvas = canvas,
                            density = context.resources.displayMetrics.density,
                            viewWidth = bridge.width.toFloat(),
                            viewHeight = bridge.height.toFloat(),
                            resources = context.resources,
                            zoom = currentZoom,
                            project = projLambda
                        )
                        bridge.invalidateOverlayNextFrame()
                    }
                }
                var lastScaleZoom = -1.0
                bridge.setOnCameraMoveListener {
                    val z = bridge.zoom
                    if (Math.abs(z - lastScaleZoom) > 0.01) {
                        lastScaleZoom = z
                        onScaleChange(bridge.metersPerPixel())
                    }
                }
                val findBestThreatAt: (PointF, Float) -> NormalizedThreat? = { screenPt, extraPaddingDp ->
                    val density = context.resources.displayMetrics.density
                    val iconSizeDp = if (threatIconZoomState) threatIconSizeDp(bridge.zoom) else 32
                    val baseRadius = (iconSizeDp / 2f + extraPaddingDp) * density
                    val maxRadius = 48f * density

                    val activeThreats = ArrayList<Pair<NormalizedThreat, PointF>>(mapThreatsState.size)
                    for (t in mapThreatsState) {
                        if (deathFx.isActiveFor(t.id) || t.id in hiddenByDeath.value) continue
                        val placement = threatPlacements[t.id]
                        if (placement != null && !placement.visible) continue
                        val outcome = threatOutcomes[t.id] ?: BehaviorOutcome(t.lat, t.lon, 0f, moving = false)
                        val sp = bridge.project(outcome.lat, outcome.lon) ?: continue
                        val sx = sp.x + (placement?.offsetDx ?: 0f)
                        val sy = sp.y + (placement?.offsetDy ?: 0f)
                        activeThreats.add(t to PointF(sx, sy))
                    }

                    if (activeThreats.isEmpty()) {
                        null
                    } else {
                        val shelterPts = if (showNearbySheltersState && shelterSnapshot.value.isNotEmpty()) {
                            shelterSnapshot.value.mapNotNull { item ->
                                bridge.project(item.shelter.lat, item.shelter.lon)?.let { p ->
                                    PointF(p.x, p.y - 18f * density)
                                }
                            }
                        } else emptyList()

                        var bestThreat: NormalizedThreat? = null
                        var bestDist = Float.MAX_VALUE

                        for (i in 0 until activeThreats.size) {
                            val (t, pt) = activeThreats[i]
                            val dx = pt.x - screenPt.x
                            val dy = pt.y - screenPt.y
                            val d = sqrt(dx * dx + dy * dy)

                            var neighborDist = Float.MAX_VALUE
                            for (j in 0 until activeThreats.size) {
                                if (i == j) continue
                                val otherPt = activeThreats[j].second
                                val ndx = otherPt.x - pt.x
                                val ndy = otherPt.y - pt.y
                                val nd = sqrt(ndx * ndx + ndy * ndy)
                                if (nd < neighborDist) neighborDist = nd
                            }
                            for (sPt in shelterPts) {
                                val ndx = sPt.x - pt.x
                                val ndy = sPt.y - pt.y
                                val nd = sqrt(ndx * ndx + ndy * ndy)
                                if (nd < neighborDist) neighborDist = nd
                            }

                            val dynamicRadius = maxRadius.coerceAtMost(neighborDist * 0.5f).coerceAtLeast(baseRadius)
                            if (d <= dynamicRadius && d < bestDist) {
                                bestThreat = t
                                bestDist = d
                            }
                        }
                        bestThreat
                    }
                }

                val hitTestShelterOrThreat: (PointF) -> Boolean = { screenPt ->
                    var handled = false
                    // 1. Check shelter hit — snapshot, so tap target matches what's drawn.
                    if (showNearbySheltersState && shelterSnapshot.value.isNotEmpty()) {
                        val density = context.resources.displayMetrics.density
                        val threshold = 32f * density
                        var bestShelter: NearestShelter? = null
                        var bestDist = threshold
                        for (item in shelterSnapshot.value) {
                            val p = bridge.project(item.shelter.lat, item.shelter.lon) ?: continue
                            val dx = p.x - screenPt.x
                            val dy = (p.y - 18f * density) - screenPt.y
                            val d = sqrt(dx * dx + dy * dy)
                            if (d <= bestDist) {
                                bestShelter = item
                                bestDist = d
                            }
                        }
                        if (bestShelter != null) {
                            onShelterTapped(bestShelter)
                            handled = true
                        }
                    }

                    // 2. Check threat hit if not hit shelter
                    if (!handled) {
                        val bestThreat = findBestThreatAt(screenPt, 4f)
                        if (bestThreat != null) {
                            if (hapticsOnState) hapticTick(context)
                            onThreatTapped(bestThreat)
                            handled = true
                        }
                    }
                    handled
                }

                bridge.setOnDirectTapListener { screenPt ->
                    hitTestShelterOrThreat(screenPt)
                }

                bridge.setOnMapClickListener { screenPt, _ ->
                    if (!hitTestShelterOrThreat(screenPt)) {
                        onMapTapped()
                    }
                }
                bridge.setOnMapLongClickListener { screenPt, geoPt ->
                    val targetThreat = findBestThreatAt(screenPt, 8f)
                    if (targetThreat != null && deathAnimationEnabledState) {
                        val threatId = targetThreat.id
                        val outcome = threatOutcomes[threatId]
                        val strikeLat = outcome?.lat ?: geoPt.latitude
                        val strikeLon = outcome?.lon ?: geoPt.longitude
                        val threatType = targetThreat.type.toThreatType()
                        val icon = threatIconFor(
                            context, threatType, iconSetState,
                            sizeDp = if (threatIconZoomState) threatIconSizeDp(bridge.zoom) else 32
                        )
                        val base = IconCatalog.baseDeg(threatType, iconSetState)
                        val rotation = if (targetThreat.areaOnly) 0f else threatMarkerRotation(outcome?.headingDeg ?: engine.courseDeg(targetThreat).toFloat(), base)
                        val played = if (deathFx.isActiveFor(threatId)) {
                            deathFx.strikeDud(threatId, strikeLat, strikeLon)
                        } else {
                            val speedMps = typeCatalog[threatType.apiKey]?.nominalSpeedMps ?: 0.0
                            val course = outcome?.headingDeg?.toDouble() ?: engine.courseDeg(targetThreat)
                            val distMeters = speedMps * (DEATH_EXPLOSION_START_MS / 1000.0)
                            val intercept = if (distMeters > 0.0 && (course != 0.0 || outcome?.headingDeg != null)) {
                                destinationPoint(strikeLat, strikeLon, distMeters, course)
                            } else {
                                LatLng(strikeLat, strikeLon)
                            }
                            deathFx.strike(
                                id = threatId,
                                geo = intercept,
                                startGeo = LatLng(strikeLat, strikeLon),
                                icon = icon,
                                rotationDeg = rotation,
                                alpha = 1f,
                                type = threatType
                            )
                        }
                        if (played) {
                            hiddenByDeath.value.add(threatId)
                            if (threatId == selectedThreatIdState) {
                                onNeutralize(threatId)
                            }
                            AppSources.registry.markUserShot(threatId)
                            deathFx.strikeHaptics()
                        }
                    }
                }
            }
        )
    }
}
