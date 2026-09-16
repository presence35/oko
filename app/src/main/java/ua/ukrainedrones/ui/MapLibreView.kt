package ua.ukrainedrones.ui

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.cos
import kotlin.math.pow

/**
 * State and bridge holder for MapLibre map controller, GPU vector layers, and coordinate projection.
 */
class MapLibreBridge(
    var map: MapLibreMap? = null,
    var mapView: MapView? = null,
    var style: Style? = null,
    var project: ((Double, Double) -> PointF?)? = null
) {
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

    fun updateBorders(showBorders: Boolean, showRegionBorders: Boolean) {
        val s = style ?: return
        MapLibreLayerManager.updateBordersVisibility(s, showBorders, showRegionBorders)
    }

    fun updateAlerts(
        fillAlertRegions: Boolean,
        redOblasts: Set<String>,
        redRaions: Set<Pair<String, String>>,
        yellowOblasts: Set<String>,
        yellowRaions: Set<Pair<String, String>>
    ) {
        val s = style ?: return
        MapLibreLayerManager.updateAlertRegions(
            s, fillAlertRegions, redOblasts, redRaions, yellowOblasts, yellowRaions
        )
    }

    fun updateAlerts(
        oblastStems: Set<String>,
        raionKeys: Set<Pair<String, String>>,
        yellowOblastStems: Set<String>,
        yellowRaionKeys: Set<Pair<String, String>>,
        fillEnabled: Boolean
    ) {
        updateAlerts(fillEnabled, oblastStems, raionKeys, yellowOblastStems, yellowRaionKeys)
    }

    fun updateZones(
        centerLat: Double?,
        centerLon: Double?,
        slowRedKm: Double,
        slowYellowKm: Double
    ) {
        val s = style ?: return
        MapLibreLayerManager.updateZoneCircles(s, centerLat, centerLon, slowRedKm, slowYellowKm)
    }
}

/**
 * Encapsulates the MapLibre Native MapView, Carto dark tiles, GeoJSON boundary layers,
 * and Jetpack Compose lifecycle bindings.
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
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }
    val bridge = remember { MapLibreBridge() }

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
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                bridge.mapView = this
                getMapAsync { mapLibreMap ->
                    bridge.map = mapLibreMap
                    mapLibreMap.uiSettings.apply {
                        isAttributionEnabled = false
                        isLogoEnabled = false
                        isCompassEnabled = false
                        isRotateGesturesEnabled = false
                    }
                    mapLibreMap.setMinZoomPreference(5.2)
                    mapLibreMap.setMaxZoomPreference(19.0)
                    val ukraineBounds = LatLngBounds.Builder()
                        .include(LatLng(ua.ukrainedrones.UA_TIGHT_MAX_LAT, ua.ukrainedrones.UA_TIGHT_MAX_LON))
                        .include(LatLng(ua.ukrainedrones.UA_TIGHT_MIN_LAT, ua.ukrainedrones.UA_TIGHT_MIN_LON))
                        .build()
                    mapLibreMap.setLatLngBoundsForCameraBounds(ukraineBounds)

                    val initialPos = CameraPosition.Builder()
                        .target(LatLng(initialCenterLat, initialCenterLon))
                        .zoom(initialZoom)
                        .build()
                    mapLibreMap.cameraPosition = initialPos

                    mapLibreMap.setStyle(Style.Builder().fromJson(MapLibreStyle.cartoDarkJson())) { loadedStyle ->
                        bridge.style = loadedStyle
                        MapLibreLayerManager.setupLayers(loadedStyle)

                        val projLambda: (Double, Double) -> PointF? = { lat, lon ->
                            val pt = mapLibreMap.projection.toScreenLocation(LatLng(lat, lon))
                            PointF(pt.x, pt.y)
                        }
                        bridge.project = projLambda

                        mapLibreMap.addOnCameraMoveListener {
                            onCameraChange()
                            bridge.dispatchCameraMove()
                        }
                        mapLibreMap.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                onCameraChange()
                                bridge.dispatchCameraMove()
                            }
                            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                onCameraChange()
                                bridge.dispatchCameraMove()
                            }
                            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                                onCameraChange()
                                bridge.dispatchCameraMove()
                            }
                        })
                        mapLibreMap.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                            override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                                onCameraChange()
                                bridge.dispatchCameraMove()
                            }
                            override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                                onCameraChange()
                                bridge.dispatchCameraMove()
                            }
                            override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                                onCameraChange()
                                bridge.dispatchCameraMove()
                            }
                        })
                        mapLibreMap.addOnCameraIdleListener {
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
                    }
                }
            }
        },
        modifier = modifier
    )
}
