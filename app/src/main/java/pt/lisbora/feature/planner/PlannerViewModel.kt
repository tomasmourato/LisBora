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
import pt.lisbora.data.discover.DiscoveryRepository
import pt.lisbora.data.gira.GiraRepository
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.gira.GiraStations
import pt.lisbora.data.gpx.GpxFiles
import pt.lisbora.data.location.LocationRepository
import pt.lisbora.data.routing.RoutingRepository
import pt.lisbora.data.routing.RoutingUnavailableException
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.data.saved.SavedPlacesRepository
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.data.saved.SavedRoutesRepository
import pt.lisbora.data.search.SearchRepository
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.data.settings.RidingSettingsRepository
import pt.lisbora.feature.discover.DiscoverFailure
import pt.lisbora.feature.discover.DiscoverOptions
import pt.lisbora.feature.discover.DiscoverResults
import pt.lisbora.feature.discover.DiscoverSetup
import pt.lisbora.feature.discover.GiraTrip
import pt.lisbora.feature.discover.matching
import pt.lisbora.feature.discover.toGiraPreferences
import pt.lisbora.feature.map.MapDefaults
import pt.lisbora.feature.navigation.NavigationRequest
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryRequest
import pt.lisbora.routing.discover.DiscoveryResult
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.io.Gpx
import pt.lisbora.routing.navigation.TrackMatcher
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.geo.ServiceArea
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import javax.inject.Inject

/**
 * Search, places, A→B directions and Descobrir. The map stays the same underneath; this ViewModel
 * decides which panel is shown ([PlannerMode]) and computes the routes, always on the phone.
 */
@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val search: SearchRepository,
    private val routing: RoutingRepository,
    private val location: LocationRepository,
    private val settingsRepository: RidingSettingsRepository,
    private val savedPlaces: SavedPlacesRepository,
    private val discovery: DiscoveryRepository,
    private val savedRoutes: SavedRoutesRepository,
    private val gira: GiraRepository,
    private val gpxFiles: GpxFiles,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var describeJob: Job? = null
    private var discoverJob: Job? = null
    private var cameraIds = 0L
    private var mapCenter: GeoPoint = MapDefaults.LisbonCenter

    init {
        viewModelScope.launch { savedPlaces.saved.collect { list -> _uiState.update { it.copy(saved = list) } } }
        viewModelScope.launch { savedPlaces.recent.collect { list -> _uiState.update { it.copy(recent = list) } } }
        viewModelScope.launch { savedRoutes.routes.collect { list -> _uiState.update { it.copy(savedRoutes = list) } } }
        viewModelScope.launch {
            settingsRepository.settings.distinctUntilChanged().collect { settings ->
                // Only what changes routes (not the voice switch).
                val changed = _uiState.value.settings.toRoutingPreferences() != settings.toRoutingPreferences()
                _uiState.update { it.copy(settings = settings) }
                if (changed) {
                    when (val mode = _uiState.value.mode) {
                        is PlannerMode.Directions -> computeRoutes(mode)
                        // An imported GPX keeps its route: the preferences only change what Descobrir finds.
                        is PlannerMode.Discover -> if (mode.results != DiscoverResults.None && !mode.isImported) computeDiscovery(mode)
                        else -> Unit
                    }
                }
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
            SearchTarget.ORIGIN, SearchTarget.DESTINATION -> if (mode is PlannerMode.Discover) {
                val setup = if (target == SearchTarget.ORIGIN) mode.setup.copy(start = endpoint) else mode.setup.copy(end = endpoint, toDestination = true)
                updateSetup(mode, setup)
            } else {
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

    /**
     * Long press on the map: a pin there, the new destination when planning a trip, or in Descobrir the
     * new start (of a loop) or destination.
     */
    fun onMapLongPress(point: GeoPoint) {
        if (point !in ServiceArea.BOUNDS) {
            showMessage(PlannerMessage.OutsideArea)
            return
        }
        when (val mode = _uiState.value.mode) {
            is PlannerMode.Directions -> describeThen(point) { place -> startDirections(mode.copy(destination = Endpoint.AtPlace(place))) }
            is PlannerMode.Discover -> describeThen(point) { place ->
                (_uiState.value.mode as? PlannerMode.Discover)?.let { current ->
                    val endpoint = Endpoint.AtPlace(place)
                    updateSetup(current, if (current.setup.toDestination) current.setup.copy(end = endpoint) else current.setup.copy(start = endpoint))
                }
            }
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
                is PlannerMode.Discover -> m.copy(
                    setup = m.setup.copy(start = m.setup.start.replacing(pin, described), end = m.setup.end.replacing(pin, described)),
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

    // Route cards, details and colours work the same for A→B and Descobrir.

    fun onRouteSelected(index: Int) {
        updateDirections { d -> if ((d.routes as? RoutesState.Ready)?.options?.indices?.contains(index) == true) d.copy(selected = index) else d }
        updateDiscover { d -> if (index in d.options.indices) d.copy(selected = index) else d }
    }

    fun onColorModeChange(mode: RouteColorMode) {
        updateDirections { it.copy(colorMode = mode) }
        updateDiscover { it.copy(colorMode = mode) }
    }

    fun onDetailsToggle() {
        updateDirections { it.copy(detailsExpanded = !it.detailsExpanded) }
        updateDiscover { it.copy(detailsExpanded = !it.detailsExpanded) }
    }

    fun onRetryRoutes() {
        when (val mode = _uiState.value.mode) {
            is PlannerMode.Directions -> computeRoutes(mode)
            is PlannerMode.Discover -> computeDiscovery(mode)
            else -> Unit
        }
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

    // --- navigation -------------------------------------------------------------------------

    /** What "Iniciar" follows: the selected A→B or Descobrir route, with the preferences to recompute it. */
    fun navigationRequest(): NavigationRequest? {
        val s = _uiState.value
        return when (val mode = s.mode) {
            is PlannerMode.Directions -> mode.selectedOption?.route?.let { route ->
                val name = (mode.destination as? Endpoint.AtPlace)?.place?.name?.ifBlank { null }
                NavigationRequest(
                    route = route,
                    destination = route.points.last(),
                    rejoin = false,
                    preferences = s.settings.toRoutingPreferences(),
                    title = listOfNotNull(TITLE, name).joinToString(" · "),
                )
            }
            is PlannerMode.Discover -> mode.selectedOption?.let { option ->
                val route = option.discovered.route
                val imported = (mode.results as? DiscoverResults.Ready)?.imported?.ifBlank { null }
                NavigationRequest(
                    route = route,
                    destination = route.points.last(),
                    rejoin = true,
                    preferences = if (mode.setup.gira) s.settings.toGiraPreferences() else s.settings.toRoutingPreferences(),
                    title = listOfNotNull(TITLE, imported ?: option.places.firstOrNull()?.name).joinToString(" · "),
                    gira = (mode.results as? DiscoverResults.Ready)?.gira,
                )
            }
            else -> null
        }
    }

    // --- discover ---------------------------------------------------------------------------

    /** The "Descobrir" button: from my location when allowed, else the start is chosen first. */
    fun onDiscoverOpen() {
        val start = if (location.hasPermission()) Endpoint.MyLocation else null
        _uiState.update { it.copy(mode = PlannerMode.Discover(DiscoverSetup(start = start))) }
        // Points of interest and network parts are prepared while the rider makes their choices.
        viewModelScope.launch { runCatching { discovery.warmUp() } }
    }

    fun onDiscoverClose() {
        discoverJob?.cancel()
        _uiState.update { it.copy(mode = PlannerMode.Browse) }
    }

    /** Back arrow and system back: from the routes to the choices, then out of Descobrir. */
    fun onDiscoverBack() {
        val mode = _uiState.value.mode as? PlannerMode.Discover ?: return
        if (mode.results == DiscoverResults.None) onDiscoverClose() else onDiscoverEdit()
    }

    fun onDiscoverDestinationChange(toDestination: Boolean) = updateDiscover { it.copy(setup = it.setup.copy(toDestination = toDestination)) }

    fun onDiscoverMinutesChange(minutes: Int) = updateDiscover { it.copy(setup = it.setup.copy(minutes = minutes)) }

    fun onDiscoverPurposeChange(purpose: DiscoveryPurpose) = updateDiscover { it.copy(setup = it.setup.copy(purpose = purpose)) }

    /** A GIRA bike instead of the rider's own: the stations appear on the map. */
    fun onDiscoverGiraChange(on: Boolean) {
        updateDiscover { it.copy(setup = it.setup.copy(gira = on)) }
        if (on) viewModelScope.launch { giraStations() }
    }

    /** "Descobrir rotas": asks for what is missing (start or destination), else computes the routes. */
    fun onDiscoverSearch() {
        val mode = _uiState.value.mode as? PlannerMode.Discover ?: return
        when {
            mode.setup.start == null -> onSearchOpen(SearchTarget.ORIGIN)
            !mode.setup.ready -> onSearchOpen(SearchTarget.DESTINATION)
            else -> computeDiscovery(mode.copy(tweaks = emptySet()))
        }
    }

    /** "Outra rota": a new seed, and routes much like the ones shown rank lower. */
    fun onDiscoverAnother() {
        val mode = _uiState.value.mode as? PlannerMode.Discover ?: return
        computeDiscovery(mode.copy(seed = mode.seed + 1), avoid = mode.options.map { it.discovered.route })
    }

    /** Quick adjustments ("Mais rio", "Menos subidas"), switched on and off. */
    fun onDiscoverTweak(tweak: DiscoveryTweak) {
        val mode = _uiState.value.mode as? PlannerMode.Discover ?: return
        computeDiscovery(mode.copy(tweaks = if (tweak in mode.tweaks) mode.tweaks - tweak else mode.tweaks + tweak))
    }

    /** Back to the choices, which are kept. */
    fun onDiscoverEdit() {
        discoverJob?.cancel()
        updateDiscover { it.copy(results = DiscoverResults.None, selected = 0, detailsExpanded = false) }
    }

    /** Saves the selected route, or forgets it when it was already saved. */
    fun onDiscoverSave() {
        val s = _uiState.value
        val mode = s.mode as? PlannerMode.Discover ?: return
        val option = mode.selectedOption ?: return
        val existing = s.savedRoutes.matching(option.discovered)
        viewModelScope.launch {
            runCatching {
                if (existing != null) savedRoutes.remove(existing.id) else savedRoutes.save(option.discovered, mode.setup.purpose, mode.tweaks, mode.setup.gira)
            }
        }
        showMessage(if (existing != null) PlannerMessage.RouteRemoved else PlannerMessage.RouteSaved)
    }

    /** Opens a saved route, computed again from its stops with today's data and preferences. */
    fun onSavedRouteOpen(saved: SavedRoute) {
        discoverJob?.cancel()
        val start = Place(name = "", category = PlaceCategory.OTHER, point = saved.stops.first())
        val end = Place(name = "", category = PlaceCategory.OTHER, point = saved.stops.last()).takeIf { !saved.isLoop }
        val setup = DiscoverSetup(
            start = Endpoint.AtPlace(start),
            toDestination = end != null,
            end = end?.let { Endpoint.AtPlace(it) },
            minutes = (saved.durationS / 60).toInt().coerceAtLeast(1),
            purpose = saved.purpose,
            gira = saved.gira,
        )
        _uiState.update { it.copy(mode = PlannerMode.Discover(setup, DiscoverResults.Loading, tweaks = saved.tweaks)) }
        listOfNotNull(start, end).forEach { pin ->
            viewModelScope.launch { runCatching { search.describe(pin.point) }.getOrNull()?.let { replacePin(pin, it) } }
        }
        val settings = _uiState.value.settings
        discoverJob = viewModelScope.launch {
            val results = try {
                val preferences = if (saved.gira) settings.toGiraPreferences() else settings.toRoutingPreferences()
                val trip = if (saved.gira) savedGiraTrip(saved) else null
                discovery.rebuild(saved, preferences)
                    ?.let { DiscoverResults.Ready(DiscoverOptions.from(listOf(it), saved.purpose, settings.maxGradePercent), gira = trip) }
                    ?: DiscoverResults.Failed(DiscoverFailure.SAVED_UNAVAILABLE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DiscoverResults.Failed(DiscoverFailure.UNAVAILABLE)
            }
            showDiscovery(results)
        }
    }

    /**
     * A GPX file to follow (from another app, a friend, an older ride): read, followed on the streets it
     * keeps to, and shown like a Descobrir route, ready for "Iniciar".
     */
    fun onGpxImport(uri: String) {
        discoverJob?.cancel()
        val mode = _uiState.value.mode as? PlannerMode.Discover ?: PlannerMode.Discover(DiscoverSetup(start = null))
        _uiState.update { it.copy(mode = mode.copy(results = DiscoverResults.Loading, selected = 0, detailsExpanded = false)) }
        val settings = _uiState.value.settings
        discoverJob = viewModelScope.launch {
            val results = try {
                importGpx(uri, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DiscoverResults.Failed(DiscoverFailure.GPX_UNREADABLE)
            }
            showDiscovery(results)
        }
    }

    private suspend fun importGpx(uri: String, settings: RidingSettings): DiscoverResults {
        val xml = gpxFiles.read(uri)
        val track = Gpx.parse(xml)
        if (TrackMatcher.stops(track).size < 2) return DiscoverResults.Failed(DiscoverFailure.GPX_EMPTY)
        if (track.first() !in ServiceArea.BOUNDS || track.last() !in ServiceArea.BOUNDS) return DiscoverResults.Failed(DiscoverFailure.GPX_OUTSIDE)
        // Where it starts and ends, named like any pin; a track that ends where it began is a loop.
        val start = Place(name = "", category = PlaceCategory.OTHER, point = track.first())
        val end = Place(name = "", category = PlaceCategory.OTHER, point = track.last()).takeIf { track.first().distanceTo(track.last()) >= GPX_LOOP_M }
        updateDiscover {
            it.copy(setup = it.setup.copy(start = Endpoint.AtPlace(start), toDestination = end != null, end = end?.let { e -> Endpoint.AtPlace(e) }, gira = false))
        }
        listOfNotNull(start, end).forEach { pin ->
            viewModelScope.launch { runCatching { search.describe(pin.point) }.getOrNull()?.let { replacePin(pin, it) } }
        }
        return when (val result = routing.routeAlong(track, settings.toRoutingPreferences())) {
            is RouteResult.Success -> {
                val discovered = discovery.describe(result.route, TrackMatcher.stops(track))
                DiscoverResults.Ready(
                    DiscoverOptions.from(listOf(discovered), DiscoveryPurpose.LEISURE, settings.maxGradePercent),
                    imported = Gpx.name(xml).orEmpty(),
                )
            }
            is RouteResult.Failure -> DiscoverResults.Failed(DiscoverFailure.GPX_NO_ROUTE)
        }
    }

    fun onSavedRouteRemove(saved: SavedRoute) {
        viewModelScope.launch { runCatching { savedRoutes.remove(saved.id) } }
    }

    /**
     * GIRA stations, with live bikes and docks when they can be had (the repository keeps them for a
     * minute), also kept in the state for the map; the last known ones, or empty, when unavailable.
     */
    private suspend fun giraStations(): List<GiraStation> {
        val stations = runCatching { gira.stations() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: _uiState.value.giraStations
        _uiState.update { it.copy(giraStations = stations) }
        return stations
    }

    /** The stations a saved GIRA ride starts and ends at (its first and last stops). */
    private suspend fun savedGiraTrip(saved: SavedRoute): GiraTrip? {
        val stations = giraStations()
        val pickUp = GiraStations.nearest(stations, saved.stops.first(), SAVED_STATION_M) ?: return null
        val dropOff = GiraStations.nearest(stations, saved.stops.last(), SAVED_STATION_M) ?: return null
        return GiraTrip(pickUp, dropOff, walkToPickUpM = 0.0, walkFromDropOffM = 0.0.takeIf { dropOff != pickUp })
    }

    private fun updateDiscover(transform: (PlannerMode.Discover) -> PlannerMode.Discover) {
        _uiState.update { s -> (s.mode as? PlannerMode.Discover)?.let { s.copy(mode = transform(it)) } ?: s }
    }

    /** New start or destination: routes already asked for are computed again, else the choices just change. */
    private fun updateSetup(mode: PlannerMode.Discover, setup: DiscoverSetup) {
        val updated = mode.copy(setup = setup)
        if (mode.results != DiscoverResults.None && setup.ready) {
            computeDiscovery(updated)
        } else {
            discoverJob?.cancel()
            _uiState.update { it.copy(mode = updated.copy(results = DiscoverResults.None)) }
        }
    }

    private fun computeDiscovery(mode: PlannerMode.Discover, avoid: List<Route> = emptyList()) {
        discoverJob?.cancel()
        val loading = mode.copy(results = DiscoverResults.Loading, selected = 0, detailsExpanded = false)
        _uiState.update { it.copy(mode = loading) }
        val settings = _uiState.value.settings
        discoverJob = viewModelScope.launch {
            val results = try {
                discover(loading, settings, avoid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DiscoverResults.Failed(DiscoverFailure.UNAVAILABLE)
            }
            showDiscovery(results)
        }
    }

    /** Shows [results] if Descobrir is still waiting for them, framing the routes. */
    private fun showDiscovery(results: DiscoverResults) {
        _uiState.update { s ->
            val current = s.mode as? PlannerMode.Discover ?: return@update s
            if (current.results != DiscoverResults.Loading) return@update s
            val bounds = (results as? DiscoverResults.Ready)?.options?.map { it.option }?.let(RouteOptions::bounds)
            s.copy(
                mode = current.copy(results = results, selected = 0),
                camera = bounds?.let { camera(CameraTarget.Bounds(it)) } ?: s.camera,
            )
        }
    }

    private suspend fun discover(mode: PlannerMode.Discover, settings: RidingSettings, avoid: List<Route>): DiscoverResults {
        val setup = mode.setup
        val from = setup.start?.let { resolve(it) } ?: return DiscoverResults.Failed(DiscoverFailure.LOCATION_UNAVAILABLE)
        val to = setup.destination?.let { resolve(it) ?: return DiscoverResults.Failed(DiscoverFailure.LOCATION_UNAVAILABLE) }
        if (from !in ServiceArea.BOUNDS || (to != null && to !in ServiceArea.BOUNDS)) return DiscoverResults.Failed(DiscoverFailure.OUTSIDE_AREA)
        // A destination where the ride starts makes it a loop.
        val end = to?.takeIf { it.distanceTo(from) >= MIN_TRIP_M }
        val trip = if (setup.gira) {
            val stations = giraStations().ifEmpty { return DiscoverResults.Failed(DiscoverFailure.GIRA_UNAVAILABLE) }
            val pickUp = GiraStations.nearest(stations, from, MAX_GIRA_WALK_M) { it.hasBike }
                ?: return DiscoverResults.Failed(DiscoverFailure.NO_GIRA_NEAR_START)
            // A loop returns the bike where it was taken: that dock is free.
            val dropOff = if (end == null) pickUp else GiraStations.nearest(stations, end, MAX_GIRA_WALK_M) { it.hasFreeDock }
                ?: return DiscoverResults.Failed(DiscoverFailure.NO_GIRA_NEAR_END)
            GiraTrip(pickUp, dropOff, from.distanceTo(pickUp.point), end?.distanceTo(dropOff.point))
        } else {
            null
        }
        val request = DiscoveryRequest(
            start = trip?.pickUp?.point ?: from,
            end = if (trip != null) trip.dropOff.point.takeIf { !trip.returnsWhereTaken } else end,
            durationS = setup.minutes * 60.0,
            purpose = setup.purpose,
            preferences = if (setup.gira) settings.toGiraPreferences() else settings.toRoutingPreferences(),
            tweaks = mode.tweaks,
            seed = mode.seed,
            avoid = avoid,
        )
        return when (val result = discovery.discover(request)) {
            is DiscoveryResult.Success -> if (result.routes.isEmpty()) {
                DiscoverResults.Failed(DiscoverFailure.NO_ROUTE)
            } else {
                DiscoverResults.Ready(DiscoverOptions.from(result.routes, setup.purpose, settings.maxGradePercent), result.directOnly, trip)
            }
            is DiscoveryResult.Failure -> DiscoverResults.Failed(
                when (result.reason) {
                    DiscoveryResult.Reason.START_NOT_ON_NETWORK -> DiscoverFailure.START_NOT_ON_NETWORK
                    DiscoveryResult.Reason.END_NOT_ON_NETWORK -> DiscoverFailure.END_NOT_ON_NETWORK
                    DiscoveryResult.Reason.NO_ROUTE -> DiscoverFailure.NO_ROUTE
                },
            )
        }
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
            s.mode is PlannerMode.Discover -> onDiscoverBack()
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

        /** Name of a ride in its GPX file, before the place it goes to. */
        private const val TITLE = "LisBora"

        /** Farthest a rider is sent on foot to take or return a GIRA bike. */
        const val MAX_GIRA_WALK_M = 1500.0

        /** A GPX track ending this close to where it began is a loop. */
        private const val GPX_LOOP_M = 100.0

        /** A saved GIRA ride's first and last stops are its stations. */
        private const val SAVED_STATION_M = 100.0
    }
}

/** Same place for favourites: same name within a few metres. */
fun Place.samePlace(other: Place): Boolean = name == other.name && point.distanceTo(other.point) < 15
