package pt.lisbora.pipeline.datapack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pt.lisbora.routing.graph.GraphFormat
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PackManifestTest {

    private fun clockAt(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test
    fun `lists files with size and sha256, sorted and excluding itself`(@TempDir dir: Path) {
        dir.resolve("b.txt").writeText("abc")
        dir.resolve("a.txt").writeText("")
        dir.resolve(".hidden").writeText("x")
        dir.resolve("graph.lbg.partial").writeText("half")
        PackManifest.write(dir)
        PackManifest.write(dir) // a second run must not list the manifest itself

        val manifest = Json.parseToJsonElement(dir.resolve(PackManifest.FILE_NAME).readText()).jsonObject
        assertEquals(PackManifest.FORMAT_VERSION, manifest["formatVersion"]!!.jsonPrimitive.int)
        val files = manifest["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a.txt", "b.txt"), files.map { it["path"]!!.jsonPrimitive.content })
        assertEquals(3L, files[1]["size"]!!.jsonPrimitive.long)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            files[1]["sha256"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the data version only moves when a file changes`(@TempDir dir: Path) {
        dir.resolve("ciclovias.geojson").writeText("{}")
        PackManifest.write(dir, clockAt("2026-11-02T04:30:12Z"))
        assertEquals("20261102T043012Z", PackManifest.read(dir)!!.dataVersion)

        PackManifest.write(dir, clockAt("2026-11-09T04:30:00Z"))
        assertEquals("20261102T043012Z", PackManifest.read(dir)!!.dataVersion)

        dir.resolve("ciclovias.geojson").writeText("""{"type":"FeatureCollection"}""")
        PackManifest.write(dir, clockAt("2026-11-09T04:31:00Z"))
        assertEquals("20261109T043100Z", PackManifest.read(dir)!!.dataVersion)
    }

    @Test
    fun `names the binary formats inside, and reads back what it writes`(@TempDir dir: Path) {
        dir.resolve(GraphFormat.FILE_NAME).writeText("graph")
        dir.resolve("graph.meta.json").writeText("{}")
        PackManifest.write(dir)
        val manifest = PackManifest.read(dir)!!
        assertEquals(mapOf(GraphFormat.FILE_NAME to GraphFormat.FORMAT_VERSION), manifest.formats)
        assertEquals(setOf(GraphFormat.FILE_NAME), manifest.dataFiles.keys)

        val published = manifest.copy(files = manifest.files.map { it.copy(url = "x/${it.path}") })
        assertEquals(published, PackManifest.parse(PackManifest.encode(published)))
    }

    @Test
    fun `reads manifests written before data versions`() {
        val old = PackManifest.parse("""{"formatVersion":1,"files":[{"path":"a","size":1,"sha256":"00"}]}""")
        assertEquals("", old.dataVersion)
        assertEquals(emptyMap<String, Int>(), old.formats)
    }

    @Test
    fun `source versions come from the newest source, not the build time`() {
        val fallback = Instant.parse("2026-11-09T04:30:00Z")
        assertEquals("20261024T2020Z", SourceVersion.of("2026-10-24T20:20:51Z", "2026-10-23T09:14:09.040Z", fallback = fallback))
        assertEquals("20261109T0430Z", SourceVersion.of(null, "not a date", fallback = fallback))
        assertNotEquals(SourceVersion.of("2026-10-24T20:20:51Z", fallback = fallback), SourceVersion.of("2026-10-31T20:20:51Z", fallback = fallback))
    }
}
