package pt.lisbora.feature.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.lisbora.data.location.LocationRepository
import pt.lisbora.data.routing.RoutingRepository
import pt.lisbora.data.routing.RoutingUnavailableException
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.data.saved.SavedPlacesRepository
import pt.lisbora.data.search.SearchRepository
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.data.settings.RidingSettingsRepository
import pt.lisbora.feature.map.MapDefaults
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.geo.ServiceArea
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import javax.inject.Inject

/**
 * Search, places and A→B directions. The map stays the same underneath; this ViewModel decides
 * which panel is shown ([PlannerMode]) and computes the routes, always on the phone.
 */
@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val search: SearchRepository,
    private val routing: RoutingRepository,
    private val location: LocationRepository,
    private val settingsRepository: RidingSettingsRepository,
    private val savedPlaces: SavedPlacesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var describeJob: Job? = null
    private var cameraIds = 0L
    private var mapCenter: GeoPoint = MapDefaults.LisbonCenter

    init {
        viewModelScope.launch { savedPlaces.saved.collect { list -> _uiState.update { it.copy(saved = list) } } }
        viewModelScope.launch { savedPlaces.recent.collect { list -> _uiState.update { it.copy(recent = list) } } }
        viewModelScope.launch {
            settingsRepository.settings.distinctUntilChanged().collect { settings ->
                val changed = _uiState.value.settings != settings
                _uiState.update { it.copy(settings = settings) }
                if (changed) (_uiState.value.mode as? PlannerMode.Directions)?.let(::computeRoutes)
            }
        }
        // Load the graph and the index in the background so the first search and route are quick.
        viewModelScope.launch { runCatching { routing.warmUp() } }
        viewModelScope.launch { runCatching { search.warmUp() } }
    }

    // --- search -----------------------------------------------------------------------------

    fun onSearchOpen(target: SearchTarget = SearchTarget.PLACE) {
        _uiState.update { it.copy(search = SearchUiState(target)) }
        refreshUserLocation()
    }

    fun onSearchClose() {
        searchJob?.cancel()
        _uiState.update { it.copy(search = null) }
    }

    fun onSearchQueryChange(query: String) {
        val current = _uiState.value.search ?: return
        _uiState.update { it.copy(search = current.copy(query = query, searching = query.isNotBlank(), failed = false)) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { s -> s.copy(search = s.search?.copy(results = emptyList(), searching = false)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val near = _uiState.value.userLocation?.takeIf { it in ServiceArea.BOUNDS } ?: mapCenter
            val outcome = runCatching { search.search(query, near) }
            if (outcome.exceptionOrNull() is CancellationException) return@launch
            _uiState.update { s ->
                val open = s.search?.takeIf { it.query == query } ?: return@update s
                val hits = outcome.getOrNull()
                s.copy(
                    search = open.copy(
                        results = hits.orEmpty().map { SearchResultItem(it.place, s.userLocation?.distanceTo(it.place.point)) },
                        searching = false,
                        failed = hits == null,
                    ),
                )
            }
        }
    }

    /** A search result, a recent place or a saved place was chosen. */
    fun onPlaceChosen(place: Place, remember: Boolean = true) {
        val target = _uiState.value.search?.target ?: SearchTarget.PLACE
        onSearchClose()
        if (remember) viewModelScope.launch { runCatching { savedPlaces.addRecent(place) } }
        applyChoice(target, Endpoint.AtPlace(place))
    }

    fun onSavedPlaceChosen(saved: SavedPlace) = onPlaceChosen(saved.place, remember = false)

    /** "My location" in the origin or destination search. */
    fun onMyLocationChosen() {
        val target = _uiState.value.search?.target ?: return
        onSearchClose()
        applyChoice(target, Endpoint.MyLocation)
    }

    /** "Choose on the map" in the search: close it and wait for a tap. */
    fun onPickOnMapChosen() {
        val target = _uiState.value.search?.target ?: return
        onSearchClose()
        _uiState.update { it.copy(mode = PlannerMode.PickOnMap(target, it.mode)) }
    }

    /** Opens the search to set Home or Work. */
    fun onSetSavedKind(kind: SavedKind) {
        onSearchOpen(if (kind == SavedKind.HOME) SearchTarget.HOME else SearchTarget.WORK)
    }

    fun onClearRecent() {
        viewModelScope.launch { runCatching { savedPlaces.clearRecent() } }
    }

    private fun applyChoice(target: SearchTarget, endpoint: Endpoint) {
        val mode = _uiState.value.mode.let { if (it is PlannerMode.PickOnMap) it.previous else it }
        when (target) {
            SearchTarget.PLACE -> if (endpoint is Endpoint.AtPlace) showPlace(endpoint.place, fromMap = false)
            SearchTarget.ORIGIN, SearchTarget.DESTINATION -> {
                val directions = mode as? PlannerMode.Directions ?: PlannerMode.Directions(origin = null, destination = null)
                val updated = if (target == SearchTarget.ORIGIN) directions.copy(origin = endpoint) else directions.copy(destination = endpoint)
                startDirections(updated)
            }
            SearchTarget.HOME, SearchTarget.WORK -> {
                _uiState.update { it.copy(mode = mode) }
                (endpoint as? Endpoint.AtPlace)?.let { save(it.place, if (target == SearchTarget.HOME) SavedKind.HOME else SavedKind.WORK) }
            }
        }
    }

    // --- map --------------------------------------------------------------------------------

    fun onMapCameraIdle(center: GeoPoint) {
        mapCenter = center
    }

    /** Long press on the map: a pin there, or the new destination when planning a trip. */
    fun onMapLongPress(point: GeoPoint) {
        if (point !in ServiceArea.BOUNDS) {
            showMessage(PlannerMessage.OutsideArea)
            return
        }
        when (val mode = _uiState.value.mode) {
            is PlannerMode.Directions -> describeThen(point) { place -> startDirections(mode.copy(destination = Endpoint.AtPlace(place))) }
            is PlannerMode.PickOnMap -> onMapPointPicked(point)
            else -> describeThen(point) { place -> showPlace(place, fromMap = true) }
        }
    }

    /** Tap on the map while waiting for a point ([PlannerMode.PickOnMap]). */
    fun onMapPointPicked(point: GeoPoint) {
        val mode = _uiState.value.mode as? PlannerMode.PickOnMap ?: return
        if (point !in ServiceArea.BOUNDS) {
            showMessage(PlannerMessage.OutsideArea)
            return
        }
        describeThen(point) { place -> applyChoice(mode.target, Endpoint.AtPlace(place)) }
    }

    fun onPickCancel() {
        val mode = _uiState.value.mode as? PlannerMode.PickOnMap ?: return
        _uiState.update { it.copy(mode = mode.previous) }
    }

    /** Names the point (address, place or street) before handing it on; a plain pin while that runs. */
    private fun describeThen(point: GeoPoint, then: (Place) -> Unit) {
        describeJob?.cancel()
        val pin = Place(name = "", category = PlaceCategory.OTHER, point = point)
        then(pin)
        describeJob = viewModelScope.launch {
            val described = runCatching { search.describe(point) }.getOrNull() ?: return@launch
            replacePin(pin, described)
        }
    }

    /** Swaps a nameless pin for its description wherever it is still shown, without recomputing routes. */
    private fun replacePin(pin: Place, described: Place) {
        _uiState.update { s ->
            val mode = when (val m = s.mode) {
                is PlannerMode.PlaceDetails -> if (m.place == pin) m.copy(place = described) else m
                is PlannerMode.Directions -> m.copy(
                    origin = m.origin.replacing(pin, described),
                    destination = m.destination.replacing(pin, described),
                )
                else -> m
            }
            s.copy(mode = mode)
        }
    }

    private fun Endpoint?.replacing(pin: Place, described: Place): Endpoint? =
        if (this is Endpoint.AtPlace && place == pin) Endpoint.AtPlace(described) else this

    /** Where an endpoint is, ignoring its name. */
    private fun Endpoint?.key(): Any? = if (this is Endpoint.AtPlace) place.point else this

    // --- place details ----------------------------------------------------------------------

    /** Shows a place; the camera only moves to a searched one (a pin is already where the rider pressed). */
    private fun showPlace(place: Place, fromMap: Boolean) {
        _uiState.update {
            it.copy(
                mode = PlannerMode.PlaceDetails(place, fromMap),
                camera = if (fromMap) it.camera else camera(CameraTarget.Point(place.point, PLACE_ZOOM)),
            )
        }
        refreshUserLocation()
    }

    fun onPlaceDismiss() {
        _uiState.update { it.copy(mode = PlannerMode.Browse) }
    }

    /** "Directions" on a place: from my location when allowed, else ask for the origin. */
    fun onDirectionsTo(place: Place) {
        val origin = if (location.hasPermission()) Endpoint.MyLocation else null
        startDirections(PlannerMode.Directions(origin = origin, destination = Endpoint.AtPlace(place)))
        if (origin == null) onSearchOpen(SearchTarget.ORIGIN)
    }

    /** "Start here": the place becomes the origin and the search asks for the destination. */
    fun onDirectionsFrom(place: Place) {
        startDirections(PlannerMode.Directions(origin = Endpoint.AtPlace(place), destination = null))
        onSearchOpen(SearchTarget.DESTINATION)
    }

    fun onToggleFavourite(place: Place) {
        val existing = _uiState.value.saved.firstOrNull { it.kind == SavedKind.FAVOURITE && it.place.samePlace(place) }
        if (existing != null) {
            viewModelScope.launch { runCatching { savedPlaces.remove(existing.id) } }
            showMessage(PlannerMessage.RemovedFavourite)
        } else {
            save(place, SavedKind.FAVOURITE)
        }
    }

    fun onSaveAs(place: Place, kind: SavedKind) = save(place, kind)

    fun onRemoveSaved(saved: SavedPlace) {
        viewModelScope.launch { runCatching { savedPlaces.remove(saved.id) } }
    }

    private fun save(place: Place, kind: SavedKind) {
        viewModelScope.launch { runCatching { savedPlaces.save(place, kind) } }
        showMessage(
            when (kind) {
                SavedKind.HOME -> PlannerMessage.SavedHome
                SavedKind.WORK -> PlannerMessage.SavedWork
                SavedKind.FAVOURITE -> PlannerMessage.SavedFavourite
            },
        )
    }

    // --- directions -------------------------------------------------------------------------

    fun onEndpointClick(target: SearchTarget) = onSearchOpen(target)

    fun onSwapEndpoints() {
        val mode = _uiState.value.mode as? PlannerMode.Directions ?: return
        startDirections(mode.copy(origin = mode.destination, destination = mode.origin))
    }

    fun onDirectionsClose() {
        routeJob?.cancel()
        val mode = _uiState.value.mode as? PlannerMode.Directions
        val destination = (mode?.destination as? Endpoint.AtPlace)?.place
        _uiState.update { it.copy(mode = destination?.let { PlannerMode.PlaceDetails(it) } ?: PlannerMode.Browse) }
    }

    fun onRouteSelected(index: Int) {
        updateDirections { d -> if ((d.routes as? RoutesState.Ready)?.options?.indices?.contains(index) == true) d.copy(selected = index) else d }
    }

    fun onColorModeChange(mode: RouteColorMode) = updateDirections { it.copy(colorMode = mode) }

    fun onDetailsToggle() = updateDirections { it.copy(detailsExpanded = !it.detailsExpanded) }

    fun onRetryRoutes() {
        (_uiState.value.mode as? PlannerMode.Directions)?.let(::computeRoutes)
    }

    private fun updateDirections(transform: (PlannerMode.Directions) -> PlannerMode.Directions) {
        _uiState.update { s -> (s.mode as? PlannerMode.Directions)?.let { s.copy(mode = transform(it)) } ?: s }
    }

    private fun startDirections(directions: PlannerMode.Directions) {
        val reset = directions.copy(routes = RoutesState.Idle, selected = 0)
        _uiState.update { it.copy(mode = reset) }
        computeRoutes(reset)
    }

    private fun computeRoutes(directions: PlannerMode.Directions) {
        routeJob?.cancel()
        val origin = directions.origin
        val destination = directions.destination
        if (origin == null || destination == null) {
            updateDirections { it.copy(routes = RoutesState.Idle) }
            return
        }
        updateDirections { it.copy(routes = RoutesState.Loading) }
        val settings = _uiState.value.settings
        routeJob = viewModelScope.launch {
            val state = try {
                plan(origin, destination, settings) { best -> showRoutes(origin, destination, best) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RoutingUnavailableException) {
                RoutesState.Failed(RouteFailure.UNAVAILABLE)
            } catch (_: Exception) {
                RoutesState.Failed(RouteFailure.UNAVAILABLE)
            }
            showRoutes(origin, destination, state)
        }
    }

    /**
     * Shows [state] if the trip has not changed meanwhile. The camera frames the routes the first time
     * they appear, not again when the alternatives arrive, and the rider's selection is kept.
     */
    private fun showRoutes(origin: Endpoint, destination: Endpoint, state: RoutesState) {
        _uiState.update { s ->
            val current = s.mode as? PlannerMode.Directions ?: return@update s
            // A pin may have been named meanwhile; only a moved endpoint makes these routes stale.
            if (current.origin.key() != origin.key() || current.destination.key() != destination.key()) return@update s
            val firstShown = current.routes !is RoutesState.Ready
            val bounds = (state as? RoutesState.Ready)?.options?.takeIf { firstShown }?.let(RouteOptions::bounds)
            val count = (state as? RoutesState.Ready)?.options?.size ?: 0
            s.copy(
                mode = current.copy(routes = state, selected = if (firstShown || current.selected >= count) 0 else current.selected),
                camera = bounds?.let { camera(CameraTarget.Bounds(it)) } ?: s.camera,
            )
        }
    }

    private suspend fun plan(
        origin: Endpoint,
        destination: Endpoint,
        settings: RidingSettings,
        onBest: (RoutesState.Ready) -> Unit,
    ): RoutesState {
        val from = resolve(origin) ?: return RoutesState.Failed(RouteFailure.LOCATION_UNAVAILABLE)
        val to = resolve(destination) ?: return RoutesState.Failed(RouteFailure.LOCATION_UNAVAILABLE)
        if (from !in ServiceArea.BOUNDS || to !in ServiceArea.BOUNDS) return RoutesState.Failed(RouteFailure.OUTSIDE_AREA)
        if (from.distanceTo(to) < MIN_TRIP_M) return RoutesState.Failed(RouteFailure.SAME_PLACE)
        val onBestRoute: suspend (RouteResult.Success) -> Unit = { best ->
            onBest(RoutesState.Ready(RouteOptions.from(listOf(best.route), settings.maxGradePercent), loadingAlternatives = true))
        }
        return when (val result = routing.route(from, to, settings.toRoutingPreferences(), onBest = onBestRoute)) {
            is RouteResult.Success -> RoutesState.Ready(RouteOptions.from(result.routes, settings.maxGradePercent))
            is RouteResult.Failure -> RoutesState.Failed(
                when (result.reason) {
                    RouteResult.Reason.START_NOT_ON_NETWORK -> RouteFailure.START_NOT_ON_NETWORK
                    RouteResult.Reason.END_NOT_ON_NETWORK -> RouteFailure.END_NOT_ON_NETWORK
                    RouteResult.Reason.NO_PATH -> RouteFailure.NO_PATH
                },
            )
        }
    }

    private suspend fun resolve(endpoint: Endpoint): GeoPoint? = when (endpoint) {
        is Endpoint.AtPlace -> endpoint.place.point
        Endpoint.MyLocation -> location.currentLocation()?.also { p -> _uiState.update { it.copy(userLocation = p) } }
    }

    // --- preferences, back, messages --------------------------------------------------------

    fun onPreferencesOpen() = _uiState.update { it.copy(preferencesOpen = true) }

    fun onPreferencesClose() = _uiState.update { it.copy(preferencesOpen = false) }

    fun onSettingsChange(transform: (RidingSettings) -> RidingSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /** System back: closes the innermost panel. Returns false when there is nothing left to close. */
    fun onBack(): Boolean {
        val s = _uiState.value
        when {
            s.search != null -> onSearchClose()
            s.preferencesOpen -> onPreferencesClose()
            s.mode is PlannerMode.PickOnMap -> onPickCancel()
            s.mode is PlannerMode.Directions -> onDirectionsClose()
            s.mode is PlannerMode.PlaceDetails -> onPlaceDismiss()
            else -> return false
        }
        return true
    }

    fun onCameraHandled(id: Long) {
        _uiState.update { if (it.camera?.id == id) it.copy(camera = null) else it }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    private fun showMessage(message: PlannerMessage) = _uiState.update { it.copy(message = message) }

    private fun camera(target: CameraTarget) = CameraRequest(++cameraIds, target)

    private fun refreshUserLocation() {
        if (!location.hasPermission()) return
        viewModelScope.launch {
            val point = runCatching { location.currentLocation() }.getOrNull() ?: return@launch
            _uiState.update { it.copy(userLocation = point) }
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val PLACE_ZOOM = 16.0

        /** Origin and destination closer than this are the same place. */
        const val MIN_TRIP_M = 20.0
    }
}

/** Same place for favourites: same name within a few metres. */
fun Place.samePlace(other: Place): Boolean = name == other.name && point.distanceTo(other.point) < 15
