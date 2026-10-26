package pt.lisbora.pipeline.qa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryRequest
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.weighting.RoutingPreferences
import kotlin.math.abs

/** Expected properties of the best route of a reference discovery; null means "not checked". */
data class DiscoveryExpectations(
    val minRoutes: Int = 2,
    /** Largest |duration / time asked − 1|. */
    val maxTimeError: Double = 0.15,
    val minRiversideShare: Double? = null,
    val minGreenShare: Double? = null,
    val minInfraShare: Double? = null,
    val minCalmShare: Double? = null,
    val minViewpoints: Int? = null,
    /** Museums, monuments and attractions passed. */
    val minCulture: Int? = null,
    val minAscentPerKm: Double? = null,
    val maxAscentPerKm: Double? = null,
    val maxGradePercent: Double? = null,
    /** Share of the distance on streets the route already took. */
    val maxRepeatedShare: Double = 0.2,
)

data class GoldenDiscovery(val name: String, val request: DiscoveryRequest, val expect: DiscoveryExpectations)

/** Reference discoveries from `pipeline/config/golden-discoveries.json`. */
object GoldenDiscoveries {
    const val DEFAULT_FILE = "pipeline/config/golden-discoveries.json"

    fun parse(json: String): List<GoldenDiscovery> =
        Json.parseToJsonElement(json).jsonObject["discoveries"]!!.jsonArray.map { element ->
            val o = element.jsonObject
            GoldenDiscovery(
                name = o["name"]!!.jsonPrimitive.content,
                request = DiscoveryRequest(
                    start = o.point("start")!!,
                    end = o.point("end"),
                    durationS = o["minutes"]!!.jsonPrimitive.double * 60,
                    purpose = DiscoveryPurpose.valueOf(o["purpose"]!!.jsonPrimitive.content),
                    // GIRA bikes may only be ridden in Lisbon.
                    preferences = RoutingPreferences(stayInLisbon = o["gira"]?.jsonPrimitive?.booleanOrNull == true),
                ),
                expect = o["expect"]?.jsonObject?.let(::expectations) ?: DiscoveryExpectations(),
            )
        }

    private fun expectations(o: JsonObject): DiscoveryExpectations {
        fun number(key: String) = o[key]?.jsonPrimitive?.doubleOrNull
        val defaults = DiscoveryExpectations()
        return DiscoveryExpectations(
            minRoutes = number("minRoutes")?.toInt() ?: defaults.minRoutes,
            maxTimeError = number("maxTimeError") ?: defaults.maxTimeError,
            minRiversideShare = number("minRiversideShare"),
            minGreenShare = number("minGreenShare"),
            minInfraShare = number("minInfraShare"),
            minCalmShare = number("minCalmShare"),
            minViewpoints = number("minViewpoints")?.toInt(),
            minCulture = number("minCulture")?.toInt(),
            minAscentPerKm = number("minAscentPerKm"),
            maxAscentPerKm = number("maxAscentPerKm"),
            maxGradePercent = number("maxGradePercent"),
            maxRepeatedShare = number("maxRepeatedShare") ?: defaults.maxRepeatedShare,
        )
    }

    private fun JsonObject.point(key: String): GeoPoint? = this[key]?.jsonArray?.map { it.jsonPrimitive.double }?.let { (lat, lon) -> GeoPoint(lat, lon) }

    /** Why the discovery does not meet [expect], in Portuguese; empty when it does. */
    fun failures(golden: GoldenDiscovery, routes: List<DiscoveredRoute>): List<String> {
        val expect = golden.expect
        val best = routes.firstOrNull() ?: return listOf("Sem rotas.")
        val route = best.route
        val h = best.highlights
        val km = route.distanceM / 1000
        val error = route.durationS / golden.request.durationS - 1
        return buildList {
            if (routes.size < expect.minRoutes) add("Só ${routes.size} rotas (mínimo ${expect.minRoutes}).")
            if (abs(error) > expect.maxTimeError) add("Duração %+.0f%% do pedido (máximo ±%.0f%%).".format(error * 100, expect.maxTimeError * 100))
            expect.minRiversideShare?.let { if (h.riversideM / route.distanceM < it) add("Junto ao rio %.0f%% (mínimo %.0f%%).".format(h.riversideM / route.distanceM * 100, it * 100)) }
            expect.minGreenShare?.let { if (h.greenM / route.distanceM < it) add("Em parques %.0f%% (mínimo %.0f%%).".format(h.greenM / route.distanceM * 100, it * 100)) }
            expect.minInfraShare?.let { if (h.infraShare < it) add("Infraestrutura %.0f%% (mínimo %.0f%%).".format(h.infraShare * 100, it * 100)) }
            expect.minCalmShare?.let { if (h.calmShare < it) add("Ruas calmas %.0f%% (mínimo %.0f%%).".format(h.calmShare * 100, it * 100)) }
            expect.minViewpoints?.let { if (h.count(PoiKind.VIEWPOINT) < it) add("${h.count(PoiKind.VIEWPOINT)} miradouros (mínimo $it).") }
            expect.minCulture?.let {
                val culture = h.pois.count { p -> p.kind in CULTURE }
                if (culture < it) add("$culture museus e monumentos (mínimo $it).")
            }
            expect.minAscentPerKm?.let { if (route.ascentM / km < it) add("Sobe %.0f m/km (mínimo %.0f).".format(route.ascentM / km, it)) }
            expect.maxAscentPerKm?.let { if (route.ascentM / km > it) add("Sobe %.0f m/km (máximo %.0f).".format(route.ascentM / km, it)) }
            expect.maxGradePercent?.let { if (route.maxGradePercent > it) add("Inclinação máxima %.1f%% (máximo %.0f%%).".format(route.maxGradePercent, it)) }
            if (golden.request.preferences.stayInLisbon) {
                val outside = routes.sumOf { r -> r.route.legs.filterNot { it.flags.lisbon }.sumOf { it.lengthM } }
                if (outside > 0) add("Sai de Lisboa em %.0f m (GIRA).".format(outside))
            }
            val repeated = repeatedShare(best)
            if (repeated > expect.maxRepeatedShare) add("Repete %.0f%% do percurso (máximo %.0f%%).".format(repeated * 100, expect.maxRepeatedShare * 100))
        }
    }

    /** Share of the distance on edges already taken earlier in the route. */
    fun repeatedShare(route: DiscoveredRoute): Double {
        val seen = HashSet<Int>()
        var previous = -1
        var repeated = 0.0
        route.route.legs.forEach { leg ->
            if (leg.edge != previous && !seen.add(leg.edge)) repeated += leg.lengthM
            previous = leg.edge
        }
        return repeated / route.route.distanceM.coerceAtLeast(1.0)
    }

    val CULTURE = setOf(PoiKind.MUSEUM, PoiKind.MONUMENT, PoiKind.ATTRACTION)
}
