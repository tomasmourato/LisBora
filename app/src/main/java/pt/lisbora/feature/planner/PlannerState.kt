package pt.lisbora.feature.planner

import androidx.annotation.StringRes
import pt.lisbora.R
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.feature.discover.DiscoverOption
import pt.lisbora.feature.discover.DiscoverResults
import pt.lisbora.feature.discover.DiscoverSetup
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.Place

/** What a search (or a point picked on the map) is for. */
enum class SearchTarget { PLACE, ORIGIN, DESTINATION, HOME, WORK }

data class SearchResultItem(val place: Place, val distanceM: Double?)

/** The full-screen search, open over the map. */
data class SearchUiState(
    val target: SearchTarget,
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
    val searching: Boolean = false,
    val failed: Boolean = false,
)

/** One end of a trip. */
sealed interface Endpoint {
    data object MyLocation : Endpoint
    data class AtPlace(val place: Place) : Endpoint
}

enum class RouteFailure(@param:StringRes val textRes: Int) {
    START_NOT_ON_NETWORK(R.string.route_failure_start),
    END_NOT_ON_NETWORK(R.string.route_failure_end),
    NO_PATH(R.string.route_failure_no_path),
    LOCATION_UNAVAILABLE(R.string.route_failure_location),
    OUTSIDE_AREA(R.string.route_failure_outside),
    SAME_PLACE(R.string.route_failure_same_place),
    UNAVAILABLE(R.string.route_failure_unavailable),
}

sealed interface RoutesState {
    /** Waiting for the origin or the destination. */
    data object Idle : RoutesState
    data object Loading : RoutesState
    /** [loadingAlternatives]: the best route is shown while the alternatives are still being searched. */
    data class Ready(val options: List<RouteOption>, val loadingAlternatives: Boolean = false) : RoutesState
    data class Failed(val reason: RouteFailure) : RoutesState
}

/** How the selected route is coloured on the map. */
enum class RouteColorMode { INFRA, GRADE, STRESS }

sealed interface PlannerMode {
    /** Just the map. */
    data object Browse : PlannerMode

    /** A place chosen in the search or pressed on the map, with "Directions" and "Save". */
    data class PlaceDetails(val place: Place, val fromMap: Boolean = false) : PlannerMode

    data class Directions(
        val origin: Endpoint?,
        val destination: Endpoint?,
        val routes: RoutesState = RoutesState.Idle,
        val selected: Int = 0,
        val colorMode: RouteColorMode = RouteColorMode.INFRA,
        val detailsExpanded: Boolean = false,
    ) : PlannerMode {
        val selectedOption: RouteOption? get() = (routes as? RoutesState.Ready)?.options?.getOrNull(selected)
    }

    /**
     * Descobrir: routes for the time available and a purpose, back to the start or towards a destination.
     * The choices are shown until routes are asked for ([results] is [DiscoverResults.None]).
     */
    data class Discover(
        val setup: DiscoverSetup,
        val results: DiscoverResults = DiscoverResults.None,
        val selected: Int = 0,
        val tweaks: Set<DiscoveryTweak> = emptySet(),
        /** Changes with each "Outra rota". */
        val seed: Long = 0,
        val colorMode: RouteColorMode = RouteColorMode.INFRA,
        val detailsExpanded: Boolean = false,
    ) : PlannerMode {
        val options: List<DiscoverOption> get() = (results as? DiscoverResults.Ready)?.options.orEmpty()

        /** Showing a route that follows an imported GPX file. */
        val isImported: Boolean get() = (results as? DiscoverResults.Ready)?.imported != null
        val selectedOption: DiscoverOption? get() = options.getOrNull(selected)
    }

    /** Waiting for a tap on the map to choose [target]; [previous] is restored afterwards or on cancel. */
    data class PickOnMap(val target: SearchTarget, val previous: PlannerMode) : PlannerMode
}

sealed interface CameraTarget {
    data class Point(val point: GeoPoint, val zoom: Double) : CameraTarget
    data class Bounds(val bounds: GeoBounds) : CameraTarget
}

/** A one-off camera move; [id] changes for every request so repeating the same target still moves. */
data class CameraRequest(val id: Long, val target: CameraTarget)

enum class PlannerMessage(@param:StringRes val textRes: Int) {
    SavedHome(R.string.planner_saved_home),
    SavedWork(R.string.planner_saved_work),
    SavedFavourite(R.string.planner_saved_favourite),
    RemovedFavourite(R.string.planner_removed_favourite),
    LocationUnavailable(R.string.message_location_unavailable),
    OutsideArea(R.string.planner_point_outside),
    SearchFailed(R.string.search_failed),
    RouteSaved(R.string.discover_saved),
    RouteRemoved(R.string.discover_removed),
}

data class PlannerUiState(
    val mode: PlannerMode = PlannerMode.Browse,
    val search: SearchUiState? = null,
    val saved: List<SavedPlace> = emptyList(),
    val recent: List<Place> = emptyList(),
    /** Descobrir routes the rider kept, newest first. */
    val savedRoutes: List<SavedRoute> = emptyList(),
    /** GIRA stations, loaded when a GIRA ride is being planned. */
    val giraStations: List<GiraStation> = emptyList(),
    val settings: RidingSettings = RidingSettings(),
    val preferencesOpen: Boolean = false,
    val camera: CameraRequest? = null,
    val message: PlannerMessage? = null,
    /** Last position known to the planner, for distances in the search results. */
    val userLocation: GeoPoint? = null,
)
