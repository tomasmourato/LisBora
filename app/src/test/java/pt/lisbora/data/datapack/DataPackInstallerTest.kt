package pt.lisbora.data.datapack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

class DataPackInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = mutableListOf<String>()

    private fun assets(files: Map<String, String>, manifestFiles: Map<String, String> = files) = AssetSource { path ->
        opened += path
        val name = path.removePrefix("datapack/")
        val content = if (name == DataPackManifest.FILE_NAME) manifest(manifestFiles) else files[name]
        ByteArrayInputStream((content ?: throw FileNotFoundException(path)).toByteArray())
    }

    private fun manifest(files: Map<String, String>) = files.entries.joinToString(
        prefix = """{"formatVersion":1,"files":[""", postfix = "]}",
    ) { (name, content) -> """{"path":"$name","size":${content.length},"sha256":"${sha256(content)}"}""" }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `copies every bundled file and records the installed manifest`() {
        val dir = tmp.newFolder("pack")
        val pack = DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles")), dir).install()

        assertEquals("{}", pack.file("a.geojson")!!.readText())
        assertEquals("tiles", pack.file("b.pmtiles")!!.readText())
        assertTrue(dir.resolve(DataPackManifest.FILE_NAME).isFile)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `second install skips unchanged files and recopies changed ones`() {
        val dir = tmp.newFolder("pack")
        DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles")), dir).install()
        opened.clear()

        val pack = DataPackInstaller(assets(mapOf("a.geojson" to "{}", "b.pmtiles" to "tiles v2")), dir).install()

        assertEquals(listOf("datapack/manifest.json", "datapack/b.pmtiles"), opened)
        assertEquals("tiles v2", pack.file("b.pmtiles")!!.readText())
    }

    @Test
    fun `files listed but missing from the APK are left out`() {
        val dir = tmp.newFolder("pack")
        val pack = DataPackInstaller(
            assets(files = mapOf("a.geojson" to "{}"), manifestFiles = mapOf("a.geojson" to "{}", "basemap.pmtiles" to "x")),
            dir,
        ).install()

        assertNull(pack.file("basemap.pmtiles"))
        assertEquals(listOf("a.geojson"), pack.manifest.files.map { it.path })
    }

    @Test(expected = IOException::class)
    fun `a checksum mismatch fails the install`() {
        val dir = tmp.newFolder("pack")
        DataPackInstaller(
            assets(files = mapOf("a.geojson" to "corrupted"), manifestFiles = mapOf("a.geojson" to "{\"ok\":1}")),
            dir,
        ).install()
    }
}
