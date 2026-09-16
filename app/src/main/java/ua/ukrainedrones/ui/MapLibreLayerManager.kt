package ua.ukrainedrones.ui

import android.graphics.Color
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

    const val SOURCE_LAND_BORDER = "src_land_border"
    const val LAYER_LAND_BORDER = "lyr_land_border"

    const val SOURCE_OBLAST_BORDERS = "src_oblast_borders"
    const val LAYER_OBLAST_BORDERS = "lyr_oblast_borders"

    const val SOURCE_RAION_BORDERS = "src_raion_borders"
    const val LAYER_RAION_BORDERS = "lyr_raion_borders"

    const val SOURCE_ALERT_YELLOW = "src_alert_yellow"
    const val LAYER_ALERT_YELLOW = "lyr_alert_yellow"

    const val SOURCE_ALERT_RED = "src_alert_red"
    const val LAYER_ALERT_RED_FILL = "lyr_alert_red_fill"
    const val LAYER_ALERT_RED_LINE = "lyr_alert_red_line"

    const val SOURCE_ZONE_CIRCLES = "src_zone_circles"
    const val LAYER_ZONE_CIRCLES = "lyr_zone_circles"

    fun setupLayers(style: Style) {
        // 1. Alert fills (underneath outlines)
        val srcAlertYellow = GeoJsonSource(SOURCE_ALERT_YELLOW, MapLibreGeoJson.EMPTY)
        style.addSource(srcAlertYellow)
        style.addLayer(
            FillLayer(LAYER_ALERT_YELLOW, SOURCE_ALERT_YELLOW).apply {
                setProperties(fillColor(Color.argb(38, 255, 213, 0)))
            }
        )

        val srcAlertRed = GeoJsonSource(SOURCE_ALERT_RED, MapLibreGeoJson.EMPTY)
        style.addSource(srcAlertRed)
        style.addLayer(
            FillLayer(LAYER_ALERT_RED_FILL, SOURCE_ALERT_RED).apply {
                setProperties(fillColor(Color.argb(55, 255, 60, 60)))
            }
        )
        style.addLayer(
            LineLayer(LAYER_ALERT_RED_LINE, SOURCE_ALERT_RED).apply {
                setProperties(
                    lineColor(Color.argb(120, 255, 60, 60)),
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
                    lineColor(Color.argb(70, 255, 255, 255)),
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
                    lineColor(Color.argb(120, 180, 180, 200)),
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
                    lineColor(Color.argb(70, 180, 180, 200)),
                    lineWidth(1f),
                    visibility(Property.NONE)
                )
            }
        )

        // 5. Zone warning circles
        val srcZones = GeoJsonSource(SOURCE_ZONE_CIRCLES, MapLibreGeoJson.EMPTY)
        style.addSource(srcZones)
        style.addLayer(
            LineLayer(LAYER_ZONE_CIRCLES, SOURCE_ZONE_CIRCLES).apply {
                setProperties(
                    lineColor(Color.argb(180, 255, 213, 0)),
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
        fillAlertRegions: Boolean,
        redOblasts: Set<String>,
        redRaions: Set<Pair<String, String>>,
        yellowOblasts: Set<String>,
        yellowRaions: Set<Pair<String, String>>
    ) {
        val redSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ALERT_RED)
        val yellowSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ALERT_YELLOW)

        if (!fillAlertRegions) {
            redSrc?.setGeoJson(MapLibreGeoJson.EMPTY)
            yellowSrc?.setGeoJson(MapLibreGeoJson.EMPTY)
            return
        }

        redSrc?.setGeoJson(MapLibreGeoJson.alertRegions(redOblasts, redRaions))
        yellowSrc?.setGeoJson(MapLibreGeoJson.alertRegions(yellowOblasts, yellowRaions))
    }

    fun updateZoneCircles(
        style: Style,
        centerLat: Double?,
        centerLon: Double?,
        slowRedKm: Double,
        slowYellowKm: Double
    ) {
        val zoneSrc = style.getSourceAs<GeoJsonSource>(SOURCE_ZONE_CIRCLES)
        zoneSrc?.setGeoJson(MapLibreGeoJson.zoneCircles(centerLat, centerLon, slowRedKm, slowYellowKm))
    }
}
