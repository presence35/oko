package ua.ukrainedrones.ui

import ua.ukrainedrones.AlertRegionMode
import ua.ukrainedrones.theme.AppPalette
import ua.ukrainedrones.community.CompactRaionBoundaries
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages MapLibre GeoJSON sources and GPU layers for borders, alert polygons,
 * and focus zone warning circles.
 */
object MapLibreLayerManager {

    private var lastStyle: Style? = null
    private var lastAlertRegionMode: AlertRegionMode? = null
    private var lastRedOblastIds: Set<String>? = null
    private var lastRedRaions: Set<Pair<String, String>>? = null
    private var lastYellowOblastIds: Set<String>? = null
    private var lastYellowRaions: Set<Pair<String, String>>? = null

    private var lastCenterLat: Double? = null
    private var lastCenterLon: Double? = null
    private var lastSlowRedKm: Double? = null
    private var lastSlowYellowKm: Double? = null

    const val SOURCE_OUTSIDE_MASK = "src_outside_mask"
    const val LAYER_OUTSIDE_MASK = "lyr_outside_mask"

    const val SOURCE_LAND_BORDER = "src_land_border"
    const val LAYER_LAND_BORDER = "lyr_land_border"

    const val SOURCE_OBLAST_BORDERS = "src_oblast_borders"
    const val LAYER_OBLAST_BORDERS = "lyr_oblast_borders"

    const val SOURCE_RAION_BORDERS = "src_raion_borders"
    const val LAYER_RAION_BORDERS = "lyr_raion_borders"

    const val SOURCE_ALERT_YELLOW = "src_alert_yellow"
    const val LAYER_ALERT_YELLOW = "lyr_alert_yellow"
    const val LAYER_ALERT_YELLOW_LINE = "lyr_alert_yellow_line"

    const val SOURCE_ALERT_RED = "src_alert_red"
    const val LAYER_ALERT_RED_FILL = "lyr_alert_red_fill"
    const val LAYER_ALERT_RED_LINE = "lyr_alert_red_line"

    const val SOURCE_ZONE_RED = "src_zone_red"
    const val LAYER_ZONE_RED = "lyr_zone_red"

    const val SOURCE_ZONE_YELLOW = "src_zone_yellow"
    const val LAYER_ZONE_YELLOW = "lyr_zone_yellow"

    /** Prebuilt static border geometry: immutable runtime constants, safe to build off-main. */
    data class StaticLayersData(
        val outsideMask: String,
        val landBorder: String,
        val oblastBorders: String,
        val raionBorders: String
    )

    @Volatile
    private var staticCache: StaticLayersData? = null

    /**
     * Builds the static border GeoJSON off the main thread. Pure CPU work —
     * tens of thousands of coordinate strings — that used to run synchronously
     * inside the setStyle callback and starve first-frame rendering (white cold start).
     */
    fun buildStaticGeoJson(): StaticLayersData {
        staticCache?.let { return it }
        return StaticLayersData(
            outsideMask = MapLibreGeoJson.outsideUkraineMask(),
            landBorder = MapLibreGeoJson.landBorder(),
            oblastBorders = MapLibreGeoJson.oblastBorders(),
            raionBorders = MapLibreGeoJson.raionBorders()
        ).also { staticCache = it }
    }

    /**
     * Attaches all sources/layers to the style. Main-thread only (native map calls).
     * Alert and zone sources start empty; content arrives via updateAlertRegions/updateZoneCircles.
     */
    fun addStaticLayers(style: Style, data: StaticLayersData) {
        // 0. Outside Ukraine mask — computed from UKRAINE_BORDER, gives pure blackness outside with no tile download beyond bounds
        val srcMask = GeoJsonSource(SOURCE_OUTSIDE_MASK, data.outsideMask)
        style.addSource(srcMask)
        style.addLayer(FillLayer(LAYER_OUTSIDE_MASK, SOURCE_OUTSIDE_MASK).apply { setProperties(fillColor(AppPalette.Mask.toInt())) })

        // 1. Ukraine country border — not drawn (we keep the border knowledge for masking only, no line)
        // land border intentionally not added as a layer

        // 2. Alert fills — translucent (45%/35%), below white borders so white shows on top in FILL and land details show through
        val srcAlertYellow = GeoJsonSource(SOURCE_ALERT_YELLOW, MapLibreGeoJson.EMPTY)
        style.addSource(srcAlertYellow)
        style.addLayer(
            FillLayer(LAYER_ALERT_YELLOW, SOURCE_ALERT_YELLOW).apply {
                setProperties(fillColor(AppPalette.YellowFill.toInt()))
            }
        )
        val srcAlertRed = GeoJsonSource(SOURCE_ALERT_RED, MapLibreGeoJson.EMPTY)
        style.addSource(srcAlertRed)
        style.addLayer(
            FillLayer(LAYER_ALERT_RED_FILL, SOURCE_ALERT_RED).apply {
                setProperties(fillColor(AppPalette.RedFill.toInt()))
            }
        )

        // 3. Static admin borders — white, below alert strokes so active threat overrides them
        val srcOblast = GeoJsonSource(SOURCE_OBLAST_BORDERS, data.oblastBorders)
        style.addSource(srcOblast)
        style.addLayer(
            LineLayer(LAYER_OBLAST_BORDERS, SOURCE_OBLAST_BORDERS).apply {
                setProperties(
                    lineColor(AppPalette.OblastBorder.toInt()),
                    lineWidth(1.5f),
                    visibility(Property.NONE)
                )
            }
        )
        val srcRaion = GeoJsonSource(SOURCE_RAION_BORDERS, data.raionBorders)
        style.addSource(srcRaion)
        style.addLayer(
            LineLayer(LAYER_RAION_BORDERS, SOURCE_RAION_BORDERS).apply {
                setProperties(
                    lineColor(AppPalette.RaionBorder.toInt()),
                    lineWidth(1f),
                    visibility(Property.NONE)
                )
            }
        )

        // 4. Alert strokes — colored, on top of static borders so active threat boundaries always override admin outlines
        style.addLayer(
            LineLayer(LAYER_ALERT_YELLOW_LINE, SOURCE_ALERT_YELLOW).apply {
                setProperties(
                    lineColor(AppPalette.YellowLine.toInt()),
                    lineWidth(1.2f),
                    visibility(Property.NONE)
                )
            }
        )
        style.addLayer(
            LineLayer(LAYER_ALERT_RED_LINE, SOURCE_ALERT_RED).apply {
                setProperties(
                    lineColor(AppPalette.RedLine.toInt()),
                    lineWidth(1.5f),
                    visibility(Property.NONE)
                )
            }
        )

        // 5. Zone warning circles (Yellow outer, Red inner)
        val srcZoneYellow = GeoJsonSource(SOURCE_ZONE_YELLOW, MapLibreGeoJson.EMPTY)
        style.addSource(srcZoneYellow)
        style.addLayer(
            LineLayer(LAYER_ZONE_YELLOW, SOURCE_ZONE_YELLOW).apply {
                setProperties(
                    lineColor(AppPalette.AlertYellow.toInt()),
                    lineWidth(1.5f)
                )
            }
        )

        val srcZoneRed = GeoJsonSource(SOURCE_ZONE_RED, MapLibreGeoJson.EMPTY)
        style.addSource(srcZoneRed)
        style.addLayer(
            LineLayer(LAYER_ZONE_RED, SOURCE_ZONE_RED).apply {
                setProperties(
                    lineColor(AppPalette.AlertRed.toInt()),
                    lineWidth(1.5f)
                )
            }
        )
    }

    /**
     * Full layer setup safe to call from the setStyle callback: geometry builds on
     * Default so first-frame rendering isn't starved, native attach stays on Main.
     * Returns false when the style died mid-build (caller retries on next style load).
     */
    suspend fun setupLayersAsync(style: Style): Boolean {
        val data = withContext(Dispatchers.Default) { buildStaticGeoJson() }
        return try {
            addStaticLayers(style, data)
            true
        } catch (e: Exception) {
            android.util.Log.w("MapLibreLayerManager", "addStaticLayers failed, will retry on next style", e)
            false
        }
    }

    fun updateBordersVisibility(style: Style, showBorders: Boolean, showRegionBorders: Boolean, alertRegionMode: AlertRegionMode) {
        val oblastVis = if (showBorders) Property.VISIBLE else Property.NONE
        val raionVis = if (showBorders && showRegionBorders) Property.VISIBLE else Property.NONE
        val oblastBorderLayer = style.getLayer(LAYER_OBLAST_BORDERS)
        oblastBorderLayer?.setProperties(
            visibility(oblastVis),
            lineColor(
                if (alertRegionMode == AlertRegionMode.FILL) 0x66FFFFFF.toInt() else AppPalette.OblastBorder.toInt()
            ),
            lineWidth(1.2f)
        )
        val raionBorderLayer = style.getLayer(LAYER_RAION_BORDERS)
        raionBorderLayer?.setProperties(
            visibility(raionVis),
            lineColor(
                if (alertRegionMode == AlertRegionMode.FILL) 0x99FFFFFF.toInt() else AppPalette.RaionBorder.toInt()
            ),
            lineWidth(1f)
        )
    }

    fun updateAlertRegions(
        style: Style,
        alertRegionMode: AlertRegionMode,
        redOblastIds: Set<String>,
        redRaions: Set<Pair<String, String>>,
        yellowOblastIds: Set<String>,
        yellowRaions: Set<Pair<String, String>>
    ) {
        if (lastStyle !== style) {
            lastStyle = style
            lastAlertRegionMode = null
            lastRedOblastIds = null
            lastRedRaions = null
            lastYellowOblastIds = null
            lastYellowRaions = null
            lastCenterLat = null
            lastCenterLon = null
            lastSlowRedKm = null
            lastSlowYellowKm = null
        }

        if (alertRegionMode == AlertRegionMode.CITY_LABELS) {
            if (lastAlertRegionMode == AlertRegionMode.CITY_LABELS) return
            val redSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ALERT_RED)
            val yellowSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ALERT_YELLOW)
            if (redSrc == null || yellowSrc == null) {
                return
            }
            redSrc.setGeoJson(MapLibreGeoJson.EMPTY)
            yellowSrc.setGeoJson(MapLibreGeoJson.EMPTY)
            lastAlertRegionMode = AlertRegionMode.CITY_LABELS
            lastRedOblastIds = null
            lastRedRaions = null
            lastYellowOblastIds = null
            lastYellowRaions = null
            return
        }

        if (alertRegionMode == lastAlertRegionMode &&
            redOblastIds == lastRedOblastIds &&
            redRaions == lastRedRaions &&
            yellowOblastIds == lastYellowOblastIds &&
            yellowRaions == lastYellowRaions
        ) {
            return
        }

        val dataUnchanged = redOblastIds == lastRedOblastIds &&
            redRaions == lastRedRaions &&
            yellowOblastIds == lastYellowOblastIds &&
            yellowRaions == lastYellowRaions
        if (dataUnchanged && alertRegionMode != AlertRegionMode.CITY_LABELS &&
            lastAlertRegionMode != AlertRegionMode.CITY_LABELS && lastAlertRegionMode != null
        ) {
            // Same data, FILL<->BORDER switch: flip fill vs line visibility — FILL white, BORDER colored.
            val fillVisible = alertRegionMode == AlertRegionMode.FILL
            val lineVisible = alertRegionMode == AlertRegionMode.BORDER
            style.getLayer(LAYER_ALERT_YELLOW)?.setProperties(visibility(if (fillVisible) Property.VISIBLE else Property.NONE))
            style.getLayer(LAYER_ALERT_RED_FILL)?.setProperties(visibility(if (fillVisible) Property.VISIBLE else Property.NONE))
            style.getLayer(LAYER_ALERT_YELLOW_LINE)?.setProperties(visibility(if (lineVisible) Property.VISIBLE else Property.NONE))
            style.getLayer(LAYER_ALERT_RED_LINE)?.setProperties(visibility(if (lineVisible) Property.VISIBLE else Property.NONE))
            lastAlertRegionMode = alertRegionMode
            return
        }

        val filteredYellowOblastIds = yellowOblastIds - redOblastIds
        val redCanonicalRaions = redRaions.mapNotNull { (id, raion) ->
            CompactRaionBoundaries.canonicalKey(raion)?.let { id to it }
        }.toSet()
        val filteredYellowRaions = yellowRaions.filter { (id, raion) ->
            if (id in redOblastIds || (id to raion) in redRaions) return@filter false
            val canonical = CompactRaionBoundaries.canonicalKey(raion) ?: raion
            (id to canonical) !in redCanonicalRaions
        }.toSet()

        val redSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ALERT_RED)
        val yellowSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ALERT_YELLOW)
        if (redSrc == null || yellowSrc == null) {
            return
        }
        val redGeoJson = MapLibreGeoJson.alertRegions(redOblastIds, redRaions)
        val yellowGeoJson = MapLibreGeoJson.alertRegions(filteredYellowOblastIds, filteredYellowRaions)
        redSrc.setGeoJson(redGeoJson)
        yellowSrc.setGeoJson(yellowGeoJson)

        val fillVisible = alertRegionMode == AlertRegionMode.FILL
        val lineVisible = alertRegionMode == AlertRegionMode.BORDER
        style.getLayer(LAYER_ALERT_YELLOW)?.setProperties(visibility(if (fillVisible) Property.VISIBLE else Property.NONE))
        style.getLayer(LAYER_ALERT_RED_FILL)?.setProperties(visibility(if (fillVisible) Property.VISIBLE else Property.NONE))
        style.getLayer(LAYER_ALERT_YELLOW_LINE)?.setProperties(visibility(if (lineVisible) Property.VISIBLE else Property.NONE))
        style.getLayer(LAYER_ALERT_RED_LINE)?.setProperties(visibility(if (lineVisible) Property.VISIBLE else Property.NONE))

        lastAlertRegionMode = alertRegionMode
        lastRedOblastIds = redOblastIds
        lastRedRaions = redRaions
        lastYellowOblastIds = yellowOblastIds
        lastYellowRaions = yellowRaions
    }

    fun updateZoneCircles(
        style: Style,
        centerLat: Double?,
        centerLon: Double?,
        slowRedKm: Double,
        slowYellowKm: Double
    ) {
        if (lastStyle !== style) {
            lastStyle = style
            lastAlertRegionMode = null
            lastRedOblastIds = null
            lastRedRaions = null
            lastYellowOblastIds = null
            lastYellowRaions = null
            lastCenterLat = null
            lastCenterLon = null
            lastSlowRedKm = null
            lastSlowYellowKm = null
        }

        if (centerLat == lastCenterLat &&
            centerLon == lastCenterLon &&
            slowRedKm == lastSlowRedKm &&
            slowYellowKm == lastSlowYellowKm
        ) {
            return
        }
        lastCenterLat = centerLat
        lastCenterLon = centerLon
        lastSlowRedKm = slowRedKm
        lastSlowYellowKm = slowYellowKm

        val redSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ZONE_RED)
        val yellowSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ZONE_YELLOW)
        redSrc?.setGeoJson(MapLibreGeoJson.singleCircle(centerLat, centerLon, slowRedKm))
        yellowSrc?.setGeoJson(MapLibreGeoJson.singleCircle(centerLat, centerLon, slowYellowKm))
    }

}