package pt.lisbora.pipeline.gira

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pt.lisbora.pipeline.datapack.PackManifest
import pt.lisbora.routing.geo.GeoPoint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class GiraCommandTest {

    private fun document(name: String, lat: Double = 38.714, lon: Double = -9.193, active: Boolean = true, virtual: Boolean = false, docks: Int = 32) = """
        {"document":{"fields":{
          "Name":{"stringValue":"$name"},
          "Location":{"geoPointValue":{"latitude":$lat,"longitude":$lon}},
          "IsActive":{"booleanValue":$active},
          "IsVirtual":{"booleanValue":$virtual},
          "ServiceStatus":{"stringValue":"UNAVAILABLE_BY_OPERATOR"},
          "AvailableBikes":{"integerValue":"3"},
          "DockLimit":{"integerValue":"$docks"}
        }},"readTime":"2026-11-02T04:30:00Z"}
    """.trimIndent()

    private fun response(vararg documents: String) = documents.joinToString(",", "[", "]")

    /** [count] stations numbered from 1, all in Lisbon. */
    private fun stations(count: Int) = response(*Array(count) { i -> document("${i + 1} - Rua ${i + 1}", lat = 38.70 + i * 0.0005) })

    private fun run(source: GiraSource, dir: Path): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { GiraCommand(source, it).run(dir) }
        return code to buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `keeps the stations that exist, open or closed, sorted by number, without live counts`() {
        val skipped = mutableListOf<String>()
        val parsed = GiraFirestore.parse(
            response(
                document("253 - Avenida da Universidade Técnica / FMV"),
                document("101 - Alameda dos Oceanos", lat = 38.7631, lon = -9.0951, docks = 20),
                document("900 - Armazém", active = false),
                document("901 - Teste", virtual = true),
                document("Sem número"),
                """{"readTime":"2026-11-02T04:30:00Z"}""",
            ),
        ) { skipped += it }

        assertEquals(listOf(101, 253), parsed.map { it.number })
        assertEquals(GiraStation(101, "101 - Alameda dos Oceanos", "Alameda dos Oceanos", GeoPoint(38.7631, -9.0951), 20), parsed[0])
        assertEquals(listOf("900 - Armazém", "901 - Teste", "Sem número"), skipped)
    }

    @Test
    fun `writes gira geojson and its summary into the pack`(@TempDir dir: Path) {
        val (code, output) = run({ stations(GiraCommand.MIN_STATIONS) }, dir)
        assertEquals(0, code, output)

        val features = Json.parseToJsonElement(dir.resolve(GiraCommand.FILE_NAME).readText()).jsonObject["features"]!!.jsonArray
        assertEquals(GiraCommand.MIN_STATIONS, features.size)
        val first = features[0].jsonObject
        assertEquals(1, first["properties"]!!.jsonObject["number"]!!.jsonPrimitive.int)
        assertEquals("Rua 1", first["properties"]!!.jsonObject["place"]!!.jsonPrimitive.content)
        assertEquals(-9.193, first["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonPrimitive.double, 1e-9)
        assertTrue(dir.resolve(GiraCommand.META_FILE).exists())
        assertTrue(PackManifest.read(dir)!!.files.any { it.path == GiraCommand.FILE_NAME })
    }

    @Test
    fun `a broken answer leaves the pack as it was`(@TempDir dir: Path) {
        assertEquals(4, run({ throw IOException("HTTP 403") }, dir).first)
        assertEquals(3, run({ stations(10) }, dir).first)
        assertFalse(dir.resolve(GiraCommand.FILE_NAME).exists())

        assertEquals(0, run({ stations(200) }, dir).first)
        val (code, output) = run({ stations(155) }, dir)
        assertEquals(3, code)
        assertTrue(output.contains("caiu de 200 para 155"), output)
        assertEquals(200, GiraCommand.stationCount(dir.resolve(GiraCommand.FILE_NAME).readText()))
    }

    @Test
    fun `validation flags stations outside the region and repeated numbers`() {
        val command = GiraCommand({ "" }, PrintStream(ByteArrayOutputStream()))
        val lisbon = (1..GiraCommand.MIN_STATIONS).map { GiraStation(it, "$it - Rua", "Rua", GeoPoint(38.72, -9.14), 20) }
        assertEquals(emptyList<String>(), command.validate(lisbon, previousCount = 160))
        val porto = lisbon + GiraStation(999, "999 - Porto", "Porto", GeoPoint(41.15, -8.61), 20)
        assertTrue(command.validate(porto, null).single().contains("fora da região"))
        val repeated = lisbon + lisbon.first()
        assertTrue(command.validate(repeated, null).single().contains("repetidos"))
    }
}
