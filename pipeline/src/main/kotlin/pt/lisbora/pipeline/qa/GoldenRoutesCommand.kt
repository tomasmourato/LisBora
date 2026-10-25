package pt.lisbora.pipeline.qa

import pt.lisbora.routing.Route
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/** `golden-routes`: checks the reference routes against an existing `graph.lbg`. */
class GoldenRoutesCommand(private val out: PrintStream) {

    fun run(graphFile: Path, configFile: Path, detailed: Boolean = false): Int {
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
        val failures = runner.graphFailures(config).onEach { out.println("FALHA $it") }.size
        val results = runner.run(config)
        results.forEach { r ->
            val route = r.computed
            val summary = route?.let {
                "%.1f km, %.0f min, %.0f%% infra, +%.0f m, máx %.1f%%, %d estados".format(
                    it.distanceM / 1000, it.durationS / 60, it.infraShare * 100, it.ascentM, it.maxGradePercent, it.settledStates,
                )
            } ?: "sem rota"
            out.println("${if (r.passed) "OK   " else "FALHA"} ${r.route.name}: $summary ${r.failures.joinToString("; ")}")
            if (detailed && route != null) printLegs(graph, route)
        }
        return if (failures == 0 && results.all { it.passed }) 0 else 3
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
                "    %-38s %-13s %-24s %-4s %6.0f m  %5.1f→%5.1f m  máx %5.1f%%  (%.5f, %.5f)".format(
                    (leg.name ?: "—").take(38), leg.flags.road, leg.infra, leg.mode, leg.lengthM, start, end, maxGrade * 100, p.lat, p.lon,
                ),
            )
        }
    }
}
