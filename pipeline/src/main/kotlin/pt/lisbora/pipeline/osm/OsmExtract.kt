package pt.lisbora.pipeline.osm

import pt.lisbora.pipeline.http.JdkHttpGet
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/**
 * The Geofabrik OpenStreetMap extract for Portugal, cached under `pipeline/work/osm`.
 * Downloaded when missing (or when [refresh] is set) and always checked against Geofabrik's MD5.
 */
class OsmExtract(
    private val file: Path,
    private val http: JdkHttpGet,
    private val out: PrintStream,
    private val url: String = DEFAULT_URL,
) {

    fun ensure(refresh: Boolean): Path {
        val md5File = file.resolveSibling(file.fileName.toString() + ".md5")
        if (file.exists() && !refresh) {
            if (md5File.exists()) verify(md5File)
            out.println("OSM: a usar $file (${file.fileSize() / 1_000_000} MB)")
            return file
        }
        Files.createDirectories(file.parent)
        out.println("OSM: a descarregar $url …")
        val partial = file.resolveSibling(file.fileName.toString() + ".part")
        http.download(url, partial)
        Files.writeString(md5File, http.getText("$url.md5"))
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING)
        verify(md5File)
        out.println("OSM: ${file.fileSize() / 1_000_000} MB descarregados e verificados")
        return file
    }

    private fun verify(md5File: Path) {
        val expected = md5File.readText().trim().substringBefore(' ').lowercase()
        val actual = md5(file)
        if (expected != actual) throw IOException("MD5 mismatch for $file: expected $expected, got $actual")
    }

    companion object {
        const val DEFAULT_URL = "https://download.geofabrik.de/europe/portugal-latest.osm.pbf"
        const val DEFAULT_FILE = "pipeline/work/osm/portugal-latest.osm.pbf"
        const val ATTRIBUTION = "© OpenStreetMap contributors (ODbL)"

        fun md5(file: Path): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
