package ua.ukrainedrones

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
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import ua.ukrainedrones.engine.AlertLevel
import ua.ukrainedrones.engine.LatLng
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.ThreatEngine
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatZone
import ua.ukrainedrones.engine.distanceFlat
import ua.ukrainedrones.engine.threatTypeInfoByString
import ua.ukrainedrones.engine.toThreatType
import ua.ukrainedrones.source.RESOLVED_REPLAY_GRACE_MS
import ua.ukrainedrones.ui.MapLibreBridge
import ua.ukrainedrones.ui.MapLibreHostView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Odesa city centre — fallback camera target before the first GPS fix. */
private const val DEFAULT_CENTER_LAT = ODESA_LAT
private const val DEFAULT_CENTER_LON = ODESA_LON

/** Max zoom outside shelter mode — the ~5 km threat-map viewport. */
private const val NORMAL_MAX_ZOOM = 14.5

/** Deep zoom, unlocked only while the shelter overlay is up (street-level shelter detail). */
private const val SHELTER_MAX_ZOOM = 19.0

/** Zooming below this level makes shelter pins clutter — auto-exit shelter mode. */
private const val SHELTER_AUTO_EXIT_ZOOM = 13.0

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

/** Threat marker icon size tracks map zoom. */
private fun threatIconSizeDp(zoom: Double): Int {
    val scale = ((zoom - 11.5) / 3.0 * 2.0 + 1.0).coerceIn(1.0, 3.0)
    return (32.0 * scale / 8.0).roundToInt() * 8
}

/** Position for the "approaching, precision unknown" orbit: a point on the yellow ring around [center]. */
private fun orbitPosition(center: LatLng, radiusMeters: Double, angleRad: Double): LatLng {
    val bearing = (Math.toDegrees(angleRad) + 360.0) % 360.0
    return ua.ukrainedrones.engine.destinationPoint(center.lat, center.lon, radiusMeters, bearing)
}

/** The destination city an approximate-position threat is heading toward. */
private fun orbitCenter(nt: NormalizedThreat): LatLng? {
    if (nt.areaOnly || nt.positionQuality != "approx") return null
    val place = courseTargetPlace(nt.explanationShort) ?: return null
    return Cities.findCity(place)?.let { LatLng(it.lat, it.lon) }
}

/** An approximate-position threat heading toward a known city circles that city's yellow ring. */
private fun shouldOrbitDestination(nt: NormalizedThreat, destination: LatLng, redKm: Int): Boolean {
    if (nt.areaOnly || nt.positionQuality != "approx") return false
    return distanceFlat(destination.lat, destination.lon, nt.lat, nt.lon) / 1000.0 > redKm
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
            val dotColor = if (areaOnly) Color.rgb(255, 183, 77) else Color.rgb(76, 175, 80)
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

/** Classic "blue glowing dot" used as the GPS location icon. */
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

/** Map pin with the tip at the bottom centre. */
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

private data class NewRingState(val id: String?, val activeUntilMs: Long)

private const val NEW_RING_MS = 8_000L
private const val ZONE_REFIT_DEBOUNCE_MS = 350L
private const val ORBIT_PERIOD_MS = 15_000L

private fun orbitPhase(id: String): Double {
    val deg = Math.floorMod(id.hashCode(), 360)
    return Math.toRadians(deg.toDouble())
}

internal fun orbitAngle(now: Long, id: String): Double =
    (now / ORBIT_PERIOD_MS.toDouble()) * 2.0 * Math.PI + orbitPhase(id)

internal fun orbitTangentBearing(angleRad: Double): Double {
    val deg = Math.toDegrees(atan2(cos(angleRad), -sin(angleRad)))
    return (deg + 360.0) % 360.0
}

internal enum class ThreatPoseMode { PARKED, ORBIT, DRIFT }

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

internal data class ThreatScreenPlacement(
    val screenX: Float?,
    val screenY: Float?,
    val chip: String?
)

private fun chipLabel(t: NormalizedThreat, chip: String?): String? {
    val sim = if (t.simulated) "SIM" else null
    return when {
        sim != null && chip != null -> "$sim · $chip"
        sim != null -> sim
        else -> chip
    }
}

/** Deterministic screen-space de-overlap for threats sharing a coordinate. */
private fun deOverlapThreats(
    poses: List<Triple<String, LatLng, String>>, // id, pose, type
    project: (Double, Double) -> PointF?,
    mode: OverlapMode,
    stepPx: Int,
    viewWidth: Int,
    viewHeight: Int
): Map<String, ThreatScreenPlacement> {
    val raw = HashMap<String, ThreatScreenPlacement>(poses.size)
    if (mode == OverlapMode.DEFAULT || viewWidth <= 0 || viewHeight <= 0) {
        for ((id, pose, _) in poses) {
            val pt = project(pose.lat, pose.lon)
            raw[id] = ThreatScreenPlacement(pt?.x, pt?.y, null)
        }
        return raw
    }

    val cells = HashMap<Pair<Int, Int>, MutableList<Triple<String, LatLng, String>>>()
    for (item in poses) {
        val (_, pose, _) = item
        val pt = project(pose.lat, pose.lon) ?: continue
        val key = (pt.x.toInt() / stepPx) to (pt.y.toInt() / stepPx)
        cells.getOrPut(key) { mutableListOf() }.add(item)
    }
    val out = HashMap<String, ThreatScreenPlacement>(poses.size)
    for (members in cells.values) {
        val sorted = members.sortedBy { it.first }
        if (sorted.size == 1) {
            val (id, pose, _) = sorted[0]
            val pt = project(pose.lat, pose.lon)
            out[id] = ThreatScreenPlacement(pt?.x, pt?.y, null)
            continue
        }
        val firstPt = project(sorted[0].second.lat, sorted[0].second.lon) ?: continue
        when (mode) {
            OverlapMode.GRID -> {
                val cols = kotlin.math.ceil(kotlin.math.sqrt(sorted.size.toDouble())).toInt().coerceAtLeast(1)
                val rows = kotlin.math.ceil(sorted.size / cols.toDouble()).toInt()
                sorted.forEachIndexed { i, (id, _, _) ->
                    val dx = (i % cols - (cols - 1) / 2.0) * stepPx
                    val dy = (i / cols - (rows - 1) / 2.0) * stepPx
                    out[id] = ThreatScreenPlacement((firstPt.x + dx).toFloat(), (firstPt.y + dy).toFloat(), null)
                }
            }
            OverlapMode.SPREAD -> {
                val half = stepPx / 2.0
                sorted.forEachIndexed { i, (id, _, _) ->
                    val dx = i * half
                    val dy = if (i % 2 == 0) 0.0 else half
                    out[id] = ThreatScreenPlacement((firstPt.x + dx).toFloat(), (firstPt.y + dy).toFloat(), null)
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
                        (firstPt.x + dx).toFloat(), (firstPt.y + dy).toFloat(),
                        if (count > 1) "$count" else null
                    )
                }
                for (m in sorted) {
                    if (m.first !in out) out[m.first] = ThreatScreenPlacement(null, null, null)
                }
            }
            else -> {}
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
        ShelterType.MOBILE -> Color.rgb(255, 160, 0)
        ShelterType.BASIC -> Color.rgb(76, 175, 80)
        ShelterType.BUNKER -> Color.rgb(33, 150, 243)
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

/** True when nothing covers the map and the app is visible. */
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

    val struckRemovalAt = remember { HashMap<String, Long>() }
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

    val threatPoses = remember { mutableStateMapOf<String, MarkerPose>() }
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

    val deathFrame = remember { mutableIntStateOf(0) }
    val cameraFrame = remember { mutableIntStateOf(0) }
    LaunchedEffect(deathFx) {
        while (true) {
            withFrameNanos {}
            if (deathFx.isActive) {
                deathFrame.intValue++
                bridgeState.value?.invalidateOverlay()
            }
        }
    }

    SideEffect {
        bridgeState.value?.invalidateOverlay()
    }

    // Sync GPU layers with UI state
    LaunchedEffect(
        bridgeState.value,
        uiState.fillAlertRegions,
        uiState.showBorders,
        uiState.showRegionBorders,
        uiState.alertOblastTokens,
        uiState.alertRaionKeys,
        uiState.alertYellowOblastTokens,
        uiState.alertYellowRaionKeys,
        uiState.focusLocation,
        uiState.activeZoneParams.slowYellowKm,
        uiState.activeZoneParams.slowRedKm,
        uiState.activeZone
    ) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        bridge.updateAlerts(
            oblastStems = uiState.alertOblastTokens,
            raionKeys = uiState.alertRaionKeys,
            yellowOblastStems = uiState.alertYellowOblastTokens,
            yellowRaionKeys = uiState.alertYellowRaionKeys,
            fillEnabled = uiState.fillAlertRegions
        )
        bridge.updateBorders(
            showBorders = uiState.showBorders,
            showRegionBorders = uiState.showRegionBorders
        )
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
                zonesSheetCoverPx
            )
        }

        // Center threat request
        val center = uiState.centerRequest
        if (center != null && center.tick != lastCenterTick.value) {
            lastCenterTick.value = center.tick
            camera.fitZone(bridge, center.lat, center.lon, 12.0)
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
                    zonesSheetCoverPx
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
    LaunchedEffect(lastArmTick.value, popupCoverPx) {
        if (popupCoverPx <= 0) return@LaunchedEffect
        val bridge = bridgeState.value ?: return@LaunchedEffect
        camera.refinePendingFit(bridge, selectedThreatIdState, bridge.height, popupCoverPx, zonesSheetCoverPx)
    }

    // Refine zone sheet fit
    LaunchedEffect(zonesSheetCoverPx) {
        if (zonesSheetCoverPx <= 0) {
            lastZonesCoverPx.value = 0
            return@LaunchedEffect
        }
        val prev = lastZonesCoverPx.value
        lastZonesCoverPx.value = zonesSheetCoverPx
        if (prev != 0 || !zonesSheetOpen) return@LaunchedEffect
        val bridge = bridgeState.value ?: return@LaunchedEffect
        val focus = focusLocationState ?: return@LaunchedEffect
        camera.fitZoneToPanel(
            bridge, focus.lat, focus.lon,
            uiState.activeZoneParams.slowYellowKm.toDouble(),
            zonesSheetCoverPx
        )
    }

    // Shelter mode zoom & fit
    LaunchedEffect(showNearbyShelters, shelterZoomTick, focusLocationState, shelterIndex) {
        val bridge = bridgeState.value ?: return@LaunchedEffect
        bridge.setMaxZoom(if (showNearbyShelters) SHELTER_MAX_ZOOM else NORMAL_MAX_ZOOM)
        if (!showNearbyShelters) return@LaunchedEffect
        shelterEntryGuardUntil.value = System.currentTimeMillis() + 1500
        val near = focusLocationState?.let { f -> shelterIndex?.nearest(f.lat, f.lon, limit = 25) }
        val box = near?.let { sheltersBoundingBox(it) }
        if (box != null) {
            camera.fitBox(bridge, box.north, box.east, box.south, box.west, durationMs = 0)
        } else {
            val centerLat = focusLocationState?.lat ?: bridge.latitude
            val centerLon = focusLocationState?.lon ?: bridge.longitude
            camera.animateTo(bridge, centerLat, centerLon, 18.0, 400L)
        }
    }

    // Death animations for removed threats
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.deathAnimationEnabled }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) emptyFlow() else AppSources.registry.removedThreats
            }
            .collect { r ->
                val nowMs = System.currentTimeMillis()
                struckRemovalAt.entries.removeIf { nowMs - it.value > RESOLVED_REPLAY_GRACE_MS }
                if (struckRemovalAt.containsKey(r.id)) return@collect
                struckRemovalAt[r.id] = nowMs

                if (!mapIsUserFocus(pausedState, mapVisibleState, showNearbySheltersState, lifecycle.currentState)) {
                    return@collect
                }
                if (r.type in hiddenTypesState) return@collect
                val bridge = bridgeState.value
                val pose = threatPoses[r.id]
                val anchorLat = pose?.lat ?: r.lat
                val anchorLon = pose?.lon ?: r.lon

                if (deathFx.isActiveFor(r.id)) {
                    deathFx.strikeDud(r.id, anchorLat, anchorLon)
                } else {
                    val threatType = r.type
                    val base = IconCatalog.baseDeg(threatType, iconSetState)
                    val rotation = pose?.headingDeg ?: ((r.courseDeg.toFloat() - base + 360f) % 360f)
                    val icon = threatIconFor(context, threatType, iconSetState)
                    val followBullet = followBulletState
                    val pressedId = r.id
                    deathFx.startAutoCountdown(threatType) {
                        deathFx.followStrike(anchorLat, anchorLon, followBullet)
                        deathFx.strike(
                            id = pressedId,
                            lat = anchorLat,
                            lon = anchorLon,
                            icon = icon,
                            rotationDeg = rotation,
                            alpha = 1f
                        )
                        deathFx.strikeHaptics()
                    }
                }
            }
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

    LaunchedEffect(paused, mapVisible, showNearbyShelters) {
        if (paused || !mapVisible || showNearbyShelters) {
            val interrupted = deathFx.isActive
            val queuedPending = uiState.flourish?.let { it.tick != lastFlourishTick.value } == true
            if ((interrupted || queuedPending) && deathAnimationEnabledState) onFlourishEjected()
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

    // Pose update ticker
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
            var moving = false
            for (t in mapThreatsState) {
                val props = engine.propsFor(t.type)
                engine.speedCache.record(t.id, t.updatedAtMillis ?: now, t.lat, t.lon)
                val pose = resolveThreatPose(engine, t, props, slowRedKmState, slowYellowKmState, now)
                threatPoses[t.id] = pose
                if (pose.mode != ThreatPoseMode.PARKED) moving = true
            }

            // De-overlap in screen space
            val stepPx = ((if (threatIconZoomState) threatIconSizeDp(bridge.zoom) else 32) *
                context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
            val posesList = mapThreatsState.map { t ->
                val p = threatPoses[t.id] ?: MarkerPose(t.lat, t.lon, 0f, ThreatPoseMode.PARKED)
                Triple(t.id, LatLng(p.lat, p.lon), t.type)
            }
            val placements = deOverlapThreats(
                posesList,
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
    val displayAlerts = remember(uiState.cityAlerts, uiState.fillAlertRegions, uiState.alertOblastTokens, uiState.alertYellowOblastTokens) {
        if (!uiState.fillAlertRegions) {
            buildMap {
                putAll(uiState.cityAlerts)
                for (city in Cities.ALL) {
                    if (city.nameUa in uiState.cityAlerts) continue
                    val stem = Cities.cityOblast[city.nameUa] ?: continue
                    when {
                        stem in uiState.alertOblastTokens -> put(city.nameUa, AlertLevel.RED)
                        stem in uiState.alertYellowOblastTokens -> put(city.nameUa, AlertLevel.YELLOW)
                    }
                }
            }
        } else uiState.cityAlerts
    }

    val suppressedCities = remember(uiState.cityAlerts, uiState.fillAlertRegions, uiState.alertOblastTokens, uiState.alertYellowOblastTokens, uiState.alertRaionKeys, uiState.alertYellowRaionKeys) {
        if (uiState.fillAlertRegions) {
            uiState.cityAlerts.mapNotNull { (cityName, level) ->
                val stem = Cities.cityOblast[cityName] ?: return@mapNotNull null
                val raion = CityRaions.cityRaion[cityName]?.lowercase()?.trim()
                val covered = when (level) {
                    AlertLevel.RED -> stem in uiState.alertOblastTokens ||
                        (raion != null && (stem to raion) in uiState.alertRaionKeys)
                    AlertLevel.YELLOW -> (stem in uiState.alertYellowOblastTokens ||
                        (raion != null && (stem to raion) in uiState.alertYellowRaionKeys)) ||
                        stem in uiState.alertOblastTokens
                    else -> false
                }
                if (covered) cityName else null
            }.toSet()
        } else emptySet()
    }

    val cityLabelOverlay = remember(context, lang, displayAlerts, suppressedCities, uiState.showLargeCities, uiState.showMediumCities, uiState.showSmallCities) {
        CityLabelOverlay(
            context, lang,
            cityAlertLevels = displayAlerts,
            suppressedAlertCities = suppressedCities,
            uiState.showLargeCities, uiState.showMediumCities, uiState.showSmallCities,
            forceShowAllProvider = { deathFx.forceShowAllCities.value }
        )
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
            onCameraChange = {
                cameraFrame.intValue++
            },
            onBridgeReady = { bridge ->
                bridgeState.value = bridge
                bridge.onDrawOverlay = { canvas ->
                    val projLambda: (Double, Double) -> PointF? = { lat, lon ->
                        bridge.project(lat, lon)
                    }
                    val currentZoom = bridge.zoom
                    val nowMs = System.currentTimeMillis()
                    val ring = newRingState.value

                    // 1. City labels
                    cityLabelOverlay.draw(canvas, currentZoom, projLambda)

                    // 2. Nearby shelters
                    if (showNearbySheltersState && focusLocationState != null && shelterIndex != null) {
                        val nearList = shelterIndex.nearest(focusLocationState!!.lat, focusLocationState!!.lon, limit = 25)
                        for (item in nearList) {
                            val pt = bridge.project(item.shelter.lat, item.shelter.lon) ?: continue
                            val isSelected = selectedShelter?.shelter?.id == item.shelter.id
                            val bmp = shelterMarkerBitmap(context, item.shelter.type, isSelected)
                            canvas.drawBitmap(bmp, pt.x - bmp.width / 2f, pt.y - bmp.height.toFloat(), null)
                        }
                    }

                    // 3. GPS dot
                    if (uiState.followMe) {
                        val pos = uiState.userLocation ?: focusLocationState
                        if (pos != null) {
                            val pt = bridge.project(pos.lat, pos.lon)
                            if (pt != null) {
                                val bmp = gpsDotBitmap(context, uiState.gpsFixAvailable)
                                canvas.drawBitmap(bmp, pt.x - bmp.width / 2f, pt.y - bmp.height / 2f, null)
                            }
                        }
                    }

                    // 4. Pinned city pin
                    if (!uiState.followMe && uiState.pinnedCity != null) {
                        val city = uiState.pinnedCity!!
                        val pt = bridge.project(city.lat, city.lon)
                        if (pt != null) {
                            val bmp = pinBitmap(context)
                            canvas.drawBitmap(bmp, pt.x - bmp.width / 2f, pt.y - bmp.height.toFloat() * 1.5f, null)
                        }
                    }

                    // 5. Threat markers
                    val matrix = Matrix()
                    val paint = Paint().apply { isAntiAlias = true }
                    for (t in mapThreatsState) {
                        if (deathFx.isActiveFor(t.id) || t.id in hiddenByDeath.value) continue
                        val pose = threatPoses[t.id] ?: MarkerPose(t.lat, t.lon, 0f, ThreatPoseMode.PARKED)
                        val pt = bridge.project(pose.lat, pose.lon) ?: continue
                        val sx = pt.x
                        val sy = pt.y
                        val placement = threatPlacements[t.id]
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
                        val rot = if (t.areaOnly) 0f else threatMarkerRotation(pose.headingDeg, base)
                        val scale = restoringScales[t.id] ?: 1f

                        paint.alpha = ((if (stale) 0.45f else 1.0f) * scale * 255).toInt().coerceIn(0, 255)

                        matrix.reset()
                        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                        matrix.postRotate(rot)
                        if (scale < 1f) matrix.postScale(scale, scale)
                        matrix.postTranslate(sx, sy)
                        canvas.drawBitmap(bmp, matrix, paint)

                        // Sub-description / count chip
                        val chip = chipLabel(t, placement?.chip)
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
                    }
                }
                bridge.setOnCameraMoveListener {
                    cameraFrame.intValue++
                    onScaleChange(bridge.metersPerPixel())
                    if (showNearbySheltersState &&
                        System.currentTimeMillis() >= shelterEntryGuardUntil.value &&
                        bridge.zoom < SHELTER_AUTO_EXIT_ZOOM
                    ) {
                        onExitShelterMode()
                    }
                }
                bridge.setOnMapClickListener { screenPt, _ ->
                    // 1. Check shelter hit
                    if (showNearbySheltersState && focusLocationState != null && shelterIndex != null) {
                        val density = context.resources.displayMetrics.density
                        val threshold = 32f * density
                        val nearList = shelterIndex.nearest(focusLocationState!!.lat, focusLocationState!!.lon, limit = 25)
                        var bestShelter: NearestShelter? = null
                        var bestDist = threshold
                        for (item in nearList) {
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
                            return@setOnMapClickListener
                        }
                    }

                    // 2. Check threat hit
                    val density = context.resources.displayMetrics.density
                    val threshold = 36f * density
                    var bestThreat: NormalizedThreat? = null
                    var bestDist = threshold
                    for (t in mapThreatsState) {
                        val pose = threatPoses[t.id] ?: MarkerPose(t.lat, t.lon, 0f, ThreatPoseMode.PARKED)
                        val sp = bridge.project(pose.lat, pose.lon) ?: continue
                        val dx = sp.x - screenPt.x
                        val dy = sp.y - screenPt.y
                        val d = sqrt(dx * dx + dy * dy)
                        if (d <= bestDist) {
                            bestThreat = t
                            bestDist = d
                        }
                    }
                    if (bestThreat != null) {
                        if (hapticsOnState) hapticTick(context)
                        onThreatTapped(bestThreat)
                        return@setOnMapClickListener
                    }

                    // 3. Map tap
                    onMapTapped()
                }
                bridge.setOnMapLongClickListener { screenPt, geoPt ->
                    val density = context.resources.displayMetrics.density
                    val threshold = 48f * density
                    var bestThreat: NormalizedThreat? = null
                    var bestDist = threshold
                    for (t in mapThreatsState) {
                        val pose = threatPoses[t.id] ?: MarkerPose(t.lat, t.lon, 0f, ThreatPoseMode.PARKED)
                        val sp = bridge.project(pose.lat, pose.lon) ?: continue
                        val dx = sp.x - screenPt.x
                        val dy = sp.y - screenPt.y
                        val d = sqrt(dx * dx + dy * dy)
                        if (d <= bestDist) {
                            bestThreat = t
                            bestDist = d
                        }
                    }
                    val targetThreat = bestThreat
                    if (targetThreat != null && deathAnimationEnabledState) {
                        val threatId = targetThreat.id
                        val pose = threatPoses[threatId]
                        val strikeLat = pose?.lat ?: geoPt.latitude
                        val strikeLon = pose?.lon ?: geoPt.longitude
                        val threatType = targetThreat.type.toThreatType()
                        val icon = threatIconFor(
                            context, threatType, iconSetState,
                            sizeDp = if (threatIconZoomState) threatIconSizeDp(bridge.zoom) else 32
                        )
                        val base = IconCatalog.baseDeg(threatType, iconSetState)
                        val rotation = if (targetThreat.areaOnly) 0f else threatMarkerRotation(pose?.headingDeg ?: engine.courseDeg(targetThreat).toFloat(), base)
                        val played = if (deathFx.isActiveFor(threatId)) {
                            deathFx.strikeDud(threatId, strikeLat, strikeLon)
                        } else {
                            deathFx.strike(
                                id = threatId,
                                lat = strikeLat,
                                lon = strikeLon,
                                icon = icon,
                                rotationDeg = rotation,
                                alpha = 1f
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
