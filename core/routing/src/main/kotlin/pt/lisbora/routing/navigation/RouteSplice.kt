package pt.lisbora.routing.navigation

import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.geo.GeoPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts and joins [Route]s, so a rider who left the route can be taken back to it: a detour from where
 * they are to a point ahead, then the rest of the original route. Times, costs and climbs follow the parts.
 */
object RouteSplice {

    /** The part of [route] between [fromM] and [toM] metres, with distances starting again at 0. */
    fun slice(route: Route, fromM: Double, toM: Double = route.distanceM): Route {
        val from = fromM.coerceIn(0.0, route.distanceM)
        val to = toM.coerceIn(from, route.distanceM)
        val d = route.distances
        val points = mutableListOf(RouteAnalysis.pointAt(route, from))
        val elevations = mutableListOf(elevationAt(route, from))
        val distances = mutableListOf(0.0)
        for (i in d.indices) {
            if (d[i] > from + EPSILON_M && d[i] < to - EPSILON_M) {
                points += route.points[i]
                elevations += route.elevations[i]
                distances += d[i] - from
            }
        }
        if (to - from > EPSILON_M) {
            points += RouteAnalysis.pointAt(route, to)
            elevations += elevationAt(route, to)
            distances += to - from
        }
        val legTime = route.legs.sumOf { it.timeS }
        val oldLegTime = legTime.takeIf { it > 0 }
        val legs = route.legs.mapNotNull { leg ->
            val start = max(leg.startM, from)
            val end = min(leg.endM, to)
            if (end - start <= EPSILON_M) return@mapNotNull null
            val share = if (leg.lengthM > 0) (end - start) / leg.lengthM else 1.0
            // Along the edge, the leg runs from→to when forward and to→from when reversed.
            val enter = start - leg.startM
            val exit = end - leg.startM
            val (edgeFrom, edgeTo) = if (leg.reverse) leg.to - exit to leg.to - enter else leg.from + enter to leg.from + exit
            val newStart = start - from
            leg.copy(
                from = edgeFrom,
                to = edgeTo,
                lengthM = end - start,
                timeS = leg.timeS * share,
                firstPoint = distances.indexOfLast { it <= newStart + EPSILON_M }.coerceAtLeast(0),
                startM = newStart,
            )
        }
        val newLegTime = legs.sumOf { it.timeS }
        val scale = if (oldLegTime != null) newLegTime / oldLegTime else (to - from) / route.distanceM.coerceAtLeast(1.0)
        return build(points, elevations, distances, legs, route.durationS * scale, route.cost * scale)
    }

    /** [first] then [second], which should start where [first] ends (a gap is bridged in a straight line). */
    fun join(first: Route, second: Route): Route {
        if (first.points.isEmpty()) return second
        if (second.points.isEmpty()) return first
        val gap = first.points.last().distanceTo(second.points.first())
        val shared = gap < JOIN_M
        val offset = first.distanceM + if (shared) 0.0 else gap
        val skip = if (shared) 1 else 0
        val points = first.points + second.points.drop(skip)
        val elevations = first.elevations + second.elevations.drop(skip)
        val distances = first.distances + second.distances.drop(skip).map { it + offset }
        val pointOffset = first.points.size - skip
        val legs = first.legs + second.legs.map { it.copy(startM = it.startM + offset, firstPoint = it.firstPoint + pointOffset) }
        return build(points, elevations, distances, legs, first.durationS + second.durationS, first.cost + second.cost)
    }

    private fun build(
        points: List<GeoPoint>,
        elevations: List<Double>,
        distances: List<Double>,
        legs: List<RouteLeg>,
        durationS: Double,
        cost: Double,
    ): Route {
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until elevations.size) {
            val dz = elevations[i] - elevations[i - 1]
            if (dz > 0) ascent += dz else descent -= dz
        }
        return Route(
            points = points,
            elevations = elevations,
            distances = distances,
            legs = legs,
            distanceM = distances.lastOrNull() ?: 0.0,
            durationS = durationS,
            ascentM = ascent,
            descentM = descent,
            maxGradePercent = RouteAnalysis.steepestClimbPercent(distances, elevations),
            cost = cost,
            settledStates = 0,
        )
    }

    /** Elevation at [distance] metres along [route], interpolated. */
    fun elevationAt(route: Route, distance: Double): Double {
        val d = route.distances
        val e = route.elevations
        if (d.isEmpty()) return 0.0
        if (distance <= d.first()) return e.first()
        if (distance >= d.last()) return e.last()
        var i = d.binarySearch(distance)
        if (i >= 0) return e[i]
        i = -i - 2
        val span = d[i + 1] - d[i]
        val t = if (span > 0) (distance - d[i]) / span else 0.0
        return e[i] + t * (e[i + 1] - e[i])
    }

    private const val EPSILON_M = 0.01

    /** Routes that end and start closer than this are joined at one point. */
    private const val JOIN_M = 5.0
}
