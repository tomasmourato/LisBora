package pt.lisbora.feature.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.lisbora.routing.model.CycleInfraClass

class MapStyleComposerTest {

    private val baseStyle = """
        {"version":8,"glyphs":"asset://map/fonts/{fontstack}/{range}.pbf",
         "sources":{"protomaps":{"type":"vector","url":"pmtiles://{basemap}","attribution":"Protomaps"}},
         "layers":[
           {"id":"background","type":"background"},
           {"id":"roads","type":"line","source":"protomaps","source-layer":"roads"},
           {"id":"labels","type":"symbol","source":"protomaps","source-layer":"places"}
         ]}
    """.trimIndent()

    private fun compose(basemapPath: String?, poisPath: String? = null, giraPath: String? = null): JsonObject = Json.parseToJsonElement(
        MapStyleComposer.compose(
            baseStyle = baseStyle,
            basemapPath = basemapPath,
            cycleNetworkPath = "/data/pack/ciclovias.geojson",
            cycleAttribution = "Rede Ciclável © CML",
            palette = CycleNetworkPalette.Light,
            poisPath = poisPath,
            giraPath = giraPath,
        ),
    ).jsonObject

    private fun JsonObject.layerIds() = getValue("layers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun `points the basemap source at the installed pmtiles file`() {
        val style = compose("/data/pack/basemap.pmtiles")
        val url = style["sources"]!!.jsonObject["protomaps"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("pmtiles://file:///data/pack/basemap.pmtiles", url)
    }

    @Test
    fun `inserts cycle and route layers above the basemap and below the first label layer`() {
        val ids = compose("/data/pack/basemap.pmtiles").layerIds()
        assertEquals(listOf("background", "roads", CycleNetworkLayers.CASING, CycleNetworkLayers.SELECTION), ids.take(4))
        assertEquals(CycleNetworkLayers.classLayers, ids.subList(4, 4 + CycleNetworkLayers.classLayers.size))
        val routes = listOf(RouteLayers.ALT_CASING, RouteLayers.ALT, RouteLayers.SELECTED_CASING, RouteLayers.SELECTED, RouteLayers.SELECTED_DASHED)
        val afterCycle = 4 + CycleNetworkLayers.classLayers.size
        assertEquals(routes, ids.subList(afterCycle, afterCycle + routes.size))
        // Labels stay above the routes; the trip's markers go on top of everything.
        assertEquals(listOf("labels", RouteLayers.MARKERS, RouteLayers.MARKER_LABELS), ids.takeLast(3))
    }

    @Test
    fun `route sources start empty and credit the graph's sources`() {
        val sources = Json.parseToJsonElement(
            MapStyleComposer.compose(baseStyle, null, "/c.geojson", "CML", CycleNetworkPalette.Light, routeAttribution = "Elevação: Mapterhorn"),
        ).jsonObject.getValue("sources").jsonObject
        assertEquals("Elevação: Mapterhorn", sources.getValue(RouteLayers.SOURCE).jsonObject["attribution"]!!.jsonPrimitive.content)
        assertEquals(0, sources.getValue(RouteLayers.MARKER_SOURCE).jsonObject["data"]!!.jsonObject["features"]!!.jsonArray.size)
    }

    @Test
    fun `adds the cycle network source with its attribution`() {
        val source = compose(null)["sources"]!!.jsonObject[CycleNetworkLayers.SOURCE]!!.jsonObject
        assertEquals("file:///data/pack/ciclovias.geojson", source["data"]!!.jsonPrimitive.content)
        assertEquals("Rede Ciclável © CML", source["attribution"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a basemap drops its source and layers but keeps the background`() {
        val style = compose(null)
        assertNull(style["sources"]!!.jsonObject["protomaps"])
        val ids = style.layerIds()
        assertTrue("background" in ids)
        assertFalse("roads" in ids)
        assertFalse("labels" in ids)
        assertTrue(CycleNetworkLayers.classLayers.all { it in ids })
    }

    @Test
    fun `class layers filter on the class id and dash only lanes and trails`() {
        val layers = compose(null)["layers"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val trail = layers.getValue(CycleNetworkLayers.classLayer(CycleInfraClass.TRAIL))
        assertEquals("""["==",["get","class"],"trail"]""", trail["filter"].toString())
        assertTrue("line-dasharray" in trail["paint"]!!.jsonObject)
        val segregated = layers.getValue(CycleNetworkLayers.classLayer(CycleInfraClass.SEGREGATED_TRACK))
        assertFalse("line-dasharray" in segregated["paint"]!!.jsonObject)
    }

    @Test
    fun `point-of-interest layers start hidden over the cycle network, names above the basemap labels`() {
        val style = compose("/data/pack/basemap.pmtiles", poisPath = "/data/pack/pois.geojson")
        val source = style["sources"]!!.jsonObject[PoiLayer.SOURCE]!!.jsonObject
        assertEquals("file:///data/pack/pois.geojson", source["data"]!!.jsonPrimitive.content)
        val ids = style.layerIds()
        val poiIds = PoiLayer.entries.map { it.layerId }
        val firstPoi = ids.indexOf(poiIds.first())
        assertEquals(poiIds, ids.subList(firstPoi, firstPoi + poiIds.size))
        assertTrue(firstPoi > ids.indexOf(CycleNetworkLayers.classLayers.last()) && firstPoi < ids.indexOf(RouteLayers.ALT_CASING))
        assertTrue(ids.indexOf(PoiLayer.PLACES_LABELS) > ids.indexOf("labels"))

        val layers = style["layers"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val water = layers.getValue(PoiLayer.DRINKING_WATER.layerId)
        assertEquals("none", water["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
        assertEquals("""["in",["get","kind"],["literal",["drinking_water"]]]""", water["filter"].toString())
        assertEquals("none", layers.getValue(PoiLayer.PLACES_LABELS)["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
    }

    @Test
    fun `with EMEL's GIRA list, its stations replace OpenStreetMap's in the bike layer`() {
        val style = compose("/data/pack/basemap.pmtiles", poisPath = "/data/pack/pois.geojson", giraPath = "/data/pack/gira.geojson")
        assertEquals("file:///data/pack/gira.geojson", style["sources"]!!.jsonObject[PoiLayer.GIRA_SOURCE]!!.jsonObject["data"]!!.jsonPrimitive.content)
        val layers = style["layers"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("""["in",["get","kind"],["literal",["bike_shop","bike_repair"]]]""", layers.getValue(PoiLayer.BIKE_SERVICES.layerId)["filter"].toString())
        val gira = layers.getValue(PoiLayer.GIRA_LAYER)
        assertEquals(PoiLayer.GIRA_SOURCE, gira["source"]!!.jsonPrimitive.content)
        assertEquals("none", gira["layout"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)

        val osmOnly = compose("/data/pack/basemap.pmtiles", poisPath = "/data/pack/pois.geojson").layerIds()
        assertFalse(PoiLayer.GIRA_LAYER in osmOnly)
    }

    @Test
    fun `without points of interest there is no layer for them`() {
        val style = compose("/data/pack/basemap.pmtiles")
        assertNull(style["sources"]!!.jsonObject[PoiLayer.SOURCE])
        assertTrue(style.layerIds().none { it.startsWith("poi-") })
    }
}
