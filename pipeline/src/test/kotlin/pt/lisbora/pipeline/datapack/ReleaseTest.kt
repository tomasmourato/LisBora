package pt.lisbora.pipeline.datapack

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pt.lisbora.pipeline.http.JdkHttpGet
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ReleaseTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** What the fake GitHub release serves, by asset name. */
    private val published = mutableMapOf<String, ByteArray>()
    private val base get() = "http://127.0.0.1:${server.address.port}/datapack/"

    init {
        server.createContext("/datapack/") { exchange ->
            val body = published[exchange.requestURI.path.removePrefix("/datapack/")]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun clockAt(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    private fun manifest(version: String, vararg files: Pair<String, String>) = Manifest(
        formatVersion = 1,
        dataVersion = version,
        formats = emptyMap(),
        files = files.map { (path, sha) -> Manifest.Entry(path, 10, sha) },
    )

    /** Runs `release` and uploads the result to the fake release, as the workflow does. */
    private fun release(pack: Path, releaseDir: Path, at: String): String {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { out ->
            ReleaseCommand(JdkHttpGet(attempts = 1), out, clockAt(at)).run(pack, releaseDir, base + PackManifest.FILE_NAME)
        }
        assertEquals(0, code, buffer.toString(Charsets.UTF_8))
        if (releaseDir.resolve(PackManifest.FILE_NAME).exists()) {
            releaseDir.resolve("assets").listDirectoryEntries().forEach { published[it.name] = Files.readAllBytes(it) }
            published[PackManifest.FILE_NAME] = Files.readAllBytes(releaseDir.resolve(PackManifest.FILE_NAME))
            val keep = releaseDir.resolve("keep.txt").readLines().toSet()
            published.keys.retainAll(keep)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `nothing to publish when only fetch times changed`() {
        val published = manifest("20261102T043012Z", "graph.lbg" to "aa", "graph.meta.json" to "m1")
        val local = manifest("20261109T043000Z", "graph.lbg" to "aa", "graph.meta.json" to "m2")
        assertNull(ReleasePlan.of(local, published, Instant.parse("2026-11-09T04:30:00Z")))
    }

    @Test
    fun `a new pack uploads only the files that changed and keeps the published ones for a while`() {
        val published = manifest("20261102T043012Z", "basemap.pmtiles" to "b1", "graph.lbg" to "g1")
            .let { m -> m.copy(files = m.files.map { it.copy(url = ReleasePlan.assetName(it)) }) }
        val local = manifest("20261109T043000Z", "basemap.pmtiles" to "b1", "graph.lbg" to "g2", "gira.geojson" to "s1")
        val plan = ReleasePlan.of(local, published, Instant.parse("2026-11-09T04:30:00Z"))!!

        assertEquals("20261109T043000Z", plan.manifest.dataVersion)
        assertEquals(listOf("gira.geojson", "graph.lbg"), plan.changed)
        assertEquals(listOf("graph.g2.lbg", "gira.s1.geojson"), plan.uploads.map { it.url })
        assertEquals(setOf("basemap.b1.pmtiles", "graph.g1.lbg", "graph.g2.lbg", "gira.s1.geojson", "manifest.json"), plan.keep)
    }

    @Test
    fun `asset names carry the content hash and keep the extension`() {
        assertEquals("graph.625e72943f43be77.lbg", ReleasePlan.assetName(Manifest.Entry("graph.lbg", 1, "625e72943f43be77abcdef")))
        assertEquals("graph.meta.430dd46d73245d80.json", ReleasePlan.assetName(Manifest.Entry("graph.meta.json", 1, "430dd46d73245d80ff")))
        assertEquals("LICENSE.0123456789abcdef", ReleasePlan.assetName(Manifest.Entry("LICENSE", 1, "0123456789abcdef00")))
    }

    @Test
    fun `release, publish and pull round trip`(@TempDir root: Path) {
        val pack = Files.createDirectories(root.resolve("pack"))
        val releaseDir = root.resolve("release")
        pack.resolve("graph.lbg").writeText("graph v1")
        pack.resolve("graph.meta.json").writeText("""{"fetchedAt":"1"}""")
        PackManifest.write(pack, clockAt("2026-11-01T10:00:00Z"))

        assertTrue(release(pack, releaseDir, at = "2026-11-02T04:30:12Z").contains("Pacote 20261102T043012Z"))
        assertEquals(setOf("graph.lbg", "graph.meta.json"), PackManifest.parse(published.getValue("manifest.json").decodeToString()).files.map { it.path }.toSet())

        // A week later only the fetch time changed: nothing is published.
        pack.resolve("graph.meta.json").writeText("""{"fetchedAt":"2"}""")
        assertTrue(release(pack, releaseDir, at = "2026-11-09T04:30:00Z").contains("nada a publicar"))
        assertFalse(releaseDir.exists())

        // New graph: published, and pulled into an empty folder exactly as built.
        pack.resolve("graph.lbg").writeText("graph v2")
        release(pack, releaseDir, at = "2026-11-16T04:30:00Z")
        val pulled = root.resolve("pulled")
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { PullCommand(JdkHttpGet(attempts = 1), it).run(base + PackManifest.FILE_NAME, pulled) }
        assertEquals(0, code, buffer.toString(Charsets.UTF_8))
        assertEquals("graph v2", pulled.resolve("graph.lbg").readText())
        val manifest = PackManifest.read(pulled)!!
        assertEquals("20261116T043000Z", manifest.dataVersion)
        assertTrue(manifest.files.all { it.url == null })
        // The first graph is gone from the release; the one before the current one stays.
        assertEquals(2, published.keys.count { it.startsWith("graph.") && it.endsWith(".lbg") })
    }

    @Test
    fun `pull does nothing before anything is published`(@TempDir root: Path) {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { PullCommand(JdkHttpGet(attempts = 1), it).run(base + PackManifest.FILE_NAME, root.resolve("pack")) }
        assertEquals(0, code)
        assertFalse(root.resolve("pack").exists())
    }
}
