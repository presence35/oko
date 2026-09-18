package ua.ukrainedrones.ui

import ua.ukrainedrones.AlertRegionMode
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import ua.ukrainedrones.UA_PAN_MAX_LAT
import ua.ukrainedrones.UA_PAN_MAX_LON
import ua.ukrainedrones.UA_PAN_MIN_LAT
import ua.ukrainedrones.UA_PAN_MIN_LON
import ua.ukrainedrones.theme.AppPalette
import kotlin.math.cos
import kotlin.math.pow

/**
 * State and bridge holder for MapLibre map controller, GPU vector layers, and coordinate projection.
 */
class MapLibreBridge(
    var map: MapLibreMap? = null,
    var mapView: MapView? = null,
    var overlayView: View? = null,
    var style: Style? = null,
    var project: ((Double, Double) -> PointF?)? = null,
    var onDrawOverlay: ((android.graphics.Canvas) -> Unit)? = null
) {
    /** True once layer sources exist; MapView effects wait on this before touching the style. */
    val layersReady = androidx.compose.runtime.mutableStateOf(false)
    val width: Int get() = mapView?.width ?: 0
    val height: Int get() = mapView?.height ?: 0
    val zoom: Double get() = map?.cameraPosition?.zoom ?: 0.0
    val centerLat: Double get() = map?.cameraPosition?.target?.latitude ?: 0.0
    val centerLon: Double get() = map?.cameraPosition?.target?.longitude ?: 0.0
    val latitude: Double get() = centerLat
    val longitude: Double get() = centerLon

    private var onCameraMoveCallback: (() -> Unit)? = null
    private var onMapClickCallback: ((PointF, LatLng) -> Unit)? = null
    private var onMapLongClickCallback: ((PointF, LatLng) -> Unit)? = null

    fun invalidateOverlay() {
        overlayView?.invalidate()
    }

    fun invalidateOverlayNextFrame() {
        overlayView?.postInvalidateOnAnimation()
    }

    fun setOnCameraMoveListener(listener: () -> Unit) {
        onCameraMoveCallback = listener
    }

    fun setOnMapClickListener(listener: (PointF, LatLng) -> Unit) {
        onMapClickCallback = listener
    }

    fun setOnMapLongClickListener(listener: (PointF, LatLng) -> Unit) {
        onMapLongClickCallback = listener
    }

    internal fun dispatchCameraMove() {
        onCameraMoveCallback?.invoke()
    }

    internal fun dispatchMapClick(screenPt: PointF, geoPt: LatLng) {
        onMapClickCallback?.invoke(screenPt, geoPt)
    }

    internal fun dispatchMapLongClick(screenPt: PointF, geoPt: LatLng) {
        onMapLongClickCallback?.invoke(screenPt, geoPt)
    }

    fun metersPerPixel(): Double {
        return 156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom)
    }

    fun setMaxZoom(maxZoom: Double) {
        map?.setMaxZoomPreference(maxZoom)
    }

    fun project(lat: Double, lon: Double): PointF? {
        val m = map ?: return null
        val pt = m.projection.toScreenLocation(LatLng(lat, lon))
        return PointF(pt.x, pt.y)
    }

    fun fromPixels(x: Float, y: Float): ua.ukrainedrones.engine.LatLng? {
        val m = map ?: return null
        val pt = m.projection.fromScreenLocation(PointF(x, y)) ?: return null
        return ua.ukrainedrones.engine.LatLng(pt.latitude, pt.longitude)
    }

    fun animateTo(lat: Double, lon: Double, zoom: Double? = null, durationMs: Int = 400) {
        val m = map ?: return
        val posBuilder = CameraPosition.Builder().target(LatLng(lat, lon))
        if (zoom != null) posBuilder.zoom(zoom)
        val targetPos = posBuilder.build()
        if (durationMs <= 0) {
            m.cameraPosition = targetPos
        } else {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(targetPos), durationMs)
        }
    }

    fun zoomToBounds(
        north: Double,
        east: Double,
        south: Double,
        west: Double,
        paddingPx: Int = 60,
        durationMs: Int = 400
    ) {
        val m = map ?: return
        val bounds = LatLngBounds.Builder()
            .include(LatLng(north, east))
            .include(LatLng(south, west))
            .build()
        val update = CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        if (durationMs <= 0) {
            m.moveCamera(update)
        } else {
            m.animateCamera(update, durationMs)
        }
    }

    fun updateBorders(showBorders: Boolean, showRegionBorders: Boolean, alertRegionMode: AlertRegionMode) {
        val s = style ?: return
        MapLibreLayerManager.updateBordersVisibility(s, showBorders, showRegionBorders, alertRegionMode)
        mapView?.invalidate()
        overlayView?.invalidate()
    }

    fun updateAlerts(
        alertRegionMode: AlertRegionMode,
        redOblastIds: Set<String>,
        redRaions: Set<Pair<String, String>>,
        yellowOblastIds: Set<String>,
        yellowRaions: Set<Pair<String, String>>
    ) {
        val s = style ?: return
        MapLibreLayerManager.updateAlertRegions(
            s, alertRegionMode, redOblastIds, redRaions, yellowOblastIds, yellowRaions
        )
        mapView?.invalidate()
        overlayView?.invalidate()
    }

    fun updateAlerts(
        oblastIds: Set<String>,
        raionKeys: Set<Pair<String, String>>,
        yellowOblastIds: Set<String>,
        yellowRaionKeys: Set<Pair<String, String>>,
        alertRegionMode: AlertRegionMode
    ) {
        updateAlerts(alertRegionMode, oblastIds, raionKeys, yellowOblastIds, yellowRaionKeys)
    }

    fun updateZones(
        centerLat: Double?,
        centerLon: Double?,
        slowRedKm: Double,
        slowYellowKm: Double
    ) {
        val s = style ?: return
        MapLibreLayerManager.updateZoneCircles(s, centerLat, centerLon, slowRedKm, slowYellowKm)
        mapView?.invalidate()
        overlayView?.invalidate()
    }
}

/**
 * Encapsulates the MapLibre Native MapView, Carto dark tiles, GeoJSON boundary layers,
 * synchronized direct overlay view, and Jetpack Compose lifecycle bindings.
 */
@Composable
fun MapLibreHostView(
    modifier: Modifier = Modifier,
    initialCenterLat: Double = 46.4825,
    initialCenterLon: Double = 30.7233,
    initialZoom: Double = 8.0,
    onBridgeReady: (MapLibreBridge) -> Unit = {},
    onMapClick: (LatLng) -> Unit = {},
    onMapLongClick: (LatLng) -> Unit = {},
    onCameraChange: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bridge = remember { MapLibreBridge() }
    val scope = rememberCoroutineScope()

    val overlayView = remember {
        object : View(context) {
            init {
                setWillNotDraw(false)
                isClickable = false
                isFocusable = false
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                bridge.onDrawOverlay?.invoke(canvas)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }
    val container = remember {
        object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val handled = super.dispatchTouchEvent(ev)
                if (ev.actionMasked != MotionEvent.ACTION_UP &&
                    ev.actionMasked != MotionEvent.ACTION_CANCEL
                ) {
                    overlayView.invalidate()
                }
                return handled
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Zero-cost backdrop during initial layout passes before children attach.
            setBackgroundColor(AppPalette.MapBackground.toInt())
        }
    }
    val curtainView = remember {
        View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            // Matches Carto dark canvas so cold start transitions seamlessly.
            setBackgroundColor(AppPalette.MapBackground.toInt())
        }
    }
    val curtainCleanup = remember { object { var fn: (() -> Unit)? = null } }
    val mapView = remember {
        val opts = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        MapView(context, opts).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Black until the dark style paints its first frame — the GL surface
            // defaults to white and style load takes ~1s on cold start.
            setBackgroundColor(android.graphics.Color.BLACK)
            onCreate(null)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            curtainCleanup.fn?.invoke()
        }
    }

    AndroidView(
        factory = {
            container.apply {
                removeAllViews()
                addView(mapView)
                addView(overlayView)
                // Covers TextureView until the native GL engine finishes painting dark tiles.
                addView(curtainView)
                bridge.mapView = mapView
                bridge.overlayView = overlayView

                mapView.getMapAsync { mapLibreMap ->
                    bridge.map = mapLibreMap
                    mapLibreMap.uiSettings.apply {
                        isAttributionEnabled = false
                        isLogoEnabled = false
                        isCompassEnabled = false
                        isRotateGesturesEnabled = false
                    }
                    mapLibreMap.setMinZoomPreference(3.6)
                    mapLibreMap.setMaxZoomPreference(19.0)
                    val ukrainePanBounds = LatLngBounds.Builder()
                        .include(LatLng(UA_PAN_MAX_LAT, UA_PAN_MAX_LON))
                        .include(LatLng(UA_PAN_MIN_LAT, UA_PAN_MIN_LON))
                        .build()
                    mapLibreMap.setLatLngBoundsForCameraTarget(ukrainePanBounds)

                    val initialPos = CameraPosition.Builder()
                        .target(LatLng(initialCenterLat, initialCenterLon))
                        .zoom(initialZoom)
                        .build()
                    mapLibreMap.cameraPosition = initialPos

                    mapLibreMap.setStyle(Style.Builder().fromJson(MapLibreStyle.cartoDarkJson())) { loadedStyle ->
                        bridge.style = loadedStyle
                        // Layer sources attach asynchronously so the dark first frame
                        // isn't starved by border-GeoJSON string building on Main.
                        bridge.layersReady.value = false
                        scope.launch {
                            if (MapLibreLayerManager.setupLayersAsync(loadedStyle)) {
                                bridge.layersReady.value = true
                            }
                        }

                        // TextureView paints its dark frame asynchronously after style compilation;
                        // lifting the curtain only after the frame finishes prevents any white clear-color leak.
                        var dismissed = false
                        fun dismissCurtain() {
                            if (dismissed) return
                            dismissed = true
                            curtainView.animate()
                                .alpha(0f)
                                .setDuration(180)
                                .withEndAction { container.removeView(curtainView) }
                                .start()
                        }
                        val frameListener = object : MapView.OnDidFinishRenderingFrameListener {
                            override fun onDidFinishRenderingFrame(fully: Boolean, t1: Double, t2: Double) {
                                mapView.removeOnDidFinishRenderingFrameListener(this)
                                dismissCurtain()
                            }
                        }
                        mapView.addOnDidFinishRenderingFrameListener(frameListener)
                        val timeoutRunnable = Runnable {
                            mapView.removeOnDidFinishRenderingFrameListener(frameListener)
                            dismissCurtain()
                        }
                        // Prevents a permanently obscured map if GL rendering stalls or device locks.
                        curtainView.postDelayed(timeoutRunnable, 2000)
                        curtainCleanup.fn = {
                            curtainView.removeCallbacks(timeoutRunnable)
                            mapView.removeOnDidFinishRenderingFrameListener(frameListener)
                        }

                        val projLambda: (Double, Double) -> PointF? = { lat, lon ->
                            val pt = mapLibreMap.projection.toScreenLocation(LatLng(lat, lon))
                            PointF(pt.x, pt.y)
                        }
                        bridge.project = projLambda

                        mapLibreMap.addOnCameraMoveListener {
                            overlayView.invalidate()
                            bridge.dispatchCameraMove()
                        }
                        mapLibreMap.addOnCameraIdleListener {
                            overlayView.invalidate()
                            onCameraChange()
                            bridge.dispatchCameraMove()
                        }
                        mapLibreMap.addOnMapClickListener { latLng ->
                            val pt = mapLibreMap.projection.toScreenLocation(latLng)
                            onMapClick(latLng)
                            bridge.dispatchMapClick(PointF(pt.x, pt.y), latLng)
                            true
                        }
                        mapLibreMap.addOnMapLongClickListener { latLng ->
                            val pt = mapLibreMap.projection.toScreenLocation(latLng)
                            onMapLongClick(latLng)
                            bridge.dispatchMapLongClick(PointF(pt.x, pt.y), latLng)
                            true
                        }
                        onBridgeReady(bridge)
                        overlayView.postInvalidateOnAnimation()
                    }
                }
            }
        },
        modifier = modifier
    )
}
