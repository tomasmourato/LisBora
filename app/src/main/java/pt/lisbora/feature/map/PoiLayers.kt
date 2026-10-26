package pt.lisbora.feature.map

import androidx.annotation.StringRes
import pt.lisbora.R
import pt.lisbora.routing.discover.PoiKind

/**
 * The optional point-of-interest layers of the map, each switched on from the legend. Street furniture
 * only appears close up, where it is useful and does not crowd the map.
 */
enum class PoiLayer(val kinds: Set<PoiKind>, val minZoom: Double, @param:StringRes val labelRes: Int) {
    PLACES(
        setOf(PoiKind.VIEWPOINT, PoiKind.PARK, PoiKind.BEACH, PoiKind.MUSEUM, PoiKind.MONUMENT, PoiKind.ATTRACTION),
        minZoom = 12.0,
        labelRes = R.string.poi_layer_places,
    ),
    DRINKING_WATER(setOf(PoiKind.DRINKING_WATER), minZoom = 13.5, labelRes = R.string.poi_layer_water),
    BIKE_PARKING(setOf(PoiKind.BIKE_PARKING), minZoom = 14.0, labelRes = R.string.poi_layer_parking),
    BIKE_SERVICES(setOf(PoiKind.BIKE_SHARE, PoiKind.BIKE_SHOP, PoiKind.BIKE_REPAIR), minZoom = 13.0, labelRes = R.string.poi_layer_bike);

    val layerId: String get() = "poi-${name.lowercase().replace('_', '-')}"

    companion object {
        const val SOURCE = "pois"

        /** Names of [PLACES], shown close up. */
        const val PLACES_LABELS = "poi-places-label"

        /** EMEL's GIRA stations (`gira.geojson`), shown with [BIKE_SERVICES] in place of OpenStreetMap's. */
        const val GIRA_SOURCE = "gira"
        const val GIRA_LAYER = "poi-gira"
    }
}

/** Colours of the point-of-interest layers and of the labels LisBora draws, light and dark. */
data class PoiPalette(val colors: Map<PoiLayer, String>, val label: String, val labelHalo: String) {
    fun color(layer: PoiLayer): String = colors.getValue(layer)

    companion object {
        val Light = PoiPalette(
            colors = mapOf(
                PoiLayer.PLACES to "#9333EA",
                PoiLayer.DRINKING_WATER to "#0891B2",
                PoiLayer.BIKE_PARKING to "#475569",
                PoiLayer.BIKE_SERVICES to "#DB2777",
            ),
            label = "#1F2937",
            labelHalo = "#FFFFFF",
        )

        val Dark = PoiPalette(
            colors = mapOf(
                PoiLayer.PLACES to "#C084FC",
                PoiLayer.DRINKING_WATER to "#22D3EE",
                PoiLayer.BIKE_PARKING to "#94A3B8",
                PoiLayer.BIKE_SERVICES to "#F472B6",
            ),
            label = "#E5E7EB",
            labelHalo = "#101412",
        )
    }
}
