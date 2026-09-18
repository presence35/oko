package ua.ukrainedrones.ui

import ua.ukrainedrones.theme.AppPalette
import ua.ukrainedrones.community.CompactRaionBoundaries
import ua.ukrainedrones.AlertRegionMode
import ua.ukrainedrones.data.ApiMonitor
import ua.ukrainedrones.data.SystemEntry
import ua.ukrainedrones.data.SystemEntryKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource

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
    private var lastSkipLogged: Boolean = false

    /**
     * Logs a fill-rendering problem both to Logcat and into the in-app Logs screen
     * (Settings → Logs, amber "Fill debug" entries) so it's visible without adb/logcat.
     */
    private fun fillDebugLog(message: String) {
        android.util.Log.w("MapLibreLayerManager", message)
        ApiMonitor.record(
            SystemEntry(
                atMillis = System.currentTimeMillis(),
                kind = SystemEntryKind.FILL_DEBUG,
                detail = "[MapLibreLayerManager] $message"
            )
        )
    }

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

    fun setupLayers(style: Style) {
        // 0. Outside Ukraine mask (dim international geography outside Ukraine's border)
        val srcMask = GeoJsonSource(SOURCE_OUTSIDE_MASK, MapLibreGeoJson.outsideUkraineMask())
        style.addSource(srcMask)
        style.addLayer(
            FillLayer(LAYER_OUTSIDE_MASK, SOURCE_OUTSIDE_MASK).apply {
                setProperties(fillColor(AppPalette.Mask.toInt()))
            }
        )

        // 1. Alert fills and outlines
        val srcAlertYellow = GeoJsonSource(SOURCE_ALERT_YELLOW, MapLibreGeoJson.EMPTY)
        style.addSource(srcAlertYellow)
        style.addLayer(
            FillLayer(LAYER_ALERT_YELLOW, SOURCE_ALERT_YELLOW).apply {
                setProperties(fillColor(AppPalette.YellowFill.toInt()))
            }
        )
        style.addLayer(
            LineLayer(LAYER_ALERT_YELLOW_LINE, SOURCE_ALERT_YELLOW).apply {
                setProperties(
                    lineColor(AppPalette.YellowLine.toInt()),
                    lineWidth(1.2f)
                )
            }
        )

        val srcAlertRed = GeoJsonSource(SOURCE_ALERT_RED, MapLibreGeoJson.EMPTY)
        style.addSource(srcAlertRed)
        style.addLayer(
            FillLayer(LAYER_ALERT_RED_FILL, SOURCE_ALERT_RED).apply {
                setProperties(fillColor(AppPalette.RedFill.toInt()))
            }
        )
        style.addLayer(
            LineLayer(LAYER_ALERT_RED_LINE, SOURCE_ALERT_RED).apply {
                setProperties(
                    lineColor(AppPalette.RedLine.toInt()),
                    lineWidth(1.5f)
                )
            }
        )

        // 2. Static land border (hugs coastline/rivers)
        val srcLandBorder = GeoJsonSource(SOURCE_LAND_BORDER, MapLibreGeoJson.landBorder())
        style.addSource(srcLandBorder)
        style.addLayer(
            LineLayer(LAYER_LAND_BORDER, SOURCE_LAND_BORDER).apply {
                setProperties(
                    lineColor(AppPalette.LandBorder.toInt()),
                    lineWidth(2f)
                )
            }
        )

        // 3. Oblast borders
        val srcOblast = GeoJsonSource(SOURCE_OBLAST_BORDERS, MapLibreGeoJson.oblastBorders())
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

        // 4. Raion borders
        val srcRaion = GeoJsonSource(SOURCE_RAION_BORDERS, MapLibreGeoJson.raionBorders())
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

        // 5. Zone warning circles (Yellow outer, Red inner)
        val srcZoneYellow = GeoJsonSource(SOURCE_ZONE_YELLOW, MapLibreGeoJson.EMPTY)
        style.addSource(srcZoneYellow)
        style.addLayer(
            LineLayer(LAYER_ZONE_YELLOW, SOURCE_ZONE_YELLOW).apply {
                setProperties(
                    lineColor(AppPalette.ZoneYellow.toInt()),
                    lineWidth(2.2f)
                )
            }
        )

        val srcZoneRed = GeoJsonSource(SOURCE_ZONE_RED, MapLibreGeoJson.EMPTY)
        style.addSource(srcZoneRed)
        style.addLayer(
            LineLayer(LAYER_ZONE_RED, SOURCE_ZONE_RED).apply {
                setProperties(
                    lineColor(AppPalette.ZoneRed.toInt()),
                    lineWidth(2.5f)
                )
            }
        )
    }

    fun updateBordersVisibility(style: Style, showBorders: Boolean, showRegionBorders: Boolean) {
        val landVis = if (!showBorders) Property.VISIBLE else Property.NONE
        val oblastVis = if (showBorders) Property.VISIBLE else Property.NONE
        val raionVis = if (showBorders && showRegionBorders) Property.VISIBLE else Property.NONE

        style.getLayer(LAYER_LAND_BORDER)?.setProperties(visibility(landVis))
        style.getLayer(LAYER_OBLAST_BORDERS)?.setProperties(visibility(oblastVis))
        style.getLayer(LAYER_RAION_BORDERS)?.setProperties(visibility(raionVis))
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
            lastSkipLogged = false
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
                fillDebugLog("updateAlertRegions: alert source(s) missing on style while clearing (red=$redSrc yellow=$yellowSrc) — not caching this as applied")
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
            if (!lastSkipLogged) {
                lastSkipLogged = true
                fillDebugLog(
                    "updateAlertRegions: skipped, no change since last apply " +
                        "(mode=$alertRegionMode redObl=${redOblastIds.size} redRaion=${redRaions.size} " +
                        "yellowObl=${yellowOblastIds.size} yellowRaion=${yellowRaions.size})"
                )
            }
            return
        }
        lastSkipLogged = false

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
            fillDebugLog("updateAlertRegions: alert source(s) missing on style (red=$redSrc yellow=$yellowSrc) — not caching this as applied")
            return
        }
        val redGeoJson = MapLibreGeoJson.alertRegions(redOblastIds, redRaions)
        val yellowGeoJson = MapLibreGeoJson.alertRegions(filteredYellowOblastIds, filteredYellowRaions)
        redSrc.setGeoJson(redGeoJson)
        yellowSrc.setGeoJson(yellowGeoJson)

        val fillVisible = alertRegionMode == AlertRegionMode.FILL
        style.getLayer(LAYER_ALERT_YELLOW)?.setProperties(visibility(if (fillVisible) Property.VISIBLE else Property.NONE))
        style.getLayer(LAYER_ALERT_RED_FILL)?.setProperties(visibility(if (fillVisible) Property.VISIBLE else Property.NONE))

        fun featureCount(geoJson: String) = Regex("\"type\":\"Feature\"").findAll(geoJson).count()
        fun bounds(geoJson: String): String {
            val coordPairRegex = Regex("""\[(-?\d+\.?\d*),(-?\d+\.?\d*)\]""")
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var n = 0
            for (m in coordPairRegex.findAll(geoJson)) {
                val lon = m.groupValues[1].toDoubleOrNull() ?: continue
                val lat = m.groupValues[2].toDoubleOrNull() ?: continue
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                n++
            }
            return if (n == 0) "no coordinates found"
            else "lat[$minLat..$maxLat] lon[$minLon..$maxLon] (n=$n points)"
        }
        fillDebugLog(
            "updateAlertRegions: applied mode=$alertRegionMode redObl=${redOblastIds.size} redRaion=${redRaions.size} " +
                "yellowObl=${filteredYellowOblastIds.size} yellowRaion=${filteredYellowRaions.size} " +
                "redFeatures=${featureCount(redGeoJson)} yellowFeatures=${featureCount(yellowGeoJson)}"
        )
        fillDebugLog("updateAlertRegions: redBounds=${bounds(redGeoJson)} yellowBounds=${bounds(yellowGeoJson)}")
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