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

    private fun compose(basemapPath: String?): JsonObject = Json.parseToJsonElement(
        MapStyleComposer.compose(
            baseStyle = baseStyle,
            basemapPath = basemapPath,
            cycleNetworkPath = "/data/pack/ciclovias.geojson",
            cycleAttribution = "Rede Ciclável © CML",
            palette = CycleNetworkPalette.Light,
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
    fun `inserts cycle layers above the basemap and below the first label layer`() {
        val ids = compose("/data/pack/basemap.pmtiles").layerIds()
        assertEquals(listOf("background", "roads", CycleNetworkLayers.CASING, CycleNetworkLayers.SELECTION), ids.take(4))
        assertEquals(CycleNetworkLayers.classLayers, ids.subList(4, 4 + CycleNetworkLayers.classLayers.size))
        assertEquals("labels", ids.last())
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
}
