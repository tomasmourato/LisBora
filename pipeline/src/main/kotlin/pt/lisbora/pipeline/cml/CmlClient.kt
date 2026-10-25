package pt.lisbora.pipeline.cml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import pt.lisbora.pipeline.http.HttpGet
import java.time.Instant

/** Reads Lisbon's official cycle network ("Rede Ciclável") from the CML ArcGIS feature service. */
class CmlClient(
    private val http: HttpGet,
    private val serviceUrl: String = SERVICE_URL,
) {

    /** Last edit of the layer, as reported by the service (null if not exposed). */
    fun fetchLastEditDate(): Instant? {
        val info = Json.parseToJsonElement(http.getText("$serviceUrl?f=json")).jsonObject
        val millis = info["editingInfo"]?.jsonObject?.get("lastEditDate")?.jsonPrimitive?.longOrNull
        return millis?.let(Instant::ofEpochMilli)
    }

    /** All features as GeoJSON objects in WGS84, paging through the service's record limit. */
    fun fetchFeatures(pageSize: Int = 1000, maxPages: Int = 100): List<JsonObject> {
        val features = mutableListOf<JsonObject>()
        for (page in 0 until maxPages) {
            val url = "$serviceUrl/query?where=1%3D1&outFields=*&outSR=4326&f=geojson" +
                "&orderByFields=OBJECTID&resultOffset=${page * pageSize}&resultRecordCount=$pageSize"
            val body = Json.parseToJsonElement(http.getText(url)).jsonObject
            body["error"]?.let { error("ArcGIS error: $it") }
            val pageFeatures = body.getValue("features").jsonArray.map { it.jsonObject }
            features += pageFeatures
            val exceeded = body["properties"]?.jsonObject?.get("exceededTransferLimit")
                ?.jsonPrimitive?.booleanOrNull == true
            if (pageFeatures.isEmpty() || (pageFeatures.size < pageSize && !exceeded)) return features
        }
        error("CML service returned more than $maxPages pages; refusing to continue")
    }

    companion object {
        const val SERVICE_URL =
            "https://services.arcgis.com/1dSrzEWVQn5kHHyK/arcgis/rest/services/Ciclovias/FeatureServer/0"
        const val DATASET_URL = "https://dados.cm-lisboa.pt/dataset/rede-ciclavel"
    }
}
