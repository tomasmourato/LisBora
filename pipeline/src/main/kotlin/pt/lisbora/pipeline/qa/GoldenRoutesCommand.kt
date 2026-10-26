package pt.lisbora.pipeline.qa

import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.random.Random

/** `golden-routes`: checks the reference routes against an existing `graph.lbg`. */
class GoldenRoutesCommand(private val out: PrintStream) {

    private companion object {
        const val SAMPLE_SEED = 20261026
    }

    fun run(graphFile: Path, configFile: Path, detailed: Boolean = false, sample: Int = 0): Int {
        if (!graphFile.exists()) {
            out.println("ERRO: falta $graphFile; corre primeiro build.")
            return 2
        }
        val started = System.nanoTime()
        val graph = RoutingGraph(graphFile.inputStream().buffered().use(GraphFormat::read))
        out.println("Grafo ${graph.data.dataVersion}: ${graph.nodeCount} nós, ${graph.edgeCount} arestas " +
            "(carregado em ${(System.nanoTime() - started) / 1_000_000} ms)")
        val config = GoldenConfig.parse(configFile.readText())
        val runner = GoldenRouteRunner(graph)
        val engine = RoutingEngine(graph)
        val failures = runner.graphFailures(config).onEach { out.println("FALHA $it") }.size
        val results = runner.run(config)
        results.forEach { r ->
            val route = r.computed
            val summary = route?.let {
                "%.1f km, %.0f min, %.0f%% infra, +%.0f m, máx %.1f%%, descida em calçada/carris %.1f%%, %d estados".format(
                    it.distanceM / 1000, it.durationS / 60, it.infraShare * 100, it.ascentM, it.maxGradePercent,
                    RouteAnalysis.steepestRoughDescentPercent(it), it.settledStates,
                )
            } ?: "sem rota"
            out.println("${if (r.passed) "OK   " else "FALHA"} ${r.route.name}: $summary ${r.failures.joinToString("; ")}")
            if (route != null) printAlternatives(engine, r.route)
            if (detailed && route != null) printLegs(graph, route)
        }
        if (sample > 0) printSample(engine, sample)
        return if (failures == 0 && results.all { it.passed }) 0 else 3
    }

    /**
     * [count] random trips of 1 to 5 km in central Lisbon (fixed seed, so runs are comparable): how long they
     * take and how often they ride down steep cobbles or tram rails. Used to check weighting changes.
     */
    private fun printSample(engine: RoutingEngine, count: Int) {
        val random = Random(SAMPLE_SEED)
        val routes = mutableListOf<Route>()
        var attempts = 0
        while (routes.size < count && attempts < count * 10) {
            attempts++
            val from = GeoPoint(random.nextDouble(38.700, 38.760), random.nextDouble(-9.200, -9.100))
            val to = GeoPoint(random.nextDouble(38.700, 38.760), random.nextDouble(-9.200, -9.100))
            if (from.distanceTo(to) !in 1_000.0..5_000.0) continue
            (engine.route(from, to) as? RouteResult.Success)?.let { routes += it.route }
        }
        val descents = routes.map(RouteAnalysis::steepestRoughDescentPercent)
        fun share(limit: Double) = descents.count { it > limit } * 100.0 / routes.size
        out.println(
            "Amostra de %d viagens: %.1f min e %.2f km em média, %.0f%% com infraestrutura; descidas em calçada/carris acima de 8%%: %.0f%%, acima de 12%%: %.0f%%, acima de 16%%: %.0f%%".format(
                routes.size, routes.map { it.durationS / 60 }.average(), routes.map { it.distanceM / 1000 }.average(),
                routes.map { it.infraShare * 100 }.average(), share(8.0), share(12.0), share(16.0),
            ),
        )
    }

    /** The alternatives the app would offer for the same trip, with the time the search took. */
    private fun printAlternatives(engine: RoutingEngine, golden: GoldenRoute) {
        val started = System.nanoTime()
        val result = engine.route(golden.from, golden.to, golden.preferences, maxAlternatives = 2)
        val millis = (System.nanoTime() - started) / 1_000_000
        val alternatives = (result as? RouteResult.Success)?.alternatives.orEmpty()
        val summary = alternatives.joinToString("; ") {
            "%.1f km, %.0f min, %.0f%% infra, +%.0f m, calçada/carris %.0f%%".format(
                it.distanceM / 1000, it.durationS / 60, it.infraShare * 100, it.ascentM, RouteAnalysis.steepestRoughDescentPercent(it),
            )
        }
        out.println("      alternativas (${alternatives.size}, $millis ms): ${summary.ifEmpty { "nenhuma" }}")
    }

    /** One line per leg: name, way type, infrastructure, mode, length, elevations and steepest grade. */
    private fun printLegs(graph: RoutingGraph, route: Route) {
        route.legs.forEach { leg ->
            val start = route.elevations[leg.firstPoint]
            val end = route.elevations.getOrElse(route.legs.getOrNull(route.legs.indexOf(leg) + 1)?.firstPoint ?: route.elevations.lastIndex) { start }
            var maxGrade = 0.0
            for (i in 0 until graph.segmentCount(leg.edge)) {
                val grade = graph.segmentGrade(leg.edge, i).let { if (leg.reverse) -it else it }
                maxGrade = maxOf(maxGrade, grade)
            }
            val p = route.points[leg.firstPoint]
            out.println(
                "    %-38s %-13s %-24s %-13s %-4s %6.0f m  %5.1f→%5.1f m  máx %5.1f%%  (%.5f, %.5f)".format(
                    (leg.name ?: "—").take(38), leg.flags.road, leg.infra,
                    leg.flags.surface.name + if (leg.flags.tram) "+TRAM" else "",
                    leg.mode, leg.lengthM, start, end, maxGrade * 100, p.lat, p.lon,
                ),
            )
        }
    }
}
