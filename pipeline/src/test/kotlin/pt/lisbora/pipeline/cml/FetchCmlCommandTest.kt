package pt.lisbora.pipeline.cml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pt.lisbora.pipeline.http.HttpGet
import pt.lisbora.routing.model.CycleInfraClass
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.readText

class FetchCmlCommandTest {

    @Test
    fun `client pages through the service until a short page`() {
        val requested = mutableListOf<String>()
        val http = HttpGet { url ->
            requested += url
            when {
                "resultOffset=0&" in url -> page(count = 2, exceeded = true)
                "resultOffset=2&" in url -> page(count = 1, exceeded = false)
                else -> error("unexpected $url")
            }
        }
        val features = CmlClient(http, serviceUrl = "https://example.test/layer").fetchFeatures(pageSize = 2)
        assertEquals(3, features.size)
        assertEquals(2, requested.size)
        assertTrue(requested.all { "outSR=4326" in it && "f=geojson" in it })
    }

    @Test
    fun `snapshot sums length per class and records rejections`() {
        val snapshot = FetchCmlCommand.normalize(
            listOf(
                CmlNormalizerTest.rawFeature(),
                CmlNormalizerTest.rawFeature(typology = "Trilho"),
                CmlNormalizerTest.rawFeature(situation = "Previsto"),
            ),
        )
        assertEquals(2, snapshot.features.size)
        assertEquals(0.1, snapshot.lengthKmByClass[CycleInfraClass.SHARED_STREET_30]!!, 0.01)
        assertEquals(0.1, snapshot.lengthKmByClass[CycleInfraClass.TRAIL]!!, 0.01)
        assertEquals(1, snapshot.rejected[Rejection.NOT_BUILT]!!.size)
    }

    @Test
    fun `validation rejects a suspiciously small network`() {
        val snapshot = FetchCmlCommand.normalize(listOf(CmlNormalizerTest.rawFeature()))
        assertFalse(FetchCmlCommand.validate(snapshot).isEmpty())
    }

    @Test
    fun `run writes geojson, meta and manifest when the snapshot is valid`(@TempDir dir: Path) {
        val http = HttpGet { url ->
            if (url.endsWith("?f=json")) """{"editingInfo":{"lastEditDate":1792746849040}}"""
            else page(count = FetchCmlCommand.MIN_FEATURES, exceeded = false)
        }
        val clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC)
        val output = ByteArrayOutputStream()
        val code = PrintStream(output, true, Charsets.UTF_8).use {
            FetchCmlCommand(CmlClient(http, "https://example.test/layer"), it, clock).run(dir)
        }

        assertEquals(0, code, output.toString(Charsets.UTF_8))
        val geojson = Json.parseToJsonElement(dir.resolve(FetchCmlCommand.GEOJSON_FILE).readText()).jsonObject
        assertEquals(FetchCmlCommand.MIN_FEATURES, geojson["features"]!!.jsonArray.size)
        val meta = Json.parseToJsonElement(dir.resolve(FetchCmlCommand.META_FILE).readText()).jsonObject
        assertEquals("2026-10-23T09:14:09.040Z", meta["sourceLastEdit"]!!.jsonPrimitive.content)
        assertEquals(FetchCmlCommand.MIN_FEATURES, meta["featureCount"]!!.jsonPrimitive.int)
        assertTrue(dir.resolve("manifest.json").exists())
    }

    @Test
    fun `run writes nothing when validation fails`(@TempDir dir: Path) {
        val http = HttpGet { url ->
            if (url.endsWith("?f=json")) "{}" else page(count = 3, exceeded = false)
        }
        val code = PrintStream(ByteArrayOutputStream()).use {
            FetchCmlCommand(CmlClient(http, "https://example.test/layer"), it).run(dir)
        }
        assertEquals(3, code)
        assertFalse(dir.resolve(FetchCmlCommand.GEOJSON_FILE).exists())
    }

    private fun page(count: Int, exceeded: Boolean): String {
        val features = (1..count).joinToString(",") { i ->
            CmlNormalizerTest.rawFeature().toString().replace("a6fae945", "%08d".format(i))
        }
        return """{"type":"FeatureCollection","properties":{"exceededTransferLimit":$exceeded},"features":[$features]}"""
    }
}
