package pt.lisbora.pipeline.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.SearchHit
import pt.lisbora.routing.search.SearchIndex

/** A reference query: [expectName] must appear in a hit within [radiusM] of [at], ranked [maxRank] or better. */
data class GoldenSearch(val query: String, val expectName: String, val at: GeoPoint, val radiusM: Double, val maxRank: Int)

data class GoldenSearchResult(val search: GoldenSearch, val hits: List<SearchHit>, val rank: Int?) {
    val passed: Boolean get() = rank != null && rank <= search.maxRank
}

/** Reference searches from `pipeline/config/golden-searches.json`, all run from the same [near] position. */
data class GoldenSearchConfig(val near: GeoPoint, val searches: List<GoldenSearch>) {

    fun run(index: SearchIndex): List<GoldenSearchResult> = searches.map { s ->
        val hits = index.search(s.query, near)
        val rank = hits.indexOfFirst { hit ->
            hit.place.name.contains(s.expectName, ignoreCase = true) && hit.place.point.distanceTo(s.at) <= s.radiusM
        }.takeIf { it >= 0 }?.plus(1)
        GoldenSearchResult(s, hits, rank)
    }

    companion object {
        const val DEFAULT_FILE = "pipeline/config/golden-searches.json"

        fun parse(json: String): GoldenSearchConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            return GoldenSearchConfig(
                near = root.point("near"),
                searches = root.getValue("searches").jsonArray.map { element ->
                    val o = element.jsonObject
                    GoldenSearch(
                        query = o.getValue("query").jsonPrimitive.content,
                        expectName = o.getValue("expectName").jsonPrimitive.content,
                        at = o.point("at"),
                        radiusM = o["radiusM"]?.jsonPrimitive?.doubleOrNull ?: 500.0,
                        maxRank = o["maxRank"]?.jsonPrimitive?.intOrNull ?: 3,
                    )
                },
            )
        }

        private fun JsonObject.point(key: String): GeoPoint {
            val (lat, lon) = getValue(key).jsonArray.map { it.jsonPrimitive.double }
            return GeoPoint(lat, lon)
        }
    }
}
