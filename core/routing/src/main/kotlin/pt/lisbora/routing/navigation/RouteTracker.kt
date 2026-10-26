package pt.lisbora.routing.navigation

import pt.lisbora.routing.Route
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.RoutingGraph
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Where the rider is on the route after a position fix. */
data class RouteProgress(
    /** Distance travelled along the route, in metres. */
    val alongM: Double,
    /** The fix projected onto the route. */
    val snapped: GeoPoint,
    /** How far the fix is from the route, in metres. */
    val distanceFromRouteM: Double,
    /** Away from the route for longer than allowed: time to compute a new one. */
    val offRoute: Boolean,
    val remainingM: Double,
    /** Expected riding time left, from the route's ETA. */
    val remainingS: Double,
    val arrived: Boolean,
)

/**
 * Follows a rider along [route] from position fixes (about one per second). Each fix is projected onto
 * the stretch just behind and ahead of the last position, so a route that passes the same street twice
 * (out and back, or a loop) is followed in order; the whole route is searched only when that fails.
 * The rider is off the route after [offRouteAfterMs] more than [offRouteM] (plus the fix's inaccuracy) away.
 * Not thread-safe: one tracker per navigation.
 */
class RouteTracker(
    val route: Route,
    private val offRouteM: Double = OFF_ROUTE_M,
    private val offRouteAfterMs: Long = OFF_ROUTE_AFTER_MS,
) {
    private var along = -1.0
    private var awaySinceMs: Long? = null

    /** Riding time from the start to the beginning of each leg, scaled to the route's ETA (turn delays included). */
    private val legStartS: DoubleArray
    private val timeScale: Double

    init {
        val legTime = route.legs.sumOf { it.timeS }
        timeScale = if (legTime > 0) route.durationS / legTime else 1.0
        legStartS = DoubleArray(route.legs.size)
        var t = 0.0
        route.legs.forEachIndexed { i, leg ->
            legStartS[i] = t
            t += leg.timeS * timeScale
        }
    }

    /** Expected riding time from the start to [alongM] metres. */
    fun timeAt(alongM: Double): Double {
        val legs = route.legs
        if (legs.isEmpty()) return 0.0
        val i = legs.indexOfLast { it.startM <= alongM }.coerceAtLeast(0)
        val leg = legs[i]
        val fraction = if (leg.lengthM > 0) ((alongM - leg.startM) / leg.lengthM).coerceIn(0.0, 1.0) else 1.0
        return legStartS[i] + leg.timeS * timeScale * fraction
    }

    /**
     * Updates the progress with a fix at [point] taken at [timeMs]. [accuracyM] widens the off-route
     * margin (up to [MAX_ACCURACY_ALLOWANCE_M]); [bearing] (degrees, when moving) tells the two directions
     * of a street ridden twice apart.
     */
    fun update(point: GeoPoint, timeMs: Long, accuracyM: Double = 0.0, bearing: Double? = null): RouteProgress {
        val tolerance = offRouteM + accuracyM.coerceIn(0.0, MAX_ACCURACY_ALLOWANCE_M)
        val near = if (along >= 0) project(point, along - BACK_M, along + AHEAD_M, bearing) else null
        val best = near?.takeIf { it.distance <= tolerance } ?: project(point, 0.0, route.distanceM, bearing)
        val onRoute = best != null && best.distance <= tolerance
        // Away from the route the position stays where it was (at the start until the route is reached).
        if (best != null && onRoute) along = if (along < 0 || best.along >= along - BACK_M) best.along else along
        if (onRoute) {
            awaySinceMs = null
        } else if (awaySinceMs == null) {
            awaySinceMs = timeMs
        }
        val away = awaySinceMs
        val position = along.coerceAtLeast(0.0)
        val remaining = (route.distanceM - position).coerceAtLeast(0.0)
        return RouteProgress(
            alongM = position,
            snapped = best?.takeIf { onRoute }?.point ?: point,
            distanceFromRouteM = best?.distance ?: Double.POSITIVE_INFINITY,
            offRoute = away != null && timeMs - away >= offRouteAfterMs,
            remainingM = remaining,
            remainingS = (route.durationS - timeAt(position)).coerceAtLeast(0.0),
            arrived = remaining <= ARRIVE_M && (best?.distance ?: Double.POSITIVE_INFINITY) <= ARRIVE_M * 2,
        )
    }

    private class Projection(val along: Double, val point: GeoPoint, val distance: Double)

    /**
     * The closest point of the route between [fromM] and [toM] to [point]. Among candidates nearly as close
     * (within [AMBIGUITY_M]), the earliest wins; a segment ridden the other way than [bearing] counts as farther.
     */
    private fun project(point: GeoPoint, fromM: Double, toM: Double, bearing: Double?): Projection? {
        val d = route.distances
        val pts = route.points
        if (pts.size < 2) return pts.firstOrNull()?.let { Projection(0.0, it, it.distanceTo(point)) }
        val cosLat = cos(Math.toRadians(point.lat))
        fun x(p: GeoPoint) = (p.lon - point.lon) * METRES_PER_DEGREE * cosLat
        fun y(p: GeoPoint) = (p.lat - point.lat) * METRES_PER_DEGREE
        val candidates = mutableListOf<Pair<Projection, Double>>()
        for (i in 0 until pts.size - 1) {
            if (d[i + 1] < fromM || d[i] > toM) continue
            val ax = x(pts[i])
            val ay = y(pts[i])
            val bx = x(pts[i + 1])
            val by = y(pts[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val length2 = dx * dx + dy * dy
            val t = if (length2 > 0) ((-ax * dx - ay * dy) / length2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val distance = sqrt(px * px + py * py)
            var score = distance
            if (bearing != null && length2 > 0) {
                val segmentBearing = RoutingGraph.normalizeBearing(Math.toDegrees(atan2(dx, dy)))
                if (abs(RoutingGraph.turnAngle(segmentBearing, bearing)) > OPPOSITE_DEG) score += WRONG_WAY_PENALTY_M
            }
            val projected = GeoPoint(pts[i].lat + t * (pts[i + 1].lat - pts[i].lat), pts[i].lon + t * (pts[i + 1].lon - pts[i].lon))
            candidates += Projection(d[i] + t * (d[i + 1] - d[i]), projected, distance) to score
        }
        val bestScore = candidates.minOfOrNull { it.second } ?: return null
        return candidates.filter { it.second <= bestScore + AMBIGUITY_M }.minBy { it.first.along }.first
    }

    companion object {
        const val OFF_ROUTE_M = 40.0
        const val OFF_ROUTE_AFTER_MS = 5_000L
        const val ARRIVE_M = 25.0
        const val MAX_ACCURACY_ALLOWANCE_M = 25.0

        /** GPS noise may put the rider a little behind the last position. */
        private const val BACK_M = 30.0

        /** How far ahead a fix may land (a second or two at speed, or a few missed fixes). */
        private const val AHEAD_M = 400.0
        private const val AMBIGUITY_M = 8.0
        private const val OPPOSITE_DEG = 100.0
        private const val WRONG_WAY_PENALTY_M = 25.0
        private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M
    }
}
