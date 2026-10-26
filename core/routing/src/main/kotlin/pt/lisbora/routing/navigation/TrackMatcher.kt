package pt.lisbora.routing.navigation

import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.weighting.RoutingPreferences
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Follows a track recorded or drawn elsewhere (an imported GPX file) on the network: a route through
 * points taken along it every [STOP_SPACING_M]. Close enough that the route keeps to the track's streets,
 * far enough apart that GPS noise does not make it zigzag. The route may turn back at a point, as the
 * track may (out and back along the river).
 */
object TrackMatcher {

    const val STOP_SPACING_M = 250.0

    /** Points along a track that are not near a street (a path missing from the map) are skipped. */
    const val NEAR_SNAP_M = 40.0

    /** Between two stops the track may stray this far from the straight line before a stop is added. */
    const val MAX_DEVIATION_M = 25.0

    /**
     * Points along [track] every [spacingM] of its length, its first and last included, plus the points
     * where it strays more than [MAX_DEVIATION_M] from the line between two of them (a corner, a turn back).
     */
    fun stops(track: List<GeoPoint>, spacingM: Double = STOP_SPACING_M): List<GeoPoint> {
        if (track.isEmpty()) return emptyList()
        val indices = mutableListOf(0)
        var since = 0.0
        for (i in 1 until track.size) {
            since += track[i - 1].distanceTo(track[i])
            if (since >= spacingM - SPACING_SLACK_M) {
                indices += i
                since = 0.0
            }
        }
        val last = track.lastIndex
        if (indices.last() != last) {
            // A last stop very close to the end would make the route wobble there: the end replaces it.
            if (indices.size > 1 && track[indices.last()].distanceTo(track[last]) < spacingM / 3) indices.removeAt(indices.lastIndex)
            indices += last
        }
        val refined = mutableListOf(indices.first())
        indices.zipWithNext { a, b ->
            refine(track, a, b, refined)
            refined += b
        }
        return refined.map { track[it] }.zipWithNext().filter { (a, b) -> a != b }.map { it.first } + track[refined.last()]
    }

    /** Adds to [into], in order, the points between [from] and [to] needed to keep within [MAX_DEVIATION_M]. */
    private fun refine(track: List<GeoPoint>, from: Int, to: Int, into: MutableList<Int>) {
        if (to - from < 2) return
        var farthest = -1
        var distance = MAX_DEVIATION_M
        for (k in from + 1 until to) {
            val d = distanceToSegment(track[k], track[from], track[to])
            if (d > distance) {
                distance = d
                farthest = k
            }
        }
        if (farthest < 0) return
        refine(track, from, farthest, into)
        into += farthest
        refine(track, farthest, to, into)
    }

    private fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * METRES_PER_DEGREE * cosLat
        val ay = (a.lat - p.lat) * METRES_PER_DEGREE
        val bx = (b.lon - p.lon) * METRES_PER_DEGREE * cosLat
        val by = (b.lat - p.lat) * METRES_PER_DEGREE
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        val t = if (length2 > 0) ((-ax * dx - ay * dy) / length2).coerceIn(0.0, 1.0) else 0.0
        return hypot(ax + t * dx, ay + t * dy)
    }

    /** Rounding in the summed lengths must not push a stop one point further. */
    private const val SPACING_SLACK_M = 0.5
    private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M

    /** The route along [track] with [preferences], or why there is none. */
    fun match(engine: RoutingEngine, track: List<GeoPoint>, preferences: RoutingPreferences, spacingM: Double = STOP_SPACING_M): RouteResult {
        val stops = stops(track, spacingM)
        if (stops.size < 2) return RouteResult.Failure(RouteResult.Reason.NO_PATH)
        val first = engine.snap(stops.first(), preferences) ?: return RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK)
        val last = engine.snap(stops.last(), preferences) ?: return RouteResult.Failure(RouteResult.Reason.END_NOT_ON_NETWORK)
        val middle = stops.subList(1, stops.size - 1).mapNotNull { engine.snap(it, preferences, NEAR_SNAP_M) }
        return engine.routeVia(listOf(first) + middle + last, preferences, passThrough = false)
    }
}
