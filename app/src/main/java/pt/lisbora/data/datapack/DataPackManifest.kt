package pt.lisbora.data.datapack

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

/** `manifest.json` of a data pack, as written by the pipeline (`PackManifest`, and `release` for published packs). */
data class DataPackManifest(
    val formatVersion: Int,
    val files: List<Entry>,
    /** When the pack's content last changed ("20261102T043012Z"); a later version is newer data. */
    val dataVersion: String = "",
    /** Versions of the binary formats inside ("graph.lbg" to 1). */
    val formats: Map<String, Int> = emptyMap(),
) {

    /** [url]: where a published pack's file is downloaded from, relative to its manifest. */
    data class Entry(val path: String, val size: Long, val sha256: String, val url: String? = null)

    fun entry(path: String): Entry? = files.firstOrNull { it.path == path }

    /** This app can read every file of the pack. */
    val isSupported: Boolean
        get() = formatVersion == SUPPORTED_FORMAT_VERSION && formats.all { (path, version) -> SUPPORTED_FORMATS[path]?.let { it == version } ?: true }

    /** Newer data than [other]; a pack without a version (written before versions existed) is the oldest. */
    fun isNewerThan(other: DataPackManifest?): Boolean = other == null || dataVersion > other.dataVersion

    fun toJson(): JsonObject = buildJsonObject {
        put("formatVersion", formatVersion)
        put("dataVersion", dataVersion)
        putJsonObject("formats") { formats.forEach { (path, version) -> put(path, version) } }
        putJsonArray("files") {
            files.forEach { f ->
                addJsonObject {
                    put("path", f.path)
                    put("size", f.size)
                    put("sha256", f.sha256)
                    f.url?.let { put("url", it) }
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "manifest.json"
        const val SUPPORTED_FORMAT_VERSION = 1

        /** The binary formats this app reads, from the same `:core:routing` code that writes them. */
        val SUPPORTED_FORMATS = mapOf(
            GraphFormat.FILE_NAME to GraphFormat.FORMAT_VERSION,
            SearchIndexFormat.FILE_NAME to SearchIndexFormat.FORMAT_VERSION,
        )

        fun parse(json: String): DataPackManifest = parse(Json.parseToJsonElement(json).jsonObject)

        fun parse(root: JsonObject): DataPackManifest = DataPackManifest(
            formatVersion = root.getValue("formatVersion").jsonPrimitive.int,
            files = root.getValue("files").jsonArray.map { element ->
                val file = element.jsonObject
                Entry(
                    path = file.getValue("path").jsonPrimitive.content,
                    size = file.getValue("size").jsonPrimitive.long,
                    sha256 = file.getValue("sha256").jsonPrimitive.content,
                    url = file["url"]?.jsonPrimitive?.contentOrNull,
                )
            },
            dataVersion = root["dataVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            formats = root["formats"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.int },
        )
    }
}
