package pt.lisbora.data.datapack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DataPackUpdaterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bundledFiles = mapOf("graph.lbg" to "graph", "basemap.pmtiles" to "tiles")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneOffset.UTC)

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun manifest(files: Map<String, String>, version: String, published: Boolean = false) = DataPackManifest(
        formatVersion = 1,
        files = files.map { (name, content) ->
            DataPackManifest.Entry(name, content.length.toLong(), sha256(content)).let { if (published) it.copy(url = DataPackStorage.objectName(it)) else it }
        },
        dataVersion = version,
    )

    private val assets = AssetSource { path ->
        val name = path.removePrefix("datapack/")
        val content = if (name == DataPackManifest.FILE_NAME) manifest(bundledFiles, "20261026T150000Z").toJson().toString() else bundledFiles[name]
        ByteArrayInputStream((content ?: throw FileNotFoundException(path)).toByteArray())
    }

    /** A fake GitHub release: the manifest plus each file under its published name. */
    private class FakeRelease : DataPackHttp {
        val files = mutableMapOf<String, String>()
        val requests = mutableListOf<Pair<String, Long>>()
        var offline = false

        /** Bytes of [name] to send before failing, to cut a download short. */
        var cutAfter: Pair<String, Int>? = null

        override fun get(url: String): String? {
            if (offline) throw IOException("offline")
            return files[url.substringAfterLast('/')]
        }

        override fun open(url: String, from: Long): DataPackHttp.Body {
            if (offline) throw IOException("offline")
            val name = url.substringAfterLast('/')
            requests += name to from
            val bytes = (files[name] ?: throw IOException("HTTP 404 for $url")).toByteArray()
            val cut = cutAfter?.takeIf { it.first == name }?.second
            if (cut != null) {
                cutAfter = null
                return DataPackHttp.Body(0, object : java.io.InputStream() {
                    var i = 0
                    override fun read(): Int = if (i < cut) bytes[i++].toInt() and 0xff else throw IOException("connection reset")
                })
            }
            return DataPackHttp.Body(from, ByteArrayInputStream(bytes, from.toInt(), bytes.size - from.toInt()))
        }

        fun publish(manifest: DataPackManifest, contents: Map<String, String>) {
            files["manifest.json"] = manifest.toJson().toString()
            manifest.files.forEach { files[it.url!!] = contents.getValue(it.path) }
        }
    }

    private val release = FakeRelease()
    private val storage by lazy { DataPackStorage(File(tmp.root, "datapack")) }
    private val installer by lazy { DataPackInstaller(assets, storage) }

    private fun store() = DataPackStore(installer, Dispatchers.Unconfined)

    private fun updater(store: DataPackStore) =
        DataPackUpdater(store, release, "https://github.com/x/y/releases/download/datapack/manifest.json", clock, Dispatchers.Unconfined)

    @Test
    fun `downloads only the changed files, and the new pack takes over at the next start`() = runTest {
        val store = store()
        store.pack()
        val contents = mapOf("graph.lbg" to "graph v2", "basemap.pmtiles" to "tiles", "gira.geojson" to "{}")
        release.publish(manifest(contents, "20261102T043012Z", published = true), contents)

        val status = updater(store).update()

        assertEquals(DataUpdateStatus.Ready("20261102T043012Z"), status)
        assertEquals(listOf("graph.lbg", "gira.geojson"), release.requests.map { (name, _) -> name.substringBefore('.') + "." + name.substringAfterLast('.') })
        assertEquals(clock.millis(), store.state().checkedAt)
        val next = DataPackStore(installer, Dispatchers.Unconfined).pack()
        assertEquals("graph v2", next.file("graph.lbg")!!.readText())
        assertEquals("{}", next.file("gira.geojson")!!.readText())
    }

    @Test
    fun `nothing to do when the published pack is not newer`() = runTest {
        val store = store()
        store.pack()
        release.publish(manifest(bundledFiles, "20261026T150000Z", published = true), bundledFiles)
        assertEquals(DataUpdateStatus.UpToDate, updater(store).update())
        assertTrue(release.requests.isEmpty())
        assertEquals(clock.millis(), store.state().checkedAt)
    }

    @Test
    fun `before anything is published there is nothing new, not an error`() = runTest {
        val store = store()
        store.pack()
        assertEquals(DataUpdateStatus.UpToDate, updater(store).update())
        assertEquals(clock.millis(), store.state().checkedAt)
    }

    @Test
    fun `a pack in newer formats waits for an app update`() = runTest {
        val store = store()
        store.pack()
        val contents = mapOf("graph.lbg" to "graph v2")
        release.publish(manifest(contents, "20261102T043012Z", published = true).copy(formats = mapOf("graph.lbg" to 99)), contents)
        assertEquals(DataUpdateStatus.NeedsAppUpdate, updater(store).update())
        assertTrue(release.requests.isEmpty())
    }

    @Test
    fun `a pack that failed here is not downloaded again`() = runTest {
        val store = store()
        store.pack()
        store.updateState { it.copy(rejected = setOf("20261102T043012Z")) }
        val contents = mapOf("graph.lbg" to "graph v2")
        release.publish(manifest(contents, "20261102T043012Z", published = true), contents)
        assertEquals(DataUpdateStatus.UpToDate, updater(store).update())
    }

    @Test
    fun `a download cut short continues where it stopped`() = runTest {
        val store = store()
        store.pack()
        val contents = mapOf("graph.lbg" to "a graph of a few more bytes", "basemap.pmtiles" to "tiles")
        release.publish(manifest(contents, "20261102T043012Z", published = true), contents)
        release.cutAfter = release.files.keys.first { it.startsWith("graph.") } to 10
        val updater = updater(store)

        assertEquals(DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.NETWORK), updater.update())
        assertEquals(DataUpdateStatus.Ready("20261102T043012Z"), updater.update())
        assertEquals(10L, release.requests.last().second)
        assertEquals("a graph of a few more bytes", DataPackStore(installer, Dispatchers.Unconfined).pack().file("graph.lbg")!!.readText())
    }

    @Test
    fun `a file that does not match the manifest is thrown away`() = runTest {
        val store = store()
        store.pack()
        val contents = mapOf("graph.lbg" to "graph v2")
        val published = manifest(contents, "20261102T043012Z", published = true)
        release.publish(published, mapOf("graph.lbg" to "graph v?"))

        assertEquals(DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.CORRUPT), updater(store).update())
        assertNull(store.state().pending)
        assertFalse(storage.partFile(published.files.single()).exists())
        assertFalse(storage.stagingFile(published.files.single()).exists())
    }

    @Test
    fun `offline, it says so and changes nothing`() = runTest {
        val store = store()
        store.pack()
        release.offline = true
        assertEquals(DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.NETWORK), updater(store).update())
        assertNull(store.state().checkedAt)
    }

    @Test
    fun `a pack downloaded by an earlier process is shown as ready`() = runTest {
        val store = store()
        store.pack()
        val contents = mapOf("graph.lbg" to "graph v2")
        release.publish(manifest(contents, "20261102T043012Z", published = true), contents)
        updater(store).update()

        val later = updater(store)
        later.refreshStatus()
        assertEquals(DataUpdateStatus.Ready("20261102T043012Z"), later.status.value)
        // Asking again finds nothing newer, and still says the new data is waiting.
        assertEquals(DataUpdateStatus.Ready("20261102T043012Z"), later.update())
    }
}
