package ua.ukrainedrones.ui

import ua.ukrainedrones.BuildConfig

object MapLibreStyle {
    fun cartoDarkJson(): String {
        val key = BuildConfig.CARTO_API_KEY.takeIf { it.isNotBlank() }
        val query = if (key != null) "?key=$key" else ""
        val tileUrls = listOf("a", "b", "c", "d").joinToString(",") { sub ->
            "\"https://$sub.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}.png$query\""
        }

        return """
        {
          "version": 8,
          "name": "CartoDarkNoLabels",
          "sources": {
            "carto-dark": {
              "type": "raster",
              "tiles": [$tileUrls],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "© OpenStreetMap contributors © CARTO"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#0d1117"
              }
            },
            {
              "id": "carto-dark-layer",
              "type": "raster",
              "source": "carto-dark",
              "minzoom": 0,
              "maxzoom": 22
            }
          ]
        }
        """.trimIndent()
    }
}
