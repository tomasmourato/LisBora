package pt.lisbora.pipeline.cml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pt.lisbora.routing.model.CycleInfraClass

class CmlNormalizerTest {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Pista Ciclável Bidirecional  | Pista ciclável (separação física)  | SEGREGATED_TRACK",
            "Pista Ciclável Unidirecional | Pista ciclável (separação física)  | SEGREGATED_TRACK",
            "Contrassentido               | Pista ciclável (separação física)  | SEGREGATED_TRACK",
            "Contrassentido               | Faixa ciclável (separação visual)  | PAINTED_LANE",
            "Faixa Ciclável               | Faixa ciclável (separação visual)  | PAINTED_LANE",
            "30+Bici                      | Via banalizada (coexistência)      | SHARED_STREET_30",
            "Zona de Coexistência         | Via banalizada (coexistência)      | SHARED_STREET_30",
            "Percurso Ciclopedonal        | Via banalizada (coexistência)      | SHARED_WITH_PEDESTRIANS",
            "Trilho                       | Via banalizada (coexistência)      | TRAIL",
            "Nova Tipologia               | Pista ciclável (separação física)  | SEGREGATED_TRACK",
            "PISTA CICLAVEL BIDIRECIONAL  | ''                                 | SEGREGATED_TRACK",
        ],
    )
    fun `classifies every CML typology`(typology: String, segregation: String, expected: CycleInfraClass) {
        assertEquals(expected, CmlNormalizer.classify(typology, segregation))
    }

    @Test
    fun `unknown typology without a segregation level is not classified`() {
        assertNull(CmlNormalizer.classify("Algo novo", null))
    }

    @Test
    fun `normalizes properties, rounds coordinates and measures length`() {
        val result = CmlNormalizer.normalize(rawFeature())
        assertInstanceOf(NormalizeResult.Accepted::class.java, result)
        result as NormalizeResult.Accepted

        val props = result.feature["properties"]!!.jsonObject
        assertEquals("a6fae945-f407-45a9-ae5c-10025c404d3e", props["id"]!!.jsonPrimitive.content)
        assertEquals("shared30", props["class"]!!.jsonPrimitive.content)
        assertEquals("Rua de Nossa Senhora do Amparo", props["name"]!!.jsonPrimitive.content)
        assertEquals("Benfica", props["parish"]!!.jsonPrimitive.content)
        assertEquals(false, props["contraflow"]!!.jsonPrimitive.boolean)

        val firstPoint = result.feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(-9.203547, firstPoint[0].jsonPrimitive.double)
        assertEquals(38.749713, firstPoint[1].jsonPrimitive.double)

        // Two points ~100 m apart along a parallel.
        assertEquals(100.0, result.lengthM, 2.0)
        assertEquals(result.lengthM, props["length_m"]!!.jsonPrimitive.double, 0.05)
    }

    @Test
    fun `rejects segments that are not built yet`() {
        val result = CmlNormalizer.normalize(rawFeature(situation = "Em projeto"))
        assertEquals(Rejection.NOT_BUILT, (result as NormalizeResult.Rejected).reason)
    }

    @Test
    fun `rejects features without geometry`() {
        val result = CmlNormalizer.normalize(rawFeature(geometry = "null"))
        assertEquals(Rejection.NO_GEOMETRY, (result as NormalizeResult.Rejected).reason)
    }

    @Test
    fun `accepts multi line strings`() {
        val multi = """{"type":"MultiLineString","coordinates":[[[-9.2,38.75],[-9.199,38.75]],[[-9.19,38.75],[-9.189,38.75]]]}"""
        val result = CmlNormalizer.normalize(rawFeature(geometry = multi)) as NormalizeResult.Accepted
        assertEquals(174.0, result.lengthM, 2.0)
    }

    @Test
    fun `fold removes accents, case and extra spaces`() {
        assertEquals("zona de coexistencia", CmlNormalizer.fold("  Zona  de Coexistência "))
    }

    companion object {
        fun rawFeature(
            typology: String = "30+Bici",
            situation: String = "Executado",
            geometry: String = """{"type":"LineString","coordinates":[[-9.2035471894998,38.7497129896501],[-9.20239,38.7497129896501]]}""",
        ): JsonObject = Json.parseToJsonElement(
            """
            {"type":"Feature","id":146890,"geometry":$geometry,
             "properties":{"OBJECTID":146890,"DESIGNACAO":"Rua de Nossa Senhora do Amparo",
               "TIPOLOGIA":"$typology","NIVEL_SEGREGACAO":"Via banalizada (coexistência)",
               "SITUACAO":"$situation","ANO":"2025","FREGUESIA":"Benfica",
               "GlobalID":"a6fae945-f407-45a9-ae5c-10025c404d3e"}}
            """.trimIndent(),
        ).jsonObject
    }
}
