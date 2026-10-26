package pt.lisbora.pipeline.qa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.weighting.RoutingPreferences

/** Expected properties of a reference route; null means "not checked". */
data class RouteExpectations(
    val reachable: Boolean = true,
    val minInfraShare: Double? = null,
    val minSegregatedShare: Double? = null,
    val minDurationMin: Double? = null,
    val maxDurationMin: Double? = null,
    val maxGradePercent: Double? = null,
    /** Steepest descent ridden on cobbles or tram rails (see [RouteAnalysis.steepestRoughDescentPercent]). */
    val maxRoughDescentPercent: Double? = null,
    val avoidNames: List<String> = emptyList(),
    val useNames: List<String> = emptyList(),
    val usesFerry: Boolean? = null,
)

data class GoldenRoute(
    val name: String,
    val from: GeoPoint,
    val to: GeoPoint,
    val preferences: RoutingPreferences,
    val expect: RouteExpectations,
)

/** Reference routes plus graph-wide checks, from `pipeline/config/golden-routes.json`. */
data class GoldenConfig(val routes: List<GoldenRoute>, val forbiddenEdgeNames: List<String>) {
    companion object {
        const val DEFAULT_FILE = "pipeline/config/golden-routes.json"

        fun parse(json: String): GoldenConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val routes = root["routes"]!!.jsonArray.map { element ->
                val o = element.jsonObject
                GoldenRoute(
                    name = o.string("name")!!,
                    from = o.point("from"),
                    to = o.point("to"),
                    preferences = o["preferences"]?.jsonObject?.let(::preferences) ?: RoutingPreferences(),
                    expect = o["expect"]?.jsonObject?.let(::expectations) ?: RouteExpectations(),
                )
            }
            val forbidden = root["forbiddenEdgeNames"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            return GoldenConfig(routes, forbidden)
        }

        private fun preferences(o: JsonObject) = RoutingPreferences(
            maxGradePercent = o.number("maxGradePercent"),
            avoidCobbles = o.flag("avoidCobbles") ?: false,
            avoidTramTracks = o.flag("avoidTramTracks") ?: false,
            preferSegregated = o.flag("preferSegregated") ?: false,
            allowFerries = o.flag("allowFerries") ?: true,
        )

        private fun expectations(o: JsonObject) = RouteExpectations(
            reachable = o.flag("reachable") ?: true,
            minInfraShare = o.number("minInfraShare"),
            minSegregatedShare = o.number("minSegregatedShare"),
            minDurationMin = o.number("minDurationMin"),
            maxDurationMin = o.number("maxDurationMin"),
            maxGradePercent = o.number("maxGradePercent"),
            maxRoughDescentPercent = o.number("maxRoughDescentPercent"),
            avoidNames = o.strings("avoidNames"),
            useNames = o.strings("useNames"),
            usesFerry = o.flag("usesFerry"),
        )

        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
        private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
        private fun JsonObject.strings(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        private fun JsonObject.point(key: String): GeoPoint {
            val (lat, lon) = this[key]!!.jsonArray.map { it.jsonPrimitive.double }
            return GeoPoint(lat, lon)
        }
    }
}

data class GoldenResult(val route: GoldenRoute, val computed: Route?, val failures: List<String>) {
    val passed: Boolean get() = failures.isEmpty()
}

/** Runs [GoldenConfig] against a graph and explains every failed expectation in Portuguese. */
class GoldenRouteRunner(private val graph: RoutingGraph) {
    private val engine = RoutingEngine(graph)

    /**
     * Edges usable by bike whose name is exactly one of the forbidden names (e.g. the bridge itself,
     * not "Acesso à Ponte 25 de Abril", an ordinary trunk road in OSM).
     */
    fun graphFailures(config: GoldenConfig): List<String> = config.forbiddenEdgeNames.flatMap { forbidden ->
        (0 until graph.edgeCount)
            .filter { e ->
                graph.name(e).equals(forbidden, ignoreCase = true) &&
                    (graph.flags(e).mode(false) != TravelMode.NONE || graph.flags(e).mode(true) != TravelMode.NONE)
            }
            .take(1)
            .map { e ->
                val p = graph.nodePoint(graph.data.edgeBase[e])
                "O grafo tem arestas acessíveis de bicicleta chamadas \"$forbidden\" (%.5f, %.5f).".format(p.lat, p.lon)
            }
    }

    fun run(config: GoldenConfig): List<GoldenResult> = config.routes.map(::check)

    fun check(golden: GoldenRoute): GoldenResult {
        val expect = golden.expect
        val result = engine.route(golden.from, golden.to, golden.preferences)
        if (result !is RouteResult.Success) {
            val failures = if (expect.reachable) listOf("Sem rota: ${(result as RouteResult.Failure).reason}") else emptyList()
            return GoldenResult(golden, null, failures)
        }
        val route = result.route
        val failures = mutableListOf<String>()
        if (!expect.reachable) failures += "Esperava-se que não houvesse rota."
        if (route.legs.any { it.flags.isSteps }) failures += "Usa escadas."
        val minutes = route.durationS / 60
        expect.minInfraShare?.let { if (route.infraShare < it) failures += "Infraestrutura ciclável %.0f%% < %.0f%%".format(route.infraShare * 100, it * 100) }
        expect.minSegregatedShare?.let {
            val share = (route.lengthByInfra[EdgeInfra.SEGREGATED_TRACK] ?: 0.0) / route.distanceM
            if (share < it) failures += "Pista segregada %.0f%% < %.0f%%".format(share * 100, it * 100)
        }
        expect.minDurationMin?.let { if (minutes < it) failures += "Duração %.1f min < %.0f min".format(minutes, it) }
        expect.maxDurationMin?.let { if (minutes > it) failures += "Duração %.1f min > %.0f min".format(minutes, it) }
        expect.maxGradePercent?.let { if (route.maxGradePercent > it) failures += "Inclinação máxima %.1f%% > %.0f%%".format(route.maxGradePercent, it) }
        expect.maxRoughDescentPercent?.let {
            val descent = RouteAnalysis.steepestRoughDescentPercent(route)
            if (descent > it) failures += "Descida em calçada ou carris de %.1f%% > %.0f%%".format(descent, it)
        }
        val names = route.legs.mapNotNull { it.name }.toSet()
        expect.avoidNames.forEach { avoid -> if (names.any { it.contains(avoid, ignoreCase = true) }) failures += "Passa por \"$avoid\"." }
        expect.useNames.forEach { use -> if (names.none { it.contains(use, ignoreCase = true) }) failures += "Não passa por \"$use\"." }
        expect.usesFerry?.let { if (route.legs.any { leg -> leg.flags.isFerry } != it) failures += if (it) "Não usa o barco." else "Usa o barco." }
        return GoldenResult(golden, route, failures)
    }

    companion object {
        /** All computed routes as a GeoJSON FeatureCollection, for inspection in a GIS. */
        fun toGeoJson(results: List<GoldenResult>): JsonObject = buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                results.forEach { r ->
                    val route = r.computed ?: return@forEach
                    add(buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("properties") {
                            put("name", r.route.name)
                            put("passed", r.passed)
                            put("distance_m", Math.round(route.distanceM))
                            put("duration_min", Math.round(route.durationS / 6) / 10.0)
                            put("infra_share", Math.round(route.infraShare * 100) / 100.0)
                            put("ascent_m", Math.round(route.ascentM))
                            put("max_grade_pct", Math.round(route.maxGradePercent * 10) / 10.0)
                            put("failures", JsonArray(r.failures.map(::JsonPrimitive)))
                        }
                        putJsonObject("geometry") {
                            put("type", "LineString")
                            put("coordinates", JsonArray(route.points.map { p -> JsonArray(listOf(JsonPrimitive(p.lon), JsonPrimitive(p.lat))) }))
                        }
                    })
                }
            }
        }
    }
}
