package pt.lisbora.feature.planner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.routing.search.SearchHit

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val search = FakeSearchRepository()
    private val routing = FakeRoutingRepository()
    private val location = FakeLocationRepository()
    private val settings = FakeSettingsRepository()
    private val saved = FakeSavedPlacesRepository()

    private val torre = Place("Torre de Belém", PlaceCategory.MONUMENT, Belem, "Belém, Lisboa")
    private val route = fakeRoute(listOf(Baixa, GeoPoint(38.705, -9.170), Belem))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PlannerViewModel(search, routing, location, settings, saved, FakeDiscoveryRepository(), FakeSavedRoutesRepository(), FakeGiraRepository())

    private fun test(body: suspend TestScope.(PlannerViewModel) -> Unit) = runTest(dispatcher) { body(viewModel()) }

    @Test
    fun `searching waits for the typing to pause and lists the hits`() = test { vm ->
        search.hits = listOf(SearchHit(torre, 5.0, null))
        vm.onSearchOpen()
        vm.onSearchQueryChange("tor")
        vm.onSearchQueryChange("torre")
        assertTrue(vm.uiState.value.search!!.searching)
        advanceTimeBy(PlannerViewModel.SEARCH_DEBOUNCE_MS + 1)
        assertEquals(listOf("torre"), search.queries)
        val state = vm.uiState.value.search!!
        assertFalse(state.searching)
        assertEquals(torre, state.results.single().place)
        // Distances are measured from the rider's position.
        assertEquals(Baixa.distanceTo(Belem), state.results.single().distanceM!!, 1.0)
    }

    @Test
    fun `choosing a result shows the place and remembers it`() = test { vm ->
        vm.onSearchOpen()
        vm.onPlaceChosen(torre)
        val state = vm.uiState.value
        assertNull(state.search)
        assertEquals(PlannerMode.PlaceDetails(torre), state.mode)
        assertEquals(listOf(torre), state.recent)
        assertEquals(CameraTarget.Point(Belem, PlannerViewModel.PLACE_ZOOM), state.camera!!.target)
    }

    @Test
    fun `directions from my location compute the routes and frame them`() = test { vm ->
        val alternative = fakeRoute(listOf(Baixa, GeoPoint(38.715, -9.170), Belem), durationS = 700.0)
        routing.result = RouteResult.Success(route, listOf(alternative))
        vm.onDirectionsTo(torre)

        val mode = vm.uiState.value.mode as PlannerMode.Directions
        assertEquals(Endpoint.MyLocation, mode.origin)
        val ready = mode.routes as RoutesState.Ready
        assertEquals(2, ready.options.size)
        assertEquals(listOf(RouteLabel.RECOMMENDED), ready.options[0].labels)
        assertEquals(Baixa to Belem, routing.requests.single().let { it.first to it.second })
        assertTrue(vm.uiState.value.camera!!.target is CameraTarget.Bounds)

        vm.onRouteSelected(1)
        assertEquals(1, (vm.uiState.value.mode as PlannerMode.Directions).selected)
    }

    @Test
    fun `without location access the origin is asked for`() = test { vm ->
        location.permission = false
        vm.onDirectionsTo(torre)
        val state = vm.uiState.value
        val mode = state.mode as PlannerMode.Directions
        assertNull(mode.origin)
        assertEquals(RoutesState.Idle, mode.routes)
        assertEquals(SearchTarget.ORIGIN, state.search!!.target)

        // Picking the origin in the search starts the routing.
        routing.result = RouteResult.Success(route)
        vm.onPlaceChosen(Place("Rossio", PlaceCategory.STREET, Baixa))
        assertTrue((vm.uiState.value.mode as PlannerMode.Directions).routes is RoutesState.Ready)
    }

    @Test
    fun `changing the preferences recomputes the open routes`() = test { vm ->
        routing.result = RouteResult.Success(route)
        vm.onDirectionsTo(torre)
        vm.onSettingsChange { it.copy(maxGradePercent = 8, avoidCobbles = true) }
        assertEquals(2, routing.requests.size)
        val preferences = routing.requests.last().third
        assertEquals(8.0, preferences.maxGradePercent!!, 0.0)
        assertTrue(preferences.avoidCobbles)
    }

    @Test
    fun `routing failures and far-away points get their own message`() = test { vm ->
        routing.result = RouteResult.Failure(RouteResult.Reason.NO_PATH)
        vm.onDirectionsTo(torre)
        assertEquals(RoutesState.Failed(RouteFailure.NO_PATH), (vm.uiState.value.mode as PlannerMode.Directions).routes)

        vm.onDirectionsTo(Place("Porto", PlaceCategory.LOCALITY, GeoPoint(41.15, -8.61)))
        assertEquals(RoutesState.Failed(RouteFailure.OUTSIDE_AREA), (vm.uiState.value.mode as PlannerMode.Directions).routes)

        location.location = null
        vm.onDirectionsTo(torre)
        assertEquals(RoutesState.Failed(RouteFailure.LOCATION_UNAVAILABLE), (vm.uiState.value.mode as PlannerMode.Directions).routes)
    }

    @Test
    fun `a long press drops a pin that gets its street name`() = test { vm ->
        search.described = Place("Rua da Graça", PlaceCategory.STREET, Graca, "São Vicente, Lisboa")
        vm.onMapLongPress(Graca)
        val place = (vm.uiState.value.mode as PlannerMode.PlaceDetails).place
        assertEquals("Rua da Graça", place.name)
        assertEquals(Graca, place.point)
        // The pin is where the rider pressed: the camera stays put.
        assertNull(vm.uiState.value.camera)

        // While planning, a long press moves the destination and keeps the computed routes.
        routing.result = RouteResult.Success(route)
        vm.onDirectionsTo(torre)
        vm.onMapLongPress(Graca)
        val mode = vm.uiState.value.mode as PlannerMode.Directions
        assertEquals("Rua da Graça", (mode.destination as Endpoint.AtPlace).place.name)
        assertTrue(mode.routes is RoutesState.Ready)

        vm.onMapLongPress(GeoPoint(41.15, -8.61))
        assertEquals(PlannerMessage.OutsideArea, vm.uiState.value.message)
    }

    @Test
    fun `choosing on the map sets the endpoint that was being searched`() = test { vm ->
        routing.result = RouteResult.Success(route)
        vm.onDirectionsTo(torre)
        vm.onEndpointClick(SearchTarget.ORIGIN)
        vm.onPickOnMapChosen()
        assertTrue(vm.uiState.value.mode is PlannerMode.PickOnMap)
        vm.onMapPointPicked(Graca)
        val mode = vm.uiState.value.mode as PlannerMode.Directions
        assertEquals(Graca, (mode.origin as Endpoint.AtPlace).place.point)
        assertEquals(Graca, routing.requests.last().first)
    }

    @Test
    fun `swapping the ends routes the other way`() = test { vm ->
        routing.result = RouteResult.Success(route)
        vm.onDirectionsTo(torre)
        vm.onSwapEndpoints()
        val mode = vm.uiState.value.mode as PlannerMode.Directions
        assertEquals(Endpoint.AtPlace(torre), mode.origin)
        assertEquals(Endpoint.MyLocation, mode.destination)
        assertEquals(Belem to Baixa, routing.requests.last().let { it.first to it.second })
    }

    @Test
    fun `favourites toggle and home replaces the previous one`() = test { vm ->
        vm.onToggleFavourite(torre)
        assertEquals(SavedKind.FAVOURITE, vm.uiState.value.saved.single().kind)
        vm.onToggleFavourite(torre)
        assertTrue(vm.uiState.value.saved.isEmpty())
        assertEquals(PlannerMessage.RemovedFavourite, vm.uiState.value.message)

        vm.onSaveAs(torre, SavedKind.HOME)
        vm.onSetSavedKind(SavedKind.HOME)
        vm.onPlaceChosen(Place("Rua da Graça", PlaceCategory.STREET, Graca))
        val homes = vm.uiState.value.saved.filter { it.kind == SavedKind.HOME }
        assertEquals("Rua da Graça", homes.single().place.name)
        assertEquals(PlannerMessage.SavedHome, vm.uiState.value.message)
    }

    @Test
    fun `back closes the innermost panel first`() = test { vm ->
        routing.result = RouteResult.Success(route)
        vm.onSearchOpen()
        vm.onPlaceChosen(torre)
        vm.onDirectionsTo(torre)
        vm.onPreferencesOpen()
        assertTrue(vm.onBack())
        assertFalse(vm.uiState.value.preferencesOpen)
        assertTrue(vm.onBack())
        assertEquals(PlannerMode.PlaceDetails(torre), vm.uiState.value.mode)
        assertTrue(vm.onBack())
        assertEquals(PlannerMode.Browse, vm.uiState.value.mode)
        assertFalse(vm.onBack())
    }
}
