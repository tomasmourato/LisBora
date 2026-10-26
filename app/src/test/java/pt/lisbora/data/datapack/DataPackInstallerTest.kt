package pt.lisbora.data.datapack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

class DataPackInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<String>()

    private fun assets(files: Map<String, String>, manifestFiles: Map<String, String> = files, version: String = "20261026T150000Z") = AssetSource { path ->
        opened += path
        val name = path.removePrefix("datapack/")
        val content = if (name == DataPackManifest.FILE_NAME) manifest(manifestFiles, version).toJson().toString() else files[name]
        ByteArrayInputStream((content ?: throw FileNotFoundException(path)).toByteArray())
    }

    private fun manifest(files: Map<String, String>, version: String) = DataPackManifest(
        formatVersion = 1,
        files = files.map { (name, content) -> DataPackManifest.Entry(name, content.length.toLong(), sha256(content)) },
        dataVersion = version,
    )

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun storage(dir: File = tmp.root) = DataPackStorage(File(dir, "datapack"))

    /** Puts a downloaded pack in place, as [DataPackUpdater] leaves it for the next start. */
    private fun download(storage: DataPackStorage, files: Map<String, String>, version: String): DataPackManifest {
        val pack = manifest(files, version)
        pack.files.forEach { entry -> storage.objectFile(entry).apply { parentFile!!.mkdirs() }.writeText(files.getValue(entry.path)) }
        storage.writeState(storage.readState().copy(pending = pack))
        return pack
    }

    @Test
    fun `copies every bundled file and records the pack in use`() {
        val storage = storage()
        val pack = DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles")), storage).install()

        assertEquals("{}", pack.file("a.geojson")!!.readText())
        assertEquals("tiles", pack.file("b.pmtiles")!!.readText())
        assertEquals("20261026T150000Z", storage.readState().active!!.dataVersion)
        assertFalse(storage.root.walk().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a later start copies nothing, and an app update copies only the changed files`() {
        val storage = storage()
        DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles")), storage).install()
        opened.clear()
        DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles")), storage).install()
        assertEquals(listOf("datapack/manifest.json"), opened)

        opened.clear()
        val pack = DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles v2"), version = "20261102T000000Z"), storage).install()
        assertEquals(listOf("datapack/manifest.json", "datapack/b.pmtiles"), opened)
        assertEquals("tiles v2", pack.file("b.pmtiles")!!.readText())
        // The old base map is kept with the pack before, to go back to.
        assertEquals("20261026T150000Z", storage.readState().previous!!.dataVersion)
    }

    @Test
    fun `files listed but missing from the APK are left out`() {
        val pack = DataPackInstaller(
            assets(files = mapOf("a.geojson" to "{}"), manifestFiles = mapOf("a.geojson" to "{}", "basemap.pmtiles" to "x")),
            storage(),
        ).install()

        assertNull(pack.file("basemap.pmtiles"))
        assertEquals(listOf("a.geojson"), pack.manifest.files.map { it.path })
    }

    @Test(expected = IOException::class)
    fun `a checksum mismatch fails the install`() {
        DataPackInstaller(
            assets(files = mapOf("a.geojson" to "corrupted"), manifestFiles = mapOf("a.geojson" to "{\"ok\":1}")),
            storage(),
        ).install()
    }

    @Test
    fun `a downloaded pack takes over at the next start, sharing the files it has in common`() {
        val storage = storage()
        val bundled = assets(mapOf("graph.lbg" to "graph", "basemap.pmtiles" to "tiles"))
        DataPackInstaller(bundled, storage).install()
        download(storage, mapOf("graph.lbg" to "graph v2", "basemap.pmtiles" to "tiles"), version = "20261102T043012Z")

        val pack = DataPackInstaller(bundled, storage).install()

        assertEquals("20261102T043012Z", pack.manifest.dataVersion)
        assertEquals("graph v2", pack.file("graph.lbg")!!.readText())
        val state = storage.readState()
        assertNull(state.pending)
        assertEquals("20261026T150000Z", state.previous!!.dataVersion)
        // Three files on disk: two graphs and one base map.
        assertEquals(3, File(storage.root, "objects").listFiles()!!.size)
    }

    @Test
    fun `a pack that failed to load is dropped for the one before it and never taken again`() {
        val storage = storage()
        val bundled = assets(mapOf("graph.lbg" to "graph"))
        DataPackInstaller(bundled, storage).install()
        val bad = download(storage, mapOf("graph.lbg" to "not a graph"), version = "20261102T043012Z")
        DataPackInstaller(bundled, storage).install()
        storage.writeState(storage.readState().copy(broken = true))

        val pack = DataPackInstaller(bundled, storage).install()
        assertEquals("graph", pack.file("graph.lbg")!!.readText())
        assertEquals(setOf("20261102T043012Z"), storage.readState().rejected)
        assertFalse(storage.objectFile(bad.files.single()).exists())

        // Downloaded again by mistake: still refused.
        download(storage, mapOf("graph.lbg" to "not a graph"), version = "20261102T043012Z")
        assertEquals("20261026T150000Z", DataPackInstaller(bundled, storage).install().manifest.dataVersion)
    }

    @Test
    fun `an app update with newer data replaces a downloaded pack, an older one does not`() {
        val storage = storage()
        DataPackInstaller(assets(mapOf("graph.lbg" to "graph")), storage).install()
        download(storage, mapOf("graph.lbg" to "graph v2"), version = "20261102T043012Z")
        DataPackInstaller(assets(mapOf("graph.lbg" to "graph")), storage).install()

        val same = DataPackInstaller(assets(mapOf("graph.lbg" to "graph")), storage).install()
        assertEquals("graph v2", same.file("graph.lbg")!!.readText())

        val updated = DataPackInstaller(assets(mapOf("graph.lbg" to "graph v3"), version = "20261110T120000Z"), storage).install()
        assertEquals("graph v3", updated.file("graph.lbg")!!.readText())
        assertEquals("20261102T043012Z", storage.readState().previous!!.dataVersion)
    }

    @Test
    fun `an incomplete or unreadable downloaded pack is ignored`() {
        val storage = storage()
        val bundled = assets(mapOf("graph.lbg" to "graph"))
        DataPackInstaller(bundled, storage).install()
        val pack = download(storage, mapOf("graph.lbg" to "graph v2"), version = "20261102T043012Z")
        storage.objectFile(pack.files.single()).delete()
        assertEquals("graph", DataPackInstaller(bundled, storage).install().file("graph.lbg")!!.readText())

        val newer = download(storage, mapOf("graph.lbg" to "graph v3"), version = "20261109T043012Z")
        storage.writeState(storage.readState().copy(pending = newer.copy(formats = mapOf("graph.lbg" to 99))))
        assertEquals("graph", DataPackInstaller(bundled, storage).install().file("graph.lbg")!!.readText())
        assertNull(storage.readState().pending)
    }

    @Test
    fun `files of the layout before versioned packs are removed`() {
        val root = File(tmp.root, "datapack").apply { mkdirs() }
        File(root, "graph.lbg").writeText("old")
        File(root, "manifest.json").writeText("{}")
        val pack = DataPackInstaller(assets(mapOf("graph.lbg" to "graph")), DataPackStorage(root)).install()

        assertEquals("graph", pack.file("graph.lbg")!!.readText())
        assertFalse(File(root, "graph.lbg").exists())
        assertFalse(File(root, "manifest.json").exists())
        assertTrue(File(root, DataPackStorage.STATE_FILE).isFile)
    }
}
