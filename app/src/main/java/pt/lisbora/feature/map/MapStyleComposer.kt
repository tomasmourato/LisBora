package pt.lisbora.feature.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * the cycle-network layers below the first label layer, so street names stay readable on top.
 * Pure JSON manipulation, so it is unit-testable on the JVM.
 */
object MapStyleComposer {

    const val BASEMAP_SOURCE = "protomaps"

    fun compose(
        baseStyle: String,
        basemapPath: String?,
        cycleNetworkPath: String,
        cycleAttribution: String,
        palette: CycleNetworkPalette,
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
            putJsonObject(CycleNetworkLayers.SELECTION_SOURCE) {
                put("type", "geojson")
                putJsonObject("data") {
                    put("type", "FeatureCollection")
                    putJsonArray("features") {}
                }
            }
        }

        val keptLayers = baseLayers.filter { basemapPath != null || it.sourceId() != BASEMAP_SOURCE }
        val firstLabel = keptLayers.indexOfFirst { it["type"]?.jsonPrimitive?.contentOrNull == "symbol" }
            .takeIf { it >= 0 } ?: keptLayers.size
        val layers = keptLayers.take(firstLabel) + cycleLayers(palette) + keptLayers.drop(firstLabel)

        return JsonObject(style + mapOf("sources" to sources, "layers" to JsonArray(layers))).toString()
    }

    private fun JsonObject.sourceId(): String? = this["source"]?.jsonPrimitive?.contentOrNull

    private fun cycleLayers(palette: CycleNetworkPalette): List<JsonObject> = buildList {
        add(lineLayer(CycleNetworkLayers.CASING, CycleNetworkLayers.SOURCE, palette.casing, CASING_WIDTH, opacity = 0.9))
        add(lineLayer(CycleNetworkLayers.SELECTION, CycleNetworkLayers.SELECTION_SOURCE, palette.selection, SELECTION_WIDTH, opacity = 0.85))
        CycleInfraClass.entries.forEach { infraClass ->
            val classStyle = palette.style(infraClass)
            add(
                lineLayer(
                    id = CycleNetworkLayers.classLayer(infraClass),
                    source = CycleNetworkLayers.SOURCE,
                    color = classStyle.color,
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
        color: String,
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
}
