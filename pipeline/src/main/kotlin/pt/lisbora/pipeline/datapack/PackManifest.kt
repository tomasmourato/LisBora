package pt.lisbora.pipeline.datapack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Writes `manifest.json` describing every file of a data pack (size + SHA-256), so the app can
 * install only what changed. The content depends only on the files, keeping it stable across runs.
 */
object PackManifest {

    const val FILE_NAME = "manifest.json"
    const val FORMAT_VERSION = 1

    fun write(packDir: Path): Path {
        val entries = packDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name != FILE_NAME && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .map { file ->
                buildJsonObject {
                    put("path", file.name)
                    put("size", file.fileSize())
                    put("sha256", sha256(file))
                }
            }
        val manifest = buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            put("files", JsonArray(entries))
        }
        return packDir.resolve(FILE_NAME).also { it.writeText(PrettyJson.encode(manifest) + "\n") }
    }

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
