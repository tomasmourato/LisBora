package pt.lisbora.pipeline.qa

import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.io.Gpx
import pt.lisbora.routing.navigation.RouteInstructions
import pt.lisbora.routing.weighting.RoutingPreferences
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

/**
 * `route`: one route between two points with the app's default preferences, its turn-by-turn steps and,
 * optionally, a GPX file (to replay as GPS in the emulator, see `pipeline/tools/simulate-ride.py`).
 */
class RouteCommand(private val out: PrintStream) {

    fun run(graphFile: Path, from: String?, to: String?, gpxFile: Path?): Int {
        val start = from?.let(::parsePoint)
        val end = to?.let(::parsePoint)
        if (start == null || end == null) {
            out.println("ERRO: indica --de lat,lon e --para lat,lon.")
            return 2
        }
        if (!graphFile.exists()) {
            out.println("ERRO: falta $graphFile; corre primeiro build.")
            return 2
        }
        val engine = RoutingEngine(RoutingGraph(graphFile.inputStream().buffered().use(GraphFormat::read)))
        val route = when (val result = engine.route(start, end, RoutingPreferences())) {
            is RouteResult.Success -> result.route
            is RouteResult.Failure -> {
                out.println("Sem rota: ${result.reason}")
                return 3
            }
        }
        out.println("%.2f km, %.0f min, +%.0f m, %d pontos".format(route.distanceM / 1000, route.durationS / 60, route.ascentM, route.points.size))
        RouteInstructions.build(route).forEach { i ->
            out.println("  %6.0f m  %-12s %-6s %-6s %s".format(i.atM, i.maneuver, i.cycleway, i.walk, i.street.orEmpty()))
        }
        gpxFile?.let {
            it.writeText(Gpx.write("LisBora", route = route))
            out.println("Escrito $it")
        }
        return 0
    }

    private fun parsePoint(text: String): GeoPoint? {
        val (lat, lon) = text.split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 2 } ?: return null
        return runCatching { GeoPoint(lat, lon) }.getOrNull()
    }
}
