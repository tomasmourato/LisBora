package pt.lisbora.data.datapack

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Opens files bundled with the app (the APK's assets in production). */
fun interface AssetSource {
    @Throws(IOException::class)
    fun open(path: String): InputStream
}

/** A data pack installed on disk, ready to be read by the map and (later) the router. */
data class InstalledDataPack(val dir: File, val manifest: DataPackManifest) {
    /** The installed file for [path], or null when the pack does not contain it. */
    fun file(path: String): File? = manifest.entry(path)?.let { File(dir, it.path) }?.takeIf { it.isFile }
}

/**
 * Copies the data pack bundled in the APK (`assets/datapack`) to private storage, where MapLibre
 * can read it through `file://` URLs. Only files whose SHA-256 changed are copied, each through a
 * temporary file so a crash never leaves a half-written file in place.
 */
class DataPackInstaller(
    private val assets: AssetSource,
    private val targetDir: File,
    private val assetDir: String = BUNDLED_ASSET_DIR,
) {

    @Throws(IOException::class)
    fun install(): InstalledDataPack {
        val bundled = DataPackManifest.parse(readAssetText(DataPackManifest.FILE_NAME))
        check(bundled.formatVersion == DataPackManifest.SUPPORTED_FORMAT_VERSION) {
            "Unsupported data pack format ${bundled.formatVersion}"
        }
        targetDir.mkdirs()
        val installedManifestFile = File(targetDir, DataPackManifest.FILE_NAME)
        val installed = installedManifestFile.takeIf { it.isFile }
            ?.let { runCatching { DataPackManifest.parse(it.readText()) }.getOrNull() }

        val present = bundled.files.filter { entry ->
            val current = File(targetDir, entry.path)
            val upToDate = installed?.entry(entry.path) == entry && current.isFile && current.length() == entry.size
            upToDate || copyVerified(entry, current)
        }
        val manifest = bundled.copy(files = present)
        writeAtomically(installedManifestFile) { it.writeText(manifestJson(manifest)) }
        return InstalledDataPack(targetDir, manifest)
    }

    /** Copies one bundled file; returns false when the APK does not contain it (e.g. a trimmed build). */
    private fun copyVerified(entry: DataPackManifest.Entry, target: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = try {
            assets.open("$assetDir/${entry.path}")
        } catch (_: FileNotFoundException) {
            return false
        }
        writeAtomically(target) { tmp ->
            DigestInputStream(input, digest).use { source -> tmp.outputStream().use { source.copyTo(it, 1 shl 16) } }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != entry.sha256 || tmp.length() != entry.size) {
                throw IOException("Checksum mismatch for ${entry.path}")
            }
        }
        return true
    }

    private fun readAssetText(name: String): String =
        assets.open("$assetDir/$name").bufferedReader().use { it.readText() }

    private inline fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            write(tmp)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not move ${tmp.name} into place")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun manifestJson(manifest: DataPackManifest): String = buildJsonObject {
        put("formatVersion", manifest.formatVersion)
        putJsonArray("files") {
            manifest.files.forEach { f ->
                addJsonObject {
                    put("path", f.path)
                    put("size", f.size)
                    put("sha256", f.sha256)
                }
            }
        }
    }.toString()

    companion object {
        const val BUNDLED_ASSET_DIR = "datapack"
        const val BASEMAP_FILE = "basemap.pmtiles"
        const val CYCLE_NETWORK_FILE = "ciclovias.geojson"
        const val CYCLE_NETWORK_META_FILE = "ciclovias.meta.json"
    }
}
