package pt.lisbora.pipeline.datapack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.search.SearchIndexFormat
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A data pack's `manifest.json`: every file with its size and SHA-256, and when the pack last changed. */
data class Manifest(
    val formatVersion: Int,
    /** When the pack's content last changed ("20261102T043012Z"); a later version is newer data. */
    val dataVersion: String,
    /** Versions of the binary formats inside, so an app only installs packs it can read. */
    val formats: Map<String, Int>,
    val files: List<Entry>,
) {
    /** [url]: where a published pack's file can be downloaded, relative to the manifest. */
    data class Entry(val path: String, val size: Long, val sha256: String, val url: String? = null)

    fun entry(path: String): Entry? = files.firstOrNull { it.path == path }

    /** The files that hold data, by path; the `*.meta.json` summaries are left out, as they carry fetch times. */
    val dataFiles: Map<String, String> get() = files.filterNot { it.path.endsWith(PackManifest.META_SUFFIX) }.associate { it.path to it.sha256 }
}

/**
 * Writes `manifest.json` describing every file of a data pack (size + SHA-256), so the app can
 * install only what changed. The data version only moves when a file changes, keeping the manifest
 * stable across runs that produce the same files.
 */
object PackManifest {

    const val FILE_NAME = "manifest.json"
    const val FORMAT_VERSION = 1
    const val META_SUFFIX = ".meta.json"

    private val FORMATS = mapOf(
        GraphFormat.FILE_NAME to GraphFormat.FORMAT_VERSION,
        SearchIndexFormat.FILE_NAME to SearchIndexFormat.FORMAT_VERSION,
    )

    private val VERSION_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun write(packDir: Path, clock: Clock = Clock.systemUTC()): Path {
        val files = packDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name != FILE_NAME && !it.name.startsWith(".") && !it.name.endsWith(".partial") }
            .sortedBy { it.name }
            .map { Manifest.Entry(it.name, it.fileSize(), sha256(it)) }
        val previous = read(packDir)
        val dataVersion = previous?.takeIf { it.files == files && it.dataVersion.isNotEmpty() }?.dataVersion ?: version(clock.instant())
        val manifest = Manifest(FORMAT_VERSION, dataVersion, FORMATS.filterKeys { path -> files.any { it.path == path } }, files)
        return packDir.resolve(FILE_NAME).also { it.writeText(encode(manifest)) }
    }

    /** The manifest of the pack in [packDir], or null when there is none. */
    fun read(packDir: Path): Manifest? = packDir.resolve(FILE_NAME).takeIf { it.exists() }?.let { parse(it.readText()) }

    fun parse(json: String): Manifest {
        val root = Json.parseToJsonElement(json).jsonObject
        return Manifest(
            formatVersion = root.getValue("formatVersion").jsonPrimitive.int,
            dataVersion = root["dataVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            formats = root["formats"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.int },
            files = root.getValue("files").jsonArray.map { element ->
                val file = element.jsonObject
                Manifest.Entry(
                    path = file.getValue("path").jsonPrimitive.content,
                    size = file.getValue("size").jsonPrimitive.long,
                    sha256 = file.getValue("sha256").jsonPrimitive.content,
                    url = file["url"]?.jsonPrimitive?.contentOrNull,
                )
            },
        )
    }

    fun encode(manifest: Manifest): String = PrettyJson.encode(toJson(manifest)) + "\n"

    private fun toJson(manifest: Manifest): JsonObject = buildJsonObject {
        put("formatVersion", manifest.formatVersion)
        put("dataVersion", manifest.dataVersion)
        putJsonObject("formats") { manifest.formats.forEach { (path, version) -> put(path, version) } }
        putJsonArray("files") {
            manifest.files.forEach { f ->
                addJsonObject {
                    put("path", f.path)
                    put("size", f.size)
                    put("sha256", f.sha256)
                    f.url?.let { put("url", it) }
                }
            }
        }
    }

    /** A data version for [instant]: sortable as text. */
    fun version(instant: Instant): String = VERSION_FORMAT.format(instant)

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Ensures [packDir] exists before writing into it. */
    fun prepare(packDir: Path): Path = Files.createDirectories(packDir)
}
