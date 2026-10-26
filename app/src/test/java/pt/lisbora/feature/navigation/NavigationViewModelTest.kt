package pt.lisbora.feature.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.location.LocationFix
import pt.lisbora.feature.discover.GiraTrip
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.planner.FakeGiraRepository
import pt.lisbora.feature.planner.FakeGpxFiles
import pt.lisbora.feature.planner.FakeLocationRepository
import pt.lisbora.feature.planner.FakeRoutingRepository
import pt.lisbora.feature.planner.FakeSettingsRepository
import pt.lisbora.feature.planner.MutableClock
import pt.lisbora.feature.planner.fakeRoute
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.navigation.Maneuver
import pt.lisbora.routing.weighting.RoutingPreferences
import kotlin.math.cos

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val location = FakeLocationRepository()
    private val routing = FakeRoutingRepository()
    private val gira = FakeGiraRepository()
    private val settings = FakeSettingsRepository()
    private val gpx = FakeGpxFiles()
    private val clock = MutableClock()

    private val origin = GeoPoint(38.70, -9.20)

    /** [x] metres east and [y] north of [origin]. */
    private fun at(x: Double, y: Double = 0.0) =
        GeoPoint(origin.lat + y / 111_195.0, origin.lon + x / (111_195.0 * cos(Math.toRadians(origin.lat))))

    /** A straight 3 km street east, in six 500 m legs. */
    private val straight = fakeRoute((0..6).map { at(it * 500.0) }, durationS = 900.0)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.(NavigationViewModel) -> Unit) = runTest(dispatcher) {
        body(NavigationViewModel(location, routing, gira, settings, gpx, clock))
    }

    private fun request(route: Route, rejoin: Boolean = false, trip: GiraTrip? = null) =
        NavigationRequest(route, route.points.last(), rejoin, RoutingPreferences(), "LisBora · Teste", trip)

    private var time = 1_761_490_800_000L

    /** A fix at [point], [afterS] seconds after the last one. */
    private fun fix(point: GeoPoint, afterS: Double = 5.0, speed: Double = 5.0) {
        time += (afterS * 1000).toLong()
        clock.millis = time
        location.fixes.tryEmit(LocationFix(point, time, accuracyM = 5.0, speedMps = speed))
    }

    private fun alongStraight(m: Double) = RouteAnalysis.pointAt(straight, m)

    @Test
    fun `a ride followed to the end gives a summary and teaches the app the rider's pace`() = test { vm ->
        vm.start(request(straight))
        assertTrue(vm.uiState.value.active)
        assertTrue(vm.uiState.value.waitingForGps)
        // 3 km in 10 minutes, where the model expected 15.
        var m = 0.0
        while (m <= 3000.0) {
            fix(alongStraight(m))
            m += 25.0
        }
        val state = vm.uiState.value
        assertFalse(state.active)
        val summary = state.summary!!
        assertTrue(summary.arrived)
        assertEquals(3000.0, summary.distanceM, 30.0)
        assertEquals(600.0, summary.movingS, 10.0)
        assertEquals(18.0, summary.averageKmh, 0.5)
        assertEquals(1.15, summary.newPersonalFactor!!, 0.01)
        assertEquals(1.15, settings.state.value.personalFactor, 0.01)

        vm.onExportGpx("content://gpx/1")
        assertTrue(gpx.written.getValue("content://gpx/1").contains("<trkpt"))
        assertEquals(NavigationMessage.GPX_SAVED, vm.uiState.value.message)
        vm.onSummaryDismiss()
        assertFalse(vm.uiState.value.showing)
    }

    @Test
    fun `a GPS jump is not ridden distance, and stopping early ends without counting it`() = test { vm ->
        vm.start(request(straight))
        var m = 0.0
        while (m <= 300.0) {
            fix(alongStraight(m))
            m += 25.0
        }
        // The fix jumps 400 m away for a second and comes back.
        fix(at(300.0, 400.0), afterS = 1.0)
        fix(alongStraight(310.0), afterS = 1.0)
        vm.onStop()
        val summary = vm.uiState.value.summary!!
        assertFalse(summary.arrived)
        assertEquals(310.0, summary.distanceM, 5.0)
        assertNull(summary.newPersonalFactor)
    }

    @Test
    fun `the next turn and how far it is, and what comes soon after`() = test { vm ->
        // East 500 m on Rua 0, then north 500 m on Rua 1.
        val turn = fakeRoute(listOf(at(0.0), at(500.0), at(500.0, 500.0)), durationS = 200.0)
        vm.start(request(turn))
        fix(RouteAnalysis.pointAt(turn, 100.0))
        val state = vm.uiState.value
        assertEquals(Maneuver.LEFT, state.next!!.maneuver)
        assertEquals("Rua 1", state.next.street)
        assertEquals(400.0, state.distanceToNextM!!, 2.0)
        assertFalse(state.waitingForGps)
        assertEquals(900.0, state.progress!!.remainingM, 2.0)
    }

    @Test
    fun `five seconds off an A to B route, a new one goes to the destination`() = test { vm ->
        routing.result = RouteResult.Success(fakeRoute(listOf(at(500.0, 100.0), at(3000.0)), durationS = 500.0))
        vm.start(request(straight))
        fix(alongStraight(500.0))
        fix(at(500.0, 100.0), afterS = 1.0)
        fix(at(510.0, 100.0), afterS = 3.0)
        assertTrue(routing.requests.isEmpty())
        fix(at(520.0, 100.0), afterS = 3.0)
        val (from, to, _) = routing.requests.single()
        assertEquals(at(520.0, 100.0), from)
        assertEquals(straight.points.last(), to)
        assertEquals(2, vm.uiState.value.route!!.points.size)
        assertFalse(vm.uiState.value.rerouting)
    }

    @Test
    fun `off a Descobrir route, the way back joins it a little ahead and keeps the rest`() = test { vm ->
        routing.result = RouteResult.Success(fakeRoute(listOf(at(500.0, 100.0), at(750.0)), durationS = 80.0))
        vm.start(request(straight, rejoin = true))
        fix(alongStraight(500.0))
        fix(at(500.0, 100.0), afterS = 1.0)
        fix(at(500.0, 100.0), afterS = 6.0)
        val to = routing.requests.single().second
        assertEquals(750.0, to.distanceTo(origin), 3.0)
        // The detour, then the 2250 m left of the planned route.
        assertEquals(at(500.0, 100.0).distanceTo(at(750.0)) + 2250.0, vm.uiState.value.route!!.distanceM, 5.0)
    }

    // --- GIRA -----------------------------------------------------------------------------------

    private val pickUp = GiraStation(101, "Bicicletas Gira Estação 101", at(0.0, 20.0))
    private val nearby = GiraStation(205, "Bicicletas Gira Estação 205", at(1200.0, 150.0), bikes = 4, freeDocks = 6)
    private val full = GiraStation(206, "Bicicletas Gira Estação 206", at(1100.0, 40.0), bikes = 9, freeDocks = 0)

    /** A slow 3 km GIRA ride (90 min expected), so the 45 minutes run out on the way. */
    private val longRide = fakeRoute((0..6).map { at(it * 500.0) }, durationS = 5400.0)

    @Test
    fun `before the 45 minutes run out, the nearest station with a free dock is named and the rider is taken there`() = test { vm ->
        gira.stations = listOf(pickUp, nearby, full)
        val trip = GiraTrip(pickUp, pickUp, walkToPickUpM = 300.0, walkFromDropOffM = null)
        vm.start(request(longRide, rejoin = true, trip = trip))

        // Walking to the station: no clock yet, and no new route although off the planned one.
        fix(at(0.0, 300.0))
        fix(at(0.0, 250.0), afterS = 10.0)
        var g = vm.uiState.value.gira!!
        assertFalse(g.started)
        assertTrue(routing.requests.isEmpty())

        // At the station the trip starts; 34 minutes later, 1 km on, nothing yet.
        fix(at(0.0, 30.0), afterS = 60.0)
        assertTrue(vm.uiState.value.gira!!.started)
        fix(RouteAnalysis.pointAt(longRide, 1000.0), afterS = 34 * 60.0)
        assertNull(vm.uiState.value.gira!!.warning)

        // At 35 minutes: ten left, and a long way to go.
        fix(RouteAnalysis.pointAt(longRide, 1050.0), afterS = 60.0)
        g = vm.uiState.value.gira!!
        assertEquals(GiraWarning.TEN_MINUTES, g.warning)
        assertEquals(nearby, g.suggestion!!.station)
        assertEquals(RouteAnalysis.pointAt(longRide, 1050.0).distanceTo(nearby.point), g.suggestion.distanceM, 1.0)

        // "Ir até lá": a route to the station, followed to it.
        val detour = fakeRoute(listOf(RouteAnalysis.pointAt(longRide, 1050.0), nearby.point), durationS = 60.0)
        routing.result = RouteResult.Success(detour)
        vm.onGiraGoToStation()
        assertEquals(nearby.point, routing.requests.last().second)
        assertEquals(nearby, vm.uiState.value.gira!!.swapStation)
        fix(nearby.point, afterS = 60.0)
        assertTrue(vm.uiState.value.gira!!.atSwapStation)
        assertTrue(vm.uiState.value.active)

        // "Já troquei": a new 45 minutes, and back to the planned route a little ahead of where it was left.
        routing.result = RouteResult.Success(fakeRoute(listOf(nearby.point, RouteAnalysis.pointAt(longRide, 1300.0)), durationS = 60.0))
        vm.onGiraSwapped()
        g = vm.uiState.value.gira!!
        assertEquals(0.0, g.elapsedS, 0.0)
        assertEquals(1, g.swaps)
        assertNull(g.swapStation)
        assertEquals(1300.0, routing.requests.last().second.distanceTo(origin), 3.0)
    }

    @Test
    fun `no warning when the ride ends in time, and the times that matter`() {
        assertNull(GiraPeriod.warning(elapsedS = 35 * 60.0, remainingRideS = 5 * 60.0))
        assertEquals(GiraWarning.TEN_MINUTES, GiraPeriod.warning(35 * 60.0, 20 * 60.0))
        assertEquals(GiraWarning.FIVE_MINUTES, GiraPeriod.warning(41 * 60.0, null))
        assertEquals(GiraWarning.OVER, GiraPeriod.warning(46 * 60.0, null))
        assertNull(GiraPeriod.warning(20 * 60.0, null))
    }

    @Test
    fun `the map shows what is left of the route and the stations that matter now`() {
        val trip = GiraTrip(pickUp, pickUp, walkToPickUpM = 0.0, walkFromDropOffM = null)
        val state = NavigationUiState(
            active = true,
            remaining = straight,
            destination = straight.points.last(),
            gira = GiraNavigation(trip, startedAtMs = 0, suggestion = NearbyStation(nearby, 300.0), warning = GiraWarning.FIVE_MINUTES),
        )
        val markers = Json.parseToJsonElement(navigationOverlay(state, RoutePalette.Light).markers).jsonObject
            .getValue("features").jsonArray.map { it.jsonObject.getValue("properties").jsonObject }
        val labels = markers.filter { it.getValue("kind").jsonPrimitive.content == "gira_stop" }.map { it.getValue("label").jsonPrimitive.content }
        assertEquals(listOf("GIRA 205", "GIRA 101"), labels)
        assertTrue(markers.none { it.getValue("kind").jsonPrimitive.content == "destination" })
        assertNotNull(Json.parseToJsonElement(navigationOverlay(state, RoutePalette.Light).routes))
    }
}
