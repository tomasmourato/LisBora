package pt.lisbora.routing

import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode

/** One traversed edge, or part of an edge at the start and end of a route. */
data class RouteLeg(
    val edge: Int,
    /** True when travelled adj→base. */
    val reverse: Boolean,
    /** Start and end of the travelled part, in metres from the edge's base node (`from ≤ to`). */
    val from: Double,
    val to: Double,
    val flags: EdgeFlags,
    val name: String?,
    val lengthM: Double,
    val timeS: Double,
    /** Index in [Route.points] where this leg starts. */
    val firstPoint: Int,
) {
    val mode: TravelMode get() = flags.mode(reverse)
    val infra: EdgeInfra get() = flags.infra
}

/** A computed route with its geometry, ETA and the breakdown shown to the rider. */
data class Route(
    val points: List<GeoPoint>,
    /** Elevation in metres of each of [points]. */
    val elevations: List<Double>,
    val legs: List<RouteLeg>,
    val distanceM: Double,
    val durationS: Double,
    val ascentM: Double,
    val descentM: Double,
    /** Steepest uphill grade along the route, in percent, measured over stretches of at least 30 m. */
    val maxGradePercent: Double,
    /** Internal cost the router minimised (seconds weighted by comfort factors). */
    val cost: Double,
    /** Search effort, for diagnostics. */
    val settledStates: Int,
) {
    /** Metres travelled on each kind of infrastructure. */
    val lengthByInfra: Map<EdgeInfra, Double>
        get() = legs.groupBy { it.infra }.mapValues { (_, legs) -> legs.sumOf { it.lengthM } }

    /** Metres where the bike has to be pushed. */
    val pushedM: Double get() = legs.filter { it.mode == TravelMode.PUSH }.sumOf { it.lengthM }

    /** Share of the distance (0..1) on any cycling infrastructure. */
    val infraShare: Double
        get() = if (distanceM <= 0) 0.0 else legs.filter { it.infra != EdgeInfra.NONE }.sumOf { it.lengthM } / distanceM
}

sealed interface RouteResult {
    data class Success(val route: Route) : RouteResult

    data class Failure(val reason: Reason) : RouteResult

    enum class Reason {
        /** No usable street near the start. */
        START_NOT_ON_NETWORK,

        /** No usable street near the destination. */
        END_NOT_ON_NETWORK,

        /** Both ends are on the network but not connected under the given preferences. */
        NO_PATH,
    }
}
