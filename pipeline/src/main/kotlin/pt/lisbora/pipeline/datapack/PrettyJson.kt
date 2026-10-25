package pt.lisbora.pipeline.datapack

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** JSON encoders shared by the pipeline: pretty for small metadata, compact for large GeoJSON. */
object PrettyJson {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    fun encodeCompact(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)
}
