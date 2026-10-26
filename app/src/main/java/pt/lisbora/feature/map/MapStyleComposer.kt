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
 * the cycle-network and route layers below the first label layer, so street names stay readable on top.
 * Route markers go above everything. Pure JSON manipulation, so it is unit-testable on the JVM.
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
        }

        val keptLayers = baseLayers.filter { basemapPath != null || it.sourceId() != BASEMAP_SOURCE }
        val firstLabel = keptLayers.indexOfFirst { it["type"]?.jsonPrimitive?.contentOrNull == "symbol" }
            .takeIf { it >= 0 } ?: keptLayers.size
        val layers = keptLayers.take(firstLabel) + cycleLayers(palette) + routeLayers(palette) +
            keptLayers.drop(firstLabel) + markerLayer(palette)

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

    private val LINE_WIDTH = zoomWidth(11 to 1.0, 13 to 2.0, 15 to 3.5, 18 to 8.0)
    private val CASING_WIDTH = zoomWidth(11 to 2.2, 13 to 3.6, 15 to 6.0, 18 to 12.0)
    private val SELECTION_WIDTH = zoomWidth(11 to 6.0, 15 to 12.0, 18 to 20.0)
    private val ROUTE_ALT_WIDTH = zoomWidth(11 to 2.5, 15 to 6.0, 18 to 11.0)
    private val ROUTE_ALT_CASING_WIDTH = zoomWidth(11 to 4.5, 15 to 9.0, 18 to 16.0)
    private val ROUTE_WIDTH = zoomWidth(11 to 3.5, 15 to 7.0, 18 to 13.0)
    private val ROUTE_CASING_WIDTH = zoomWidth(11 to 6.0, 15 to 11.0, 18 to 19.0)
}
