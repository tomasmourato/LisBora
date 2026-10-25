package pt.lisbora.pipeline.cml

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.model.CycleInfraClass
import java.text.Normalizer
import kotlin.math.roundToLong

/** Why a CML feature was left out of the normalised network. */
enum class Rejection { NOT_BUILT, NO_GEOMETRY, UNKNOWN_CLASS }

sealed interface NormalizeResult {
    data class Accepted(val feature: JsonObject, val infraClass: CycleInfraClass, val lengthM: Double) : NormalizeResult
    data class Rejected(val reason: Rejection, val detail: String) : NormalizeResult
}

/**
 * Turns raw CML features into LisBora's cycle-network GeoJSON: one infrastructure class per segment,
 * English property keys, coordinates rounded to 1e-6° (~0.1 m) and a geodesic length.
 */
object CmlNormalizer {

    fun classify(typology: String?, segregation: String?): CycleInfraClass? {
        val t = fold(typology)
        val s = fold(segregation)
        val physicallySeparated = "separacao fisica" in s
        return when {
            t.startsWith("pista ciclavel") -> CycleInfraClass.SEGREGATED_TRACK
            t == "contrassentido" ->
                if (physicallySeparated) CycleInfraClass.SEGREGATED_TRACK else CycleInfraClass.PAINTED_LANE
            t.startsWith("faixa ciclavel") -> CycleInfraClass.PAINTED_LANE
            t == "30+bici" || t.startsWith("zona de coexistencia") -> CycleInfraClass.SHARED_STREET_30
            t.startsWith("percurso ciclopedonal") -> CycleInfraClass.SHARED_WITH_PEDESTRIANS
            t == "trilho" -> CycleInfraClass.TRAIL
            // Unknown typology: fall back on the segregation level alone.
            physicallySeparated -> CycleInfraClass.SEGREGATED_TRACK
            "separacao visual" in s -> CycleInfraClass.PAINTED_LANE
            "coexistencia" in s -> CycleInfraClass.SHARED_STREET_30
            else -> null
        }
    }

    fun normalize(raw: JsonObject): NormalizeResult {
        val props = raw["properties"]?.jsonObject ?: JsonObject(emptyMap())
        fun text(key: String): String? = props[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }

        val situation = text("SITUACAO")
        if (situation != null && fold(situation) != "executado") {
            return NormalizeResult.Rejected(Rejection.NOT_BUILT, "SITUACAO=$situation")
        }
        val geometry = raw["geometry"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: return NormalizeResult.Rejected(Rejection.NO_GEOMETRY, "OBJECTID=${text("OBJECTID")}")

        val typology = text("TIPOLOGIA")
        val segregation = text("NIVEL_SEGREGACAO")
        val infraClass = classify(typology, segregation)
            ?: return NormalizeResult.Rejected(Rejection.UNKNOWN_CLASS, "TIPOLOGIA=$typology / NIVEL_SEGREGACAO=$segregation")

        val lines = lineStrings(geometry)
        if (lines.isEmpty() || lines.all { it.size < 2 }) {
            return NormalizeResult.Rejected(Rejection.NO_GEOMETRY, "empty geometry, OBJECTID=${text("OBJECTID")}")
        }
        val lengthM = lines.sumOf { line -> line.zipWithNext { a, b -> a.distanceTo(b) }.sum() }

        val feature = buildJsonObject {
            put("type", "Feature")
            put("geometry", roundGeometry(geometry))
            put("properties", buildJsonObject {
                put("id", text("GlobalID")?.trim('{', '}') ?: text("OBJECTID"))
                put("class", infraClass.id)
                put("typology", typology)
                put("segregation", segregation)
                put("name", text("DESIGNACAO") ?: text("EIXO"))
                put("parish", text("FREGUESIA"))
                put("year", text("ANO"))
                put("length_m", (lengthM * 10).roundToLong() / 10.0)
                put("contraflow", fold(typology) == "contrassentido")
            })
        }
        return NormalizeResult.Accepted(feature, infraClass, lengthM)
    }

    /** Lower-case, accent-free, single-spaced text for robust comparisons. */
    internal fun fold(value: String?): String =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun lineStrings(geometry: JsonObject): List<List<GeoPoint>> {
        val coords = geometry["coordinates"]?.jsonArray ?: return emptyList()
        fun line(array: JsonArray) = array.map { position ->
            val p = position.jsonArray
            GeoPoint(lat = p[1].jsonPrimitive.doubleOrNull!!, lon = p[0].jsonPrimitive.doubleOrNull!!)
        }
        return when (geometry["type"]?.jsonPrimitive?.contentOrNull) {
            "LineString" -> listOf(line(coords))
            "MultiLineString" -> coords.map { line(it.jsonArray) }
            else -> emptyList()
        }
    }

    private fun roundGeometry(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key == "coordinates") roundGeometry(value) else value
        })
        is JsonArray -> JsonArray(element.map(::roundGeometry))
        is JsonPrimitive -> element.doubleOrNull?.let { JsonPrimitive((it * 1e6).roundToLong() / 1e6) } ?: element
        JsonNull -> element
    }
}
