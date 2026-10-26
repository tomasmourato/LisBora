package pt.lisbora.feature.navigation

import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.discover.GiraTrip
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.io.TrackPoint
import pt.lisbora.routing.navigation.Instruction
import pt.lisbora.routing.navigation.RouteProgress
import pt.lisbora.routing.weighting.RoutingPreferences

/**
 * A route to follow. [rejoin]: after leaving it, go back to it a little ahead (a Descobrir route is worth
 * keeping); otherwise compute a new route to [destination] (A→B). [title] names the trip in the GPX file.
 */
data class NavigationRequest(
    val route: Route,
    val destination: GeoPoint,
    val rejoin: Boolean,
    val preferences: RoutingPreferences,
    val title: String,
    /** A GIRA ride: where the bike is taken and returned. */
    val gira: GiraTrip? = null,
    /** Rides on the rider's own bike teach the app their pace (GIRA bikes are not theirs). */
    val calibrates: Boolean = gira == null,
)

/** Time left in a GIRA trip's 45-minute period, when it is worth saying. */
enum class GiraWarning { TEN_MINUTES, FIVE_MINUTES, OVER }

/**
 * GIRA trips are charged per 45-minute period (terms 1(x)): docking and taking a bike again starts a new
 * one. A warning comes 10 and 5 minutes before the end, and when it has passed, unless the ride ends in time.
 */
object GiraPeriod {
    const val PERIOD_S = 45 * 60.0

    /** Time to find a dock and lock the bike. */
    const val DOCKING_MARGIN_S = 60.0

    fun warning(elapsedS: Double, remainingRideS: Double?): GiraWarning? {
        val left = PERIOD_S - elapsedS
        if (remainingRideS != null && remainingRideS + DOCKING_MARGIN_S <= left) return null
        return when {
            left <= 0 -> GiraWarning.OVER
            left <= 5 * 60 -> GiraWarning.FIVE_MINUTES
            left <= 10 * 60 -> GiraWarning.TEN_MINUTES
            else -> null
        }
    }
}

/** A GIRA station near the rider, and how far it is. */
data class NearbyStation(val station: GiraStation, val distanceM: Double)

/**
 * The GIRA side of a navigation. [startedAtMs] is null until the rider reaches the pick-up station (or
 * starts riding); [swapStation] is set while going to a station to swap bikes, [atSwapStation] once there.
 */
data class GiraNavigation(
    val trip: GiraTrip,
    val startedAtMs: Long? = null,
    val elapsedS: Double = 0.0,
    val warning: GiraWarning? = null,
    /** Nearest station to swap bikes at right now (a free dock, ideally a bike too). */
    val suggestion: NearbyStation? = null,
    val swapStation: GiraStation? = null,
    val atSwapStation: Boolean = false,
    val swaps: Int = 0,
) {
    val started: Boolean get() = startedAtMs != null
    val leftS: Double get() = GiraPeriod.PERIOD_S - elapsedS
}

/** How a finished ride went, and what the app learnt from it. */
data class RideSummary(
    val title: String,
    val distanceM: Double,
    val movingS: Double,
    val elapsedS: Double,
    val climbM: Double,
    val arrived: Boolean,
    /** The new learnt pace when this ride changed it, else null. */
    val newPersonalFactor: Double?,
    val track: List<TrackPoint>,
    val plannedRoute: Route,
) {
    val averageKmh: Double get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
}

data class NavigationUiState(
    val active: Boolean = false,
    /** The route being followed now (after detours), and its instructions and hazards. */
    val route: Route? = null,
    val instructions: List<Instruction> = emptyList(),
    val hazards: List<RouteHazard> = emptyList(),
    /** What is left of [route], for the map. */
    val remaining: Route? = null,
    val progress: RouteProgress? = null,
    val next: Instruction? = null,
    val distanceToNextM: Double? = null,
    /** The instruction after [next], when it follows closely. */
    val then: Instruction? = null,
    /** A climb, cobbles, tram rails… starting soon. */
    val upcoming: RouteHazard? = null,
    val distanceToUpcomingM: Double? = null,
    val waitingForGps: Boolean = true,
    val rerouting: Boolean = false,
    val rerouteFailed: Boolean = false,
    val destination: GeoPoint? = null,
    val gira: GiraNavigation? = null,
    val summary: RideSummary? = null,
    /** Incremented to ask the map to follow the rider again after they moved it. */
    val followRequest: Int = 0,
    val message: NavigationMessage? = null,
) {
    val showing: Boolean get() = active || summary != null
}

enum class NavigationMessage { GPX_SAVED, GPX_FAILED }
