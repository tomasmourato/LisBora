package pt.lisbora.data.datapack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.routing.model.CycleInfraClass
import java.time.Instant

/** Summary of the bundled CML cycle network (`ciclovias.meta.json`), shown in the map legend. */
data class CycleNetworkMeta(
    val attribution: String,
    val sourceLastEdit: Instant?,
    val lengthKmByClass: Map<CycleInfraClass, Double>,
) {
    companion object {
        fun parse(json: String): CycleNetworkMeta {
            val root = Json.parseToJsonElement(json).jsonObject
            val lengths = root["lengthKmByClass"]?.jsonObject.orEmpty()
            return CycleNetworkMeta(
                attribution = root["attribution"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sourceLastEdit = root["sourceLastEdit"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                lengthKmByClass = lengths.mapNotNull { (id, value) ->
                    val infraClass = CycleInfraClass.fromId(id) ?: return@mapNotNull null
                    value.jsonPrimitive.doubleOrNull?.let { infraClass to it }
                }.toMap(),
            )
        }
    }
}
