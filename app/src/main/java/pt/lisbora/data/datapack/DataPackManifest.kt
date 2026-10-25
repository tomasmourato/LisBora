package pt.lisbora.data.datapack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** `manifest.json` of a data pack, as written by the pipeline (`PackManifest`). */
data class DataPackManifest(val formatVersion: Int, val files: List<Entry>) {

    data class Entry(val path: String, val size: Long, val sha256: String)

    fun entry(path: String): Entry? = files.firstOrNull { it.path == path }

    companion object {
        const val FILE_NAME = "manifest.json"
        const val SUPPORTED_FORMAT_VERSION = 1

        fun parse(json: String): DataPackManifest {
            val root = Json.parseToJsonElement(json).jsonObject
            return DataPackManifest(
                formatVersion = root.getValue("formatVersion").jsonPrimitive.int,
                files = root.getValue("files").jsonArray.map { element ->
                    val file = element.jsonObject
                    Entry(
                        path = file.getValue("path").jsonPrimitive.content,
                        size = file.getValue("size").jsonPrimitive.long,
                        sha256 = file.getValue("sha256").jsonPrimitive.content,
                    )
                },
            )
        }
    }
}
