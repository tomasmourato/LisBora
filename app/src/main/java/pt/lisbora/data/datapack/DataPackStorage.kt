package pt.lisbora.data.datapack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException

/**
 * Which data packs the phone has: the one in use, the one before it (to go back to if the new one fails),
 * one downloaded and waiting for the next start, and the versions that failed here and are never installed again.
 */
data class PackState(
    val active: DataPackManifest? = null,
    val previous: DataPackManifest? = null,
    val pending: DataPackManifest? = null,
    val rejected: Set<String> = emptySet(),
    /** The active pack failed to load: the next start goes back to the previous one. */
    val broken: Boolean = false,
    /** When the phone last asked for a newer pack (epoch ms). */
    val checkedAt: Long? = null,
) {
    /** The newest pack the phone has, in use or waiting. */
    val newest: DataPackManifest? get() = pending ?: active

    fun toJson(): JsonObject = buildJsonObject {
        active?.let { put("active", it.toJson()) }
        previous?.let { put("previous", it.toJson()) }
        pending?.let { put("pending", it.toJson()) }
        putJsonArray("rejected") { rejected.sorted().forEach { add(JsonPrimitive(it)) } }
        put("broken", broken)
        checkedAt?.let { put("checkedAt", it) }
    }

    companion object {
        fun parse(json: String): PackState {
            val root = Json.parseToJsonElement(json).jsonObject
            fun pack(key: String) = root[key]?.jsonObject?.let(DataPackManifest::parse)
            return PackState(
                active = pack("active"),
                previous = pack("previous"),
                pending = pack("pending"),
                rejected = root["rejected"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet(),
                broken = root["broken"]?.jsonPrimitive?.booleanOrNull ?: false,
                checkedAt = root["checkedAt"]?.jsonPrimitive?.longOrNull,
            )
        }
    }
}

/**
 * The data packs on disk, under [root] (`filesDir/datapack`):
 * - `objects/`: each file once, named by its content (`graph.625e72943f43be77.lbg`), so packs share
 *   the files they have in common and a file in use is never overwritten;
 * - `staging/`: downloads in progress;
 * - `state.json`: the [PackState]. Switching packs is one atomic write of this file.
 *
 * Not thread-safe: [DataPackStore] serialises access.
 */
class DataPackStorage(val root: File) {

    private val objects = File(root, "objects")
    private val staging = File(root, "staging")
    private val stateFile = File(root, STATE_FILE)

    fun objectFile(entry: DataPackManifest.Entry) = File(objects, objectName(entry))

    fun stagingFile(entry: DataPackManifest.Entry) = File(staging, objectName(entry))

    /** The file is in place (checked against its SHA-256 when it was written). */
    fun has(entry: DataPackManifest.Entry): Boolean = objectFile(entry).let { it.isFile && it.length() == entry.size }

    fun isComplete(manifest: DataPackManifest): Boolean = manifest.files.all(::has)

    /** Creates the folders; files of the layout before versioned packs (directly under [root]) are removed. */
    fun prepare() {
        objects.mkdirs()
        staging.mkdirs()
        root.listFiles()?.filter { it.isFile && it.name != STATE_FILE }?.forEach { it.delete() }
    }

    fun readState(): PackState = stateFile.takeIf { it.isFile }?.let { runCatching { PackState.parse(it.readText()) }.getOrNull() } ?: PackState()

    @Throws(IOException::class)
    fun writeState(state: PackState) = writeAtomically(stateFile) { it.writeText(state.toJson().toString()) }

    /** Moves a verified download into place. */
    @Throws(IOException::class)
    fun commit(entry: DataPackManifest.Entry) = move(stagingFile(entry), objectFile(entry))

    /** Removes the files no pack of [state] uses; downloads in progress are left alone. */
    fun collectGarbage(state: PackState) {
        val used = listOfNotNull(state.active, state.previous, state.pending).flatMap { it.files }.map(::objectName).toSet()
        objects.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    /** Removes downloads that are not part of [keep]. */
    fun clearStaging(keep: Collection<DataPackManifest.Entry>) {
        val names = keep.map(::objectName).toSet()
        staging.listFiles()?.filter { it.name.removeSuffix(PART_SUFFIX) !in names }?.forEach { it.delete() }
    }

    fun partFile(entry: DataPackManifest.Entry) = File(staging, objectName(entry) + PART_SUFFIX)

    /** Writes [target] through a temporary file, so a crash never leaves a half-written file in its place. */
    @Throws(IOException::class)
    fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            write(tmp)
            move(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    private fun move(from: File, to: File) {
        if (!from.renameTo(to)) {
            to.delete()
            if (!from.renameTo(to)) throw IOException("Could not move ${from.name} into place")
        }
    }

    companion object {
        const val STATE_FILE = "state.json"
        private const val PART_SUFFIX = ".part"

        /** "graph.lbg" → "graph.625e72943f43be77.lbg", like the published file names. */
        fun objectName(entry: DataPackManifest.Entry): String {
            val dot = entry.path.lastIndexOf('.')
            val hash = entry.sha256.take(16)
            return if (dot <= 0) "${entry.path}.$hash" else "${entry.path.substring(0, dot)}.$hash${entry.path.substring(dot)}"
        }
    }
}
