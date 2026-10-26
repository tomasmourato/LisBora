package pt.lisbora.feature.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import pt.lisbora.feature.planner.Baixa
import pt.lisbora.feature.planner.Belem
import pt.lisbora.feature.planner.CameraTarget
import pt.lisbora.feature.planner.Endpoint
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.planner.FakeDiscoveryRepository
import pt.lisbora.feature.planner.FakeGiraRepository
import pt.lisbora.feature.planner.FakeLocationRepository
import pt.lisbora.feature.planner.FakeRoutingRepository
import pt.lisbora.feature.planner.FakeSavedPlacesRepository
import pt.lisbora.feature.planner.FakeSavedRoutesRepository
import pt.lisbora.feature.planner.FakeSearchRepository
import pt.lisbora.feature.planner.FakeSettingsRepository
import pt.lisbora.feature.planner.Graca
import pt.lisbora.feature.planner.PlannerMessage
import pt.lisbora.feature.planner.PlannerMode
import pt.lisbora.feature.planner.PlannerViewModel
import pt.lisbora.feature.planner.SearchTarget
import pt.lisbora.feature.planner.fakeDiscovered
import pt.lisbora.feature.planner.fakeRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryResult
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.eta.BikeType
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverFlowTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val search = FakeSearchRepository()
    private val location = FakeLocationRepository()
    private val settings = FakeSettingsRepository()
    private val discovery = FakeDiscoveryRepository()
    private val savedRoutes = FakeSavedRoutesRepository()
    private val gira = FakeGiraRepository()

    private val torre = Place("Torre de Belém", PlaceCategory.MONUMENT, Belem, "Belém, Lisboa")
    private val loop = fakeDiscovered(fakeRoute(listOf(Baixa, GeoPoint(38.705, -9.150), GeoPoint(38.708, -9.145), Baixa), durationS = 2700.0))
    private val other = fakeDiscovered(fakeRoute(listOf(Baixa, GeoPoint(38.715, -9.140), GeoPoint(38.712, -9.135), Baixa), durationS = 2600.0))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.(PlannerViewModel) -> Unit) = runTest(dispatcher) {
        body(viewModel())
    }

    private fun viewModel() = PlannerViewModel(search, FakeRoutingRepository(), location, settings, FakeSavedPlacesRepository(), discovery, savedRoutes, gira)

    private fun PlannerViewModel.discover() = uiState.value.mode as PlannerMode.Discover

    @Test
    fun `routes are discovered from my location for the time and purpose chosen`() = test { vm ->
        vm.onDiscoverOpen()
        assertEquals(Endpoint.MyLocation, vm.discover().setup.start)
        assertEquals(DiscoverResults.None, vm.discover().results)
        assertEquals(1, discovery.warmUps)

        vm.onDiscoverMinutesChange(60)
        vm.onDiscoverPurposeChange(DiscoveryPurpose.RIVER)
        discovery.result = DiscoveryResult.Success(listOf(loop, other))
        vm.onDiscoverSearch()

        val request = discovery.requests.single()
        assertEquals(Baixa, request.start)
        assertNull(request.end)
        assertEquals(3600.0, request.durationS, 0.0)
        assertEquals(DiscoveryPurpose.RIVER, request.purpose)
        val ready = vm.discover().results as DiscoverResults.Ready
        assertEquals(2, ready.options.size)
        assertTrue(vm.uiState.value.camera!!.target is CameraTarget.Bounds)

        vm.onRouteSelected(1)
        assertEquals(other, vm.discover().selectedOption!!.discovered)
    }

    @Test
    fun `without location access the start is asked for first`() = test { vm ->
        location.permission = false
        vm.onDiscoverOpen()
        assertNull(vm.discover().setup.start)
        vm.onDiscoverSearch()
        assertEquals(SearchTarget.ORIGIN, vm.uiState.value.search!!.target)

        vm.onPlaceChosen(Place("Rossio", PlaceCategory.STREET, Baixa))
        assertEquals(Baixa, (vm.discover().setup.start as Endpoint.AtPlace).place.point)
        // The choices stay on screen until routes are asked for.
        assertEquals(DiscoverResults.None, vm.discover().results)
        assertTrue(discovery.requests.isEmpty())
    }

    @Test
    fun `towards a destination the destination is asked for and ends the ride`() = test { vm ->
        vm.onDiscoverOpen()
        vm.onDiscoverDestinationChange(true)
        vm.onDiscoverSearch()
        assertEquals(SearchTarget.DESTINATION, vm.uiState.value.search!!.target)
        vm.onPlaceChosen(torre)

        discovery.result = DiscoveryResult.Success(listOf(loop), directOnly = true)
        vm.onDiscoverSearch()
        assertEquals(Belem, discovery.requests.single().end)
        assertTrue((vm.discover().results as DiscoverResults.Ready).directOnly)

        // Back to a loop: the destination is kept but not used.
        vm.onDiscoverEdit()
        vm.onDiscoverDestinationChange(false)
        vm.onDiscoverSearch()
        assertNull(discovery.requests.last().end)
    }

    @Test
    fun `another route changes the seed and avoids the routes shown, adjustments switch on and off`() = test { vm ->
        discovery.result = DiscoveryResult.Success(listOf(loop, other))
        vm.onDiscoverOpen()
        vm.onDiscoverSearch()
        vm.onDiscoverAnother()
        val again = discovery.requests.last()
        assertEquals(1L, again.seed)
        assertEquals(listOf(loop.route, other.route), again.avoid)

        vm.onDiscoverTweak(DiscoveryTweak.MORE_RIVER)
        assertEquals(setOf(DiscoveryTweak.MORE_RIVER), discovery.requests.last().tweaks)
        assertEquals(setOf(DiscoveryTweak.MORE_RIVER), vm.discover().tweaks)
        vm.onDiscoverTweak(DiscoveryTweak.MORE_RIVER)
        assertTrue(discovery.requests.last().tweaks.isEmpty())
    }

    @Test
    fun `saving keeps the selected route and a second tap forgets it`() = test { vm ->
        discovery.result = DiscoveryResult.Success(listOf(loop, other))
        vm.onDiscoverOpen()
        vm.onDiscoverPurposeChange(DiscoveryPurpose.CULTURE)
        vm.onDiscoverSearch()
        vm.onDiscoverSave()
        val saved = vm.uiState.value.savedRoutes.single()
        assertEquals(DiscoveryPurpose.CULTURE, saved.purpose)
        assertEquals(loop.stops, saved.stops)
        assertEquals(PlannerMessage.RouteSaved, vm.uiState.value.message)

        vm.onDiscoverSave()
        assertTrue(vm.uiState.value.savedRoutes.isEmpty())
        assertEquals(PlannerMessage.RouteRemoved, vm.uiState.value.message)
    }

    @Test
    fun `a saved route opens computed again from its stops`() = test { vm ->
        discovery.result = DiscoveryResult.Success(listOf(loop))
        vm.onDiscoverOpen()
        vm.onDiscoverPurposeChange(DiscoveryPurpose.RIVER)
        vm.onDiscoverSearch()
        vm.onDiscoverSave()
        vm.onDiscoverClose()
        val saved = vm.uiState.value.savedRoutes.single()

        discovery.rebuilt = loop
        search.described = Place("Rua Augusta", PlaceCategory.STREET, Baixa)
        vm.onDiscoverOpen()
        vm.onSavedRouteOpen(saved)
        val mode = vm.discover()
        assertEquals(DiscoveryPurpose.RIVER, mode.setup.purpose)
        assertFalse(mode.setup.toDestination)
        assertEquals("Rua Augusta", (mode.setup.start as Endpoint.AtPlace).place.name)
        assertEquals(loop, (mode.results as DiscoverResults.Ready).options.single().discovered)

        discovery.rebuilt = null
        vm.onSavedRouteOpen(saved)
        assertEquals(DiscoverResults.Failed(DiscoverFailure.SAVED_UNAVAILABLE), vm.discover().results)
    }

    @Test
    fun `back goes from the routes to the choices, then out of Descobrir`() = test { vm ->
        discovery.result = DiscoveryResult.Success(listOf(loop))
        vm.onDiscoverOpen()
        vm.onDiscoverMinutesChange(90)
        vm.onDiscoverSearch()
        assertTrue(vm.onBack())
        assertEquals(DiscoverResults.None, vm.discover().results)
        assertEquals(90, vm.discover().setup.minutes)
        assertTrue(vm.onBack())
        assertEquals(PlannerMode.Browse, vm.uiState.value.mode)
    }

    @Test
    fun `failures get their own message`() = test { vm ->
        vm.onDiscoverOpen()
        discovery.result = DiscoveryResult.Failure(DiscoveryResult.Reason.START_NOT_ON_NETWORK)
        vm.onDiscoverSearch()
        assertEquals(DiscoverResults.Failed(DiscoverFailure.START_NOT_ON_NETWORK), vm.discover().results)

        discovery.result = DiscoveryResult.Success(emptyList())
        vm.onRetryRoutes()
        assertEquals(DiscoverResults.Failed(DiscoverFailure.NO_ROUTE), vm.discover().results)

        location.location = null
        vm.onRetryRoutes()
        assertEquals(DiscoverResults.Failed(DiscoverFailure.LOCATION_UNAVAILABLE), vm.discover().results)

        location.location = GeoPoint(41.15, -8.61)
        vm.onRetryRoutes()
        assertEquals(DiscoverResults.Failed(DiscoverFailure.OUTSIDE_AREA), vm.discover().results)
    }

    @Test
    fun `new preferences and a new start recompute the routes shown`() = test { vm ->
        discovery.result = DiscoveryResult.Success(listOf(loop))
        vm.onDiscoverOpen()
        vm.onDiscoverSearch()
        vm.onSettingsChange { it.copy(avoidCobbles = true) }
        assertEquals(2, discovery.requests.size)
        assertTrue(discovery.requests.last().preferences.avoidCobbles)

        // A long press moves the start of a loop.
        vm.onMapLongPress(Graca)
        assertEquals(Graca, discovery.requests.last().start)
        assertTrue(vm.discover().results is DiscoverResults.Ready)
    }

    // --- GIRA ---------------------------------------------------------------------------------

    /** Stations about 300 m and 1 km from Baixa, and one by Belém. */
    private val rossio = GiraStation(101, "Bicicletas Gira Estação 101", GeoPoint(38.7132, -9.1376))
    private val chiado = GiraStation(102, "Bicicletas Gira Estação 102", GeoPoint(38.7105, -9.1480))
    private val belemStation = GiraStation(401, "Bicicletas Gira Estação 401", GeoPoint(38.6975, -9.2050))

    @Test
    fun `a GIRA loop takes and returns the bike at the nearest station, in Lisbon, on an e-bike`() = test { vm ->
        gira.stations = listOf(chiado, rossio, belemStation)
        discovery.result = DiscoveryResult.Success(listOf(loop))
        vm.onDiscoverOpen()
        vm.onDiscoverGiraChange(true)
        // The stations show on the map while choosing.
        assertEquals(3, vm.uiState.value.giraStations.size)
        vm.onDiscoverSearch()

        val request = discovery.requests.single()
        assertEquals(rossio.point, request.start)
        assertNull(request.end)
        assertTrue(request.preferences.stayInLisbon)
        assertFalse(request.preferences.allowFerries)
        assertEquals(BikeType.ELECTRIC, request.preferences.rider.bike)
        val trip = (vm.discover().results as DiscoverResults.Ready).gira!!
        assertEquals(rossio, trip.pickUp)
        assertTrue(trip.returnsWhereTaken)
        assertEquals(Baixa.distanceTo(rossio.point), trip.walkToPickUpM, 1.0)
    }

    @Test
    fun `towards a destination the bike is returned at the station nearest to it with a free dock`() = test { vm ->
        // Live data: Rossio has no bike; the station by Belém has no free dock, the next one has.
        val full = belemStation.copy(freeDocks = 0)
        val nextToBelem = GiraStation(402, "Bicicletas Gira Estação 402", GeoPoint(38.6960, -9.2080), freeDocks = 5)
        gira.stations = listOf(rossio.copy(bikes = 0), chiado.copy(bikes = 3), full, nextToBelem)
        discovery.result = DiscoveryResult.Success(listOf(loop))
        vm.onDiscoverOpen()
        vm.onDiscoverGiraChange(true)
        vm.onDiscoverDestinationChange(true)
        vm.onDiscoverSearch()
        vm.onPlaceChosen(torre)
        vm.onDiscoverSearch()

        val request = discovery.requests.single()
        assertEquals(chiado.point, request.start)
        assertEquals(nextToBelem.point, request.end)
        val trip = (vm.discover().results as DiscoverResults.Ready).gira!!
        assertFalse(trip.returnsWhereTaken)
        assertEquals(Belem.distanceTo(nextToBelem.point), trip.walkFromDropOffM!!, 1.0)
    }

    @Test
    fun `without a GIRA station nearby there is no GIRA ride`() = test { vm ->
        gira.stations = listOf(belemStation)
        vm.onDiscoverOpen()
        vm.onDiscoverGiraChange(true)
        vm.onDiscoverSearch()
        assertEquals(DiscoverResults.Failed(DiscoverFailure.NO_GIRA_NEAR_START), vm.discover().results)
        assertTrue(discovery.requests.isEmpty())

        gira.stations = emptyList()
        val empty = viewModel()
        empty.onDiscoverOpen()
        empty.onDiscoverGiraChange(true)
        empty.onDiscoverSearch()
        assertEquals(DiscoverResults.Failed(DiscoverFailure.GIRA_UNAVAILABLE), empty.discover().results)
    }

    @Test
    fun `a saved GIRA ride is computed again for a GIRA bike from its station`() = test { vm ->
        gira.stations = listOf(rossio)
        val stops = listOf(rossio.point, GeoPoint(38.705, -9.150), rossio.point)
        val fromStation = fakeDiscovered(fakeRoute(stops), stops = stops)
        discovery.result = DiscoveryResult.Success(listOf(fromStation))
        vm.onDiscoverOpen()
        vm.onDiscoverGiraChange(true)
        vm.onDiscoverSearch()
        vm.onDiscoverSave()
        val saved = vm.uiState.value.savedRoutes.single()
        assertTrue(saved.gira)

        discovery.rebuilt = fromStation
        vm.onSavedRouteOpen(saved)
        assertTrue(vm.discover().setup.gira)
        assertTrue(discovery.rebuildPreferences.single().stayInLisbon)
        assertEquals(rossio, (vm.discover().results as DiscoverResults.Ready).gira!!.pickUp)
    }
}
