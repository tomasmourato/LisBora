package pt.lisbora.routing.discover

import pt.lisbora.routing.Route
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.weighting.RoutingPreferences

/** What a Descobrir ride is for ("motivo"). */
enum class DiscoveryPurpose { LEISURE, SCENERY, RIVER, SPORT, CLIMBS, DESCENTS, FAMILY, CULTURE }

/** Quick adjustments on the results ("mais rio", "menos subidas"). */
enum class DiscoveryTweak { MORE_RIVER, FEWER_CLIMBS }

data class DiscoveryRequest(
    val start: GeoPoint,
    /** Where to finish; null for a loop back to [start]. */
    val end: GeoPoint? = null,
    /** Time available, in seconds. */
    val durationS: Double,
    val purpose: DiscoveryPurpose,
    /** The rider's own preferences (bike, level, what to avoid); the purpose adjusts them. */
    val preferences: RoutingPreferences = RoutingPreferences(),
    val tweaks: Set<DiscoveryTweak> = emptySet(),
    /** Different seeds give different routes ("Outra rota"); the same seed gives the same ones. */
    val seed: Long = 0,
    val maxRoutes: Int = 3,
    /** Routes already shown ("Outra rota"): candidates much like them rank lower. */
    val avoid: List<Route> = emptyList(),
) {
    init {
        require(durationS in MIN_DURATION_S..MAX_DURATION_S) { "durationS out of range: $durationS" }
        require(maxRoutes >= 1) { "maxRoutes must be at least 1" }
    }

    val isLoop: Boolean get() = end == null

    companion object {
        const val MIN_DURATION_S = 10 * 60.0
        const val MAX_DURATION_S = 4 * 3600.0
    }
}

/** What makes a discovered route worth riding, for the "why this route" chips. */
data class RouteHighlights(
    val riversideM: Double,
    val greenM: Double,
    val segregatedM: Double,
    /** Share (0..1) of the distance on any cycling infrastructure. */
    val infraShare: Double,
    /** Share (0..1) of the distance ridden on calm streets (traffic stress 1 or 2). */
    val calmShare: Double,
    /** Points of interest along the route that suit its purpose, the ones it was built through first. */
    val pois: List<Poi>,
    /** Start elevation minus end elevation, in metres (positive = finishes lower). */
    val netDescentM: Double,
) {
    fun count(kind: PoiKind): Int = pois.count { it.kind == kind }
}

data class DiscoveredRoute(
    val route: Route,
    /** The stops it was built through, start and end included: enough to compute it again later. */
    val stops: List<GeoPoint>,
    /** Points of interest the route was built to visit. */
    val anchors: List<Poi>,
    /** The route may turn back at its stops (out and back); needed to compute it again. */
    val turnsBack: Boolean,
    val highlights: RouteHighlights,
    /** Internal ranking score (higher is better). */
    val score: Double,
)

sealed interface DiscoveryResult {
    /** The best routes, most recommended first. [directOnly]: the time only allows the direct route. */
    data class Success(val routes: List<DiscoveredRoute>, val directOnly: Boolean = false) : DiscoveryResult

    data class Failure(val reason: Reason) : DiscoveryResult

    enum class Reason {
        START_NOT_ON_NETWORK,
        END_NOT_ON_NETWORK,

        /** No candidate route could be built. */
        NO_ROUTE,
    }
}

/** Runs independent tasks, possibly in parallel; the app passes one backed by a thread pool. */
interface TaskRunner {
    /** Results in the order of [tasks]. */
    fun <T> runAll(tasks: List<() -> T>): List<T>

    object Sequential : TaskRunner {
        override fun <T> runAll(tasks: List<() -> T>): List<T> = tasks.map { it() }
    }
}
