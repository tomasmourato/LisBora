package pt.lisbora.feature.discover

import androidx.annotation.StringRes
import pt.lisbora.R
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.feature.planner.Endpoint
import pt.lisbora.feature.planner.RouteLabel
import pt.lisbora.feature.planner.RouteOption
import pt.lisbora.feature.planner.RouteOptions
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.eta.BikeType
import pt.lisbora.routing.weighting.RoutingPreferences

/** What Descobrir asks before computing: where from (and to), how long and what for. */
data class DiscoverSetup(
    val start: Endpoint?,
    /** Finish somewhere else ("Até um destino") instead of back at the start. */
    val toDestination: Boolean = false,
    val end: Endpoint? = null,
    val minutes: Int = DEFAULT_MINUTES,
    val purpose: DiscoveryPurpose = DiscoveryPurpose.LEISURE,
    /** On a GIRA bike: from a station to a station, never leaving the municipality of Lisbon. */
    val gira: Boolean = false,
) {
    /** Where the ride ends when it does not come back to the start. */
    val destination: Endpoint? get() = end.takeIf { toDestination }

    val ready: Boolean get() = start != null && (!toDestination || end != null)

    companion object {
        const val DEFAULT_MINUTES = 45
        val MINUTE_OPTIONS = listOf(20, 30, 45, 60, 90, 120)
    }
}

enum class DiscoverFailure(@param:StringRes val textRes: Int) {
    START_NOT_ON_NETWORK(R.string.route_failure_start),
    END_NOT_ON_NETWORK(R.string.route_failure_end),
    NO_ROUTE(R.string.discover_failure_no_route),
    LOCATION_UNAVAILABLE(R.string.route_failure_location),
    OUTSIDE_AREA(R.string.route_failure_outside),
    SAVED_UNAVAILABLE(R.string.discover_saved_unavailable),
    NO_GIRA_NEAR_START(R.string.discover_failure_gira_start),
    NO_GIRA_NEAR_END(R.string.discover_failure_gira_end),
    GIRA_UNAVAILABLE(R.string.discover_failure_gira_unavailable),
    UNAVAILABLE(R.string.discover_failure_unavailable),
}

/**
 * Where a GIRA ride takes and returns its bike, and how far the rider walks to and from them.
 * [walkFromDropOffM] is null on a loop, which returns the bike where it was taken.
 */
data class GiraTrip(val pickUp: GiraStation, val dropOff: GiraStation, val walkToPickUpM: Double, val walkFromDropOffM: Double?) {
    val returnsWhereTaken: Boolean get() = dropOff == pickUp
}

/** The rider's preferences for a GIRA bike: assisted (most of the fleet is electric) and only in Lisbon. */
fun RidingSettings.toGiraPreferences(): RoutingPreferences = toRoutingPreferences().let {
    it.copy(rider = it.rider.copy(bike = BikeType.ELECTRIC), allowFerries = false, stayInLisbon = true)
}

sealed interface DiscoverResults {
    /** Nothing computed: the choices are shown. */
    data object None : DiscoverResults
    data object Loading : DiscoverResults

    /** [directOnly]: the destination leaves no time to explore, so this is the direct route. [gira]: the stations used. */
    data class Ready(val options: List<DiscoverOption>, val directOnly: Boolean = false, val gira: GiraTrip? = null) : DiscoverResults
    data class Failed(val reason: DiscoverFailure) : DiscoverResults
}

/** A discovered route with what its card, chips and details need. */
data class DiscoverOption(
    val discovered: DiscoveredRoute,
    val option: RouteOption,
    /** Why ride it, most telling first. */
    val reasons: List<RouteReason>,
) {
    /** Named places it goes through or passes, each once. */
    val places: List<Poi> get() = (discovered.anchors + discovered.highlights.pois).filter { it.name != null }.distinctBy { it.name }
}

object DiscoverOptions {
    fun from(routes: List<DiscoveredRoute>, purpose: DiscoveryPurpose, maxGradePercent: Int?): List<DiscoverOption> {
        // A→B labels ("Mais rápida") mean little here: only the recommended one is marked.
        val options = RouteOptions.from(routes.map { it.route }, maxGradePercent)
            .mapIndexed { i, option -> option.copy(labels = if (i == 0) listOf(RouteLabel.RECOMMENDED) else emptyList()) }
        return routes.zip(options) { discovered, option -> DiscoverOption(discovered, option, RouteReasons.of(discovered, purpose)) }
    }
}

/** The saved route these stops were built from, if any (stops are stored rounded to ~10 cm). */
fun List<SavedRoute>.matching(route: DiscoveredRoute): SavedRoute? = firstOrNull { saved ->
    saved.stops.size == route.stops.size && saved.stops.zip(route.stops).all { (a, b) -> a.distanceTo(b) < SAME_STOP_M }
}

private const val SAME_STOP_M = 2.0
