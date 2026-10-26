package pt.lisbora.data.datapack

import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.search.SearchIndexFormat
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

/** A data pack installed on disk, ready to be read by the map, the router and the search. */
data class InstalledDataPack(val storage: DataPackStorage, val manifest: DataPackManifest) {
    /** The installed file for [path], or null when the pack does not contain it. */
    fun file(path: String): File? = manifest.entry(path)?.let(storage::objectFile)?.takeIf { it.isFile }
}

/**
 * Chooses the data pack to use at start-up and makes sure its files are on disk, where MapLibre can
 * read them through `file://` URLs:
 * - a pack that failed to load last time is dropped for the one before it, and never installed again;
 * - a downloaded pack waiting for this start ([DataPackUpdater]) takes over, keeping the old one to go back to;
 * - the pack bundled in the APK (`assets/datapack`) is copied on the first start, when the app brings newer
 *   data than the phone has (an app update), or when nothing else is usable. Each file is checked against
 *   its SHA-256 and written through a temporary file, so a crash never leaves a half-written file in place.
 *
 * Packs are switched only here, before anything reads them, so the map, the router and the search always
 * use the same version. Files no pack uses any more are deleted.
 */
class DataPackInstaller(
    private val assets: AssetSource,
    val storage: DataPackStorage,
    private val assetDir: String = BUNDLED_ASSET_DIR,
) {

    @Throws(IOException::class)
    fun install(): InstalledDataPack {
        val bundled = DataPackManifest.parse(readAssetText(DataPackManifest.FILE_NAME))
        check(bundled.isSupported) { "Unsupported data pack format ${bundled.formatVersion}" }
        storage.prepare()
        var state = storage.readState()

        val failed = state.active
        if (state.broken && failed != null) {
            state = state.copy(active = state.previous, previous = null, rejected = state.rejected + failed.dataVersion, broken = false)
        }
        state.pending?.let { pending ->
            val takeOver = usable(pending) && pending.dataVersion !in state.rejected && pending.isNewerThan(state.active)
            state = if (takeOver) state.copy(active = pending, previous = state.active, pending = null) else state.copy(pending = null)
        }
        val active = state.active?.takeIf(::usable)
        if (active == null || bundled.isNewerThan(active)) {
            val present = bundled.files.filter { entry -> storage.has(entry) || copyVerified(entry) }
            state = state.copy(active = bundled.copy(files = present), previous = active ?: state.previous?.takeIf(::usable))
        }
        if (state.previous?.dataVersion == state.active?.dataVersion) state = state.copy(previous = null)

        storage.writeState(state)
        storage.collectGarbage(state)
        return InstalledDataPack(storage, state.active!!)
    }

    private fun usable(manifest: DataPackManifest) = manifest.isSupported && storage.isComplete(manifest)

    /** Copies one bundled file; returns false when the APK does not contain it (e.g. a trimmed build). */
    private fun copyVerified(entry: DataPackManifest.Entry): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = try {
            assets.open("$assetDir/${entry.path}")
        } catch (_: FileNotFoundException) {
            return false
        }
        storage.writeAtomically(storage.objectFile(entry)) { tmp ->
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

    companion object {
        const val BUNDLED_ASSET_DIR = "datapack"
        const val BASEMAP_FILE = "basemap.pmtiles"
        const val CYCLE_NETWORK_FILE = "ciclovias.geojson"
        const val CYCLE_NETWORK_META_FILE = "ciclovias.meta.json"
        const val GRAPH_FILE = GraphFormat.FILE_NAME
        const val GRAPH_META_FILE = "graph.meta.json"
        const val SEARCH_FILE = SearchIndexFormat.FILE_NAME
        const val POIS_FILE = "pois.geojson"
        const val GIRA_FILE = "gira.geojson"
        const val GIRA_META_FILE = "gira.meta.json"
    }
}
