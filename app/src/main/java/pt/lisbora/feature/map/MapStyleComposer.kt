package pt.lisbora.feature.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.model.CycleInfraClass

/** Ids of the sources and layers LisBora adds on top of the basemap. */
object CycleNetworkLayers {
    const val SOURCE = "cycle-network"
    const val SELECTION_SOURCE = "cycle-selection"
    const val CASING = "cycle-casing"
    const val SELECTION = "cycle-selection-halo"

    fun classLayer(infraClass: CycleInfraClass) = "cycle-${infraClass.id}"

    val classLayers: List<String> = CycleInfraClass.entries.map(::classLayer)

    /** Everything toggled by "show cycle network". */
    val all: List<String> = listOf(CASING, SELECTION) + classLayers
}

/**
 * Builds the final MapLibre style: points the Protomaps source at the installed basemap and inserts
 * the cycle-network, point-of-interest and route layers below the first label layer, so street names
 * stay readable on top. Place names and route markers go above everything. The point-of-interest layers
 * start hidden (the legend switches them on). Pure JSON manipulation, so it is unit-testable on the JVM.
 */
object MapStyleComposer {

    const val BASEMAP_SOURCE = "protomaps"

    fun compose(
        baseStyle: String,
        basemapPath: String?,
        cycleNetworkPath: String,
        cycleAttribution: String,
        palette: CycleNetworkPalette,
        routeAttribution: String? = null,
        poisPath: String? = null,
        poiPalette: PoiPalette = PoiPalette.Light,
        giraPath: String? = null,
    ): String {
        val style = Json.parseToJsonElement(baseStyle).jsonObject
        val baseSources = style.getValue("sources").jsonObject
        val baseLayers = style.getValue("layers").jsonArray.map { it.jsonObject }

        val sources = buildJsonObject {
            baseSources.forEach { (id, source) ->
                when {
                    id != BASEMAP_SOURCE -> put(id, source)
                    basemapPath != null -> put(id, JsonObject(source.jsonObject + ("url" to JsonPrimitive("pmtiles://file://$basemapPath"))))
                    // No basemap installed: drop the source (its layers are dropped below).
                }
            }
            putJsonObject(CycleNetworkLayers.SOURCE) {
                put("type", "geojson")
                put("data", "file://$cycleNetworkPath")
                put("attribution", cycleAttribution)
            }
            putJsonObject(CycleNetworkLayers.SELECTION_SOURCE) { emptyGeoJson() }
            putJsonObject(RouteLayers.SOURCE) {
                emptyGeoJson()
                // Routes and their elevation come from the graph: credit its sources (OSM, CML, elevation).
                routeAttribution?.let { put("attribution", it) }
            }
            putJsonObject(RouteLayers.MARKER_SOURCE) { emptyGeoJson() }
            if (poisPath != null) {
                putJsonObject(PoiLayer.SOURCE) {
                    put("type", "geojson")
                    put("data", "file://$poisPath")
                }
            }
            if (poisPath != null && giraPath != null) {
                putJsonObject(PoiLayer.GIRA_SOURCE) {
                    put("type", "geojson")
                    put("data", "file://$giraPath")
                }
            }
        }

        val keptLayers = baseLayers.filter { basemapPath != null || it.sourceId() != BASEMAP_SOURCE }
        val firstLabel = keptLayers.indexOfFirst { it["type"]?.jsonPrimitive?.contentOrNull == "symbol" }
            .takeIf { it >= 0 } ?: keptLayers.size
        val poiLayers = if (poisPath != null) poiLayers(palette, poiPalette, officialGira = giraPath != null) else emptyList()
        val poiLabels = if (poisPath != null) listOf(poiLabelLayer(poiPalette)) else emptyList()
        val layers = keptLayers.take(firstLabel) + cycleLayers(palette) + poiLayers + routeLayers(palette) +
            keptLayers.drop(firstLabel) + poiLabels + markerLayer(palette) + markerLabelLayer(poiPalette)

        return JsonObject(style + mapOf("sources" to sources, "layers" to JsonArray(layers))).toString()
    }

    private fun JsonObject.sourceId(): String? = this["source"]?.jsonPrimitive?.contentOrNull

    private fun JsonObjectBuilder.emptyGeoJson() {
        put("type", "geojson")
        putJsonObject("data") {
            put("type", "FeatureCollection")
            putJsonArray("features") {}
        }
    }

    /** `["==", ["get", key], value]` */
    private fun propertyEquals(key: String, value: JsonPrimitive) = buildJsonArray {
        add(JsonPrimitive("=="))
        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive(key)) })
        add(value)
    }

    private val dataColor = buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("color")) }

    /** Alternatives in grey under the selected route; colours come from each feature's `color`. */
    private fun routeLayers(palette: CycleNetworkPalette): List<JsonObject> {
        val alt = propertyEquals("layer", JsonPrimitive("alt"))
        val selected = propertyEquals("layer", JsonPrimitive("selected"))
        fun selectedDashed(dashed: Boolean) = buildJsonArray {
            add(JsonPrimitive("all"))
            add(selected)
            add(propertyEquals("dashed", JsonPrimitive(dashed)))
        }
        return listOf(
            lineLayer(RouteLayers.ALT_CASING, RouteLayers.SOURCE, JsonPrimitive(palette.casing), ROUTE_ALT_CASING_WIDTH, filter = alt),
            lineLayer(RouteLayers.ALT, RouteLayers.SOURCE, dataColor, ROUTE_ALT_WIDTH, filter = alt),
            lineLayer(RouteLayers.SELECTED_CASING, RouteLayers.SOURCE, JsonPrimitive(palette.casing), ROUTE_CASING_WIDTH, filter = selected),
            lineLayer(RouteLayers.SELECTED, RouteLayers.SOURCE, dataColor, ROUTE_WIDTH, filter = selectedDashed(false)),
            lineLayer(RouteLayers.SELECTED_DASHED, RouteLayers.SOURCE, dataColor, ROUTE_WIDTH, filter = selectedDashed(true), dashed = true),
        )
    }

    private fun markerLayer(palette: CycleNetworkPalette) = buildJsonObject {
        put("id", RouteLayers.MARKERS)
        put("type", "circle")
        put("source", RouteLayers.MARKER_SOURCE)
        putJsonObject("paint") {
            put("circle-color", dataColor)
            put("circle-radius", buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("radius")) })
            put("circle-stroke-color", palette.casing)
            put("circle-stroke-width", 2.5)
        }
    }

    /** Names of the route's places (Descobrir), next to their markers. */
    private fun markerLabelLayer(poiPalette: PoiPalette) = labelLayer(
        id = RouteLayers.MARKER_LABELS,
        source = RouteLayers.MARKER_SOURCE,
        filter = buildJsonArray { add(JsonPrimitive("has")); add(JsonPrimitive("label")) },
        field = "label",
        size = 12.5,
        poiPalette = poiPalette,
        visible = true,
    )

    /** `["in", ["get", "kind"], ["literal", [...]]]` */
    private fun kindIn(kinds: Collection<PoiKind>) = buildJsonArray {
        add(JsonPrimitive("in"))
        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("kind")) })
        add(buildJsonArray { add(JsonPrimitive("literal")); add(buildJsonArray { kinds.forEach { add(JsonPrimitive(it.code)) } }) })
    }

    private fun kindIn(layer: PoiLayer) = kindIn(layer.kinds)

    /** With [officialGira], the GIRA stations come from EMEL's list and OpenStreetMap's bike-share points are left out. */
    private fun poiLayers(palette: CycleNetworkPalette, poiPalette: PoiPalette, officialGira: Boolean): List<JsonObject> {
        fun circles(id: String, source: String, layer: PoiLayer, filter: JsonElement?) = buildJsonObject {
            put("id", id)
            put("type", "circle")
            put("source", source)
            put("minzoom", layer.minZoom)
            filter?.let { put("filter", it) }
            putJsonObject("layout") { put("visibility", "none") }
            putJsonObject("paint") {
                put("circle-color", poiPalette.color(layer))
                put("circle-radius", zoomWidth(12 to 2.5, 15 to 4.5, 18 to 8.0))
                put("circle-stroke-color", palette.casing)
                put("circle-stroke-width", 1.5)
            }
        }
        val layers = PoiLayer.entries.map { layer ->
            val kinds = if (officialGira && layer == PoiLayer.BIKE_SERVICES) layer.kinds - PoiKind.BIKE_SHARE else layer.kinds
            circles(layer.layerId, PoiLayer.SOURCE, layer, kindIn(kinds))
        }
        return if (officialGira) layers + circles(PoiLayer.GIRA_LAYER, PoiLayer.GIRA_SOURCE, PoiLayer.BIKE_SERVICES, filter = null) else layers
    }

    private fun poiLabelLayer(poiPalette: PoiPalette) = labelLayer(
        id = PoiLayer.PLACES_LABELS,
        source = PoiLayer.SOURCE,
        filter = buildJsonArray {
            add(JsonPrimitive("all"))
            add(kindIn(PoiLayer.PLACES))
            add(buildJsonArray { add(JsonPrimitive("has")); add(JsonPrimitive("name")) })
        },
        field = "name",
        size = 11.0,
        poiPalette = poiPalette,
        visible = false,
        minZoom = PLACE_LABELS_MIN_ZOOM,
    )

    private fun labelLayer(
        id: String,
        source: String,
        filter: JsonElement,
        field: String,
        size: Double,
        poiPalette: PoiPalette,
        visible: Boolean,
        minZoom: Double? = null,
    ) = buildJsonObject {
        put("id", id)
        put("type", "symbol")
        put("source", source)
        minZoom?.let { put("minzoom", it) }
        put("filter", filter)
        putJsonObject("layout") {
            put("visibility", if (visible) "visible" else "none")
            put("text-field", buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive(field)) })
            putJsonArray("text-font") { add(JsonPrimitive(LABEL_FONT)) }
            put("text-size", size)
            put("text-anchor", "top")
            putJsonArray("text-offset") { add(JsonPrimitive(0)); add(JsonPrimitive(0.8)) }
            put("text-max-width", 9)
            put("text-optional", true)
            // Better-known places first when labels collide.
            put(
                "symbol-sort-key",
                buildJsonArray {
                    add(JsonPrimitive("-"))
                    add(
                        buildJsonArray {
                            add(JsonPrimitive("coalesce"))
                            add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("importance")) })
                            add(JsonPrimitive(0))
                        },
                    )
                },
            )
        }
        putJsonObject("paint") {
            put("text-color", poiPalette.label)
            put("text-halo-color", poiPalette.labelHalo)
            put("text-halo-width", 1.4)
        }
    }

    private fun cycleLayers(palette: CycleNetworkPalette): List<JsonObject> = buildList {
        add(lineLayer(CycleNetworkLayers.CASING, CycleNetworkLayers.SOURCE, JsonPrimitive(palette.casing), CASING_WIDTH, opacity = 0.9))
        add(lineLayer(CycleNetworkLayers.SELECTION, CycleNetworkLayers.SELECTION_SOURCE, JsonPrimitive(palette.selection), SELECTION_WIDTH, opacity = 0.85))
        CycleInfraClass.entries.forEach { infraClass ->
            val classStyle = palette.style(infraClass)
            add(
                lineLayer(
                    id = CycleNetworkLayers.classLayer(infraClass),
                    source = CycleNetworkLayers.SOURCE,
                    color = JsonPrimitive(classStyle.color),
                    width = LINE_WIDTH,
                    filter = buildJsonArray {
                        add(JsonPrimitive("=="))
                        add(buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive("class")) })
                        add(JsonPrimitive(infraClass.id))
                    },
                    dashed = classStyle.dashed,
                ),
            )
        }
    }

    private fun lineLayer(
        id: String,
        source: String,
        color: JsonElement,
        width: JsonElement,
        opacity: Double = 1.0,
        filter: JsonElement? = null,
        dashed: Boolean = false,
    ) = buildJsonObject {
        put("id", id)
        put("type", "line")
        put("source", source)
        filter?.let { put("filter", it) }
        putJsonObject("layout") {
            put("line-cap", if (dashed) "butt" else "round")
            put("line-join", "round")
        }
        putJsonObject("paint") {
            put("line-color", color)
            put("line-width", width)
            put("line-opacity", opacity)
            if (dashed) putJsonArray("line-dasharray") { add(JsonPrimitive(2)); add(JsonPrimitive(1.2)) }
        }
    }

    /** `["interpolate", ["exponential", 1.4], ["zoom"], z1, w1, ...]` */
    private fun zoomWidth(vararg stops: Pair<Int, Double>): JsonElement = buildJsonArray {
        add(JsonPrimitive("interpolate"))
        add(buildJsonArray { add(JsonPrimitive("exponential")); add(JsonPrimitive(1.4)) })
        add(buildJsonArray { add(JsonPrimitive("zoom")) })
        stops.forEach { (zoom, width) -> add(JsonPrimitive(zoom)); add(JsonPrimitive(width)) }
    }

    /** A font bundled with the basemap style (`assets/map/fonts`). */
    private const val LABEL_FONT = "NotoSans-Medium"
    private const val PLACE_LABELS_MIN_ZOOM = 14.5

    private val LINE_WIDTH = zoomWidth(11 to 1.0, 13 to 2.0, 15 to 3.5, 18 to 8.0)
    private val CASING_WIDTH = zoomWidth(11 to 2.2, 13 to 3.6, 15 to 6.0, 18 to 12.0)
    private val SELECTION_WIDTH = zoomWidth(11 to 6.0, 15 to 12.0, 18 to 20.0)
    private val ROUTE_ALT_WIDTH = zoomWidth(11 to 2.5, 15 to 6.0, 18 to 11.0)
    private val ROUTE_ALT_CASING_WIDTH = zoomWidth(11 to 4.5, 15 to 9.0, 18 to 16.0)
    private val ROUTE_WIDTH = zoomWidth(11 to 3.5, 15 to 7.0, 18 to 13.0)
    private val ROUTE_CASING_WIDTH = zoomWidth(11 to 6.0, 15 to 11.0, 18 to 19.0)
}
