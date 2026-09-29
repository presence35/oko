package com.odesaplay.oko.ui

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer

/** The one place that decides what the basemap is allowed to draw.
 *
 *  Oko owns every name on the map: [com.odesaplay.oko.domain.CityLabelOverlay] alone knows a
 *  city's tier, its language, whether it is alerted and which region mode is active — none of
 *  which a basemap can know. So the basemap supplies geometry and nothing else. Upstream styles
 *  ship place labels regardless, hence [reduceToGeometry]; without it the map renders two sets
 *  of names, the basemap's unswitchable ones underneath our own. */
object MapStyle {
    const val URI = "https://tiles.openfreemap.org/styles/dark"

    /** Strips every symbol layer from a freshly loaded style. Matching on layer *type* rather
     *  than id keeps this working when upstream renames or adds label layers. */
    fun reduceToGeometry(style: Style) {
        style.layers.filterIsInstance<SymbolLayer>().forEach { style.removeLayer(it.id) }
    }
}
