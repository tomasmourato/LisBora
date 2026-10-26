package pt.lisbora.feature.planner

import androidx.annotation.StringRes
import pt.lisbora.R
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode

/** What sets an alternative apart, shown as a chip on its card. */
enum class RouteLabel(@param:StringRes val textRes: Int) {
    RECOMMENDED(R.string.route_label_recommended),
    FASTEST(R.string.route_label_fastest),
    MOST_INFRA(R.string.route_label_most_infra),
    FLATTEST(R.string.route_label_flattest),
}

/** A computed route with everything its card and details need. */
data class RouteOption(
    val route: Route,
    val hazards: List<RouteHazard>,
    /** Metres ridden at traffic stress 1..4. */
    val stressLengths: List<Double>,
    val labels: List<RouteLabel>,
    /** The route climbs more steeply than the rider's maximum (it was the best the network allowed). */
    val aboveMaxGrade: Boolean,
) {
    val distanceM: Double get() = route.distanceM
    val durationS: Double get() = route.durationS

    /** Share of the distance on each kind of infrastructure, largest first; walking is shown apart. */
    val infraShares: List<Pair<EdgeInfra, Double>>
        get() = route.lengthByInfra.map { (infra, m) -> infra to m / route.distanceM.coerceAtLeast(1.0) }
            .sortedByDescending { it.second }

    val walkedM: Double get() = route.legs.filter { it.mode == TravelMode.PUSH }.sumOf { it.lengthM }
}

object RouteOptions {

    /** An alternative must beat the recommended route by this much to earn a label. */
    const val FASTER_BY_S = 60.0
    const val MORE_INFRA_BY = 0.10
    const val LESS_CLIMB_BY_M = 15.0

    fun from(routes: List<Route>, maxGradePercent: Int?): List<RouteOption> {
        if (routes.isEmpty()) return emptyList()
        val best = routes.first()
        val fastest = routes.minBy { it.durationS }
        val mostInfra = routes.maxBy { it.infraShare }
        val flattest = routes.minBy { it.ascentM }
        return routes.mapIndexed { i, route ->
            val labels = buildList {
                if (i == 0) add(RouteLabel.RECOMMENDED)
                if (route === fastest && route.durationS <= best.durationS - FASTER_BY_S) add(RouteLabel.FASTEST)
                if (route === mostInfra && route.infraShare >= best.infraShare + MORE_INFRA_BY) add(RouteLabel.MOST_INFRA)
                if (route === flattest && route.ascentM <= best.ascentM - LESS_CLIMB_BY_M) add(RouteLabel.FLATTEST)
            }
            RouteOption(
                route = route,
                hazards = RouteAnalysis.hazards(route),
                stressLengths = RouteAnalysis.lengthByStress(route).toList(),
                labels = labels,
                aboveMaxGrade = maxGradePercent != null && route.maxGradePercent > maxGradePercent + 0.5,
            )
        }
    }

    /** Bounding box of all the routes' points, for fitting the camera. */
    fun bounds(options: List<RouteOption>): GeoBounds? {
        val points = options.flatMap { it.route.points }
        if (points.isEmpty()) return null
        val south = points.minOf { it.lat }
        val north = points.maxOf { it.lat }
        val west = points.minOf { it.lon }
        val east = points.maxOf { it.lon }
        // GeoBounds needs a non-empty box; a route of a few metres gets a small margin.
        val pad = 0.0005
        return GeoBounds(west - pad, south - pad, east + pad, north + pad)
    }
}
