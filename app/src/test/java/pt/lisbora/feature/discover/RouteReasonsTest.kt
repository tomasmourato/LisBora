package pt.lisbora.feature.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.saved.RoomSavedRoutesRepository
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.feature.planner.MAX_PLACE_MARKERS
import pt.lisbora.feature.planner.PlannerMode
import pt.lisbora.feature.planner.Endpoint
import pt.lisbora.feature.planner.RouteLabel
import pt.lisbora.feature.planner.fakeDiscovered
import pt.lisbora.feature.planner.fakeRoute
import pt.lisbora.feature.planner.plannerOverlay
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RouteReasonsTest {

    private val line = (0..10).map { GeoPoint(38.700, -9.200 + it * 0.01) }

    @Test
    fun `a river ride says how much is by the river first`() {
        // ~8.7 km, half on a segregated track, almost flat.
        val route = fakeRoute(line, infra = List(10) { if (it < 5) EdgeInfra.SEGREGATED_TRACK else EdgeInfra.NONE })
        val discovered = fakeDiscovered(route, riversideM = 3200.0, segregatedM = route.distanceM / 2, calmShare = 0.9)
        assertEquals(
            listOf(RouteReason.Riverside(3200.0), RouteReason.Segregated(0.5), RouteReason.Flat, RouteReason.Calm(0.9)),
            RouteReasons.of(discovered, DiscoveryPurpose.RIVER).let { reasons ->
                reasons.map { if (it is RouteReason.Segregated) RouteReason.Segregated(Math.round(it.share * 100) / 100.0) else it }
            },
        )
    }

    @Test
    fun `culture counts museums and monuments once each, little scenery is left out`() {
        val museum = Poi(PoiKind.MUSEUM, "Museu do Oriente", line[2], 60)
        val monument = Poi(PoiKind.MONUMENT, "Padrão dos Descobrimentos", line[4], 80)
        val discovered = fakeDiscovered(
            fakeRoute(line, elevations = List(11) { it * 10.0 }),
            anchors = listOf(museum),
            pois = listOf(museum, monument, museum.copy(point = line[3])),
            riversideM = 300.0,
        )
        val reasons = RouteReasons.of(discovered, DiscoveryPurpose.CULTURE)
        assertEquals(RouteReason.Culture(2), reasons.first())
        assertTrue(reasons.none { it is RouteReason.Riverside })
        assertTrue(reasons.size <= RouteReasons.MAX)
    }

    @Test
    fun `descents prefer routes that finish lower`() {
        val downhill = fakeRoute(line, elevations = List(11) { 100.0 - it * 8 })
        val reasons = RouteReasons.of(fakeDiscovered(downhill, netDescentM = 80.0), DiscoveryPurpose.DESCENTS)
        assertEquals(listOf(RouteReason.EndsLower(80.0), RouteReason.Descent(80.0), RouteReason.GentleClimbs), reasons.take(3))
    }

    @Test
    fun `only the recommended route is labelled`() {
        val options = DiscoverOptions.from(listOf(fakeDiscovered(fakeRoute(line)), fakeDiscovered(fakeRoute(line, durationS = 300.0))), DiscoveryPurpose.LEISURE, null)
        assertEquals(listOf(RouteLabel.RECOMMENDED), options[0].option.labels)
        assertTrue(options[1].option.labels.isEmpty())
    }

    @Test
    fun `the map shows the start and the places of the selected route, named`() {
        val viewpoint = Poi(PoiKind.VIEWPOINT, "Miradouro de Santa Luzia", line[5], 70)
        val discovered = fakeDiscovered(fakeRoute(line), anchors = listOf(viewpoint))
        val mode = PlannerMode.Discover(
            setup = DiscoverSetup(start = Endpoint.MyLocation),
            results = DiscoverResults.Ready(DiscoverOptions.from(listOf(discovered), DiscoveryPurpose.SCENERY, null)),
        )
        val markers = Json.parseToJsonElement(plannerOverlay(mode, RoutePalette.Light).markers).jsonObject.getValue("features").jsonArray
            .map { it.jsonObject.getValue("properties").jsonObject }
        val poi = markers.single { it.getValue("kind").jsonPrimitive.content == "poi" }
        assertEquals("Miradouro de Santa Luzia", poi.getValue("label").jsonPrimitive.content)
        // A loop: where it starts, and no destination.
        assertEquals(1, markers.count { it.getValue("kind").jsonPrimitive.content == "origin" })
        assertTrue(markers.none { it.getValue("kind").jsonPrimitive.content == "destination" })
        assertTrue(MAX_PLACE_MARKERS >= 1)
    }

    @Test
    fun `a GIRA ride shows the stations, the one it uses named, and not the ends of the route again`() {
        val station = GiraStation(101, "Bicicletas Gira Estação 101", line[0])
        val other = GiraStation(102, "Bicicletas Gira Estação 102", line[8])
        val mode = PlannerMode.Discover(
            setup = DiscoverSetup(start = Endpoint.MyLocation, gira = true),
            results = DiscoverResults.Ready(
                DiscoverOptions.from(listOf(fakeDiscovered(fakeRoute(line))), DiscoveryPurpose.LEISURE, null),
                gira = GiraTrip(station, station, walkToPickUpM = 150.0, walkFromDropOffM = null),
            ),
        )
        val markers = Json.parseToJsonElement(plannerOverlay(mode, RoutePalette.Light, listOf(station, other)).markers).jsonObject
            .getValue("features").jsonArray.map { it.jsonObject.getValue("properties").jsonObject }
        assertEquals(2, markers.count { it.getValue("kind").jsonPrimitive.content == "gira" })
        val stop = markers.single { it.getValue("kind").jsonPrimitive.content == "gira_stop" }
        assertEquals("GIRA 101", stop.getValue("label").jsonPrimitive.content)
        assertTrue(markers.none { it.getValue("kind").jsonPrimitive.content in setOf("origin", "destination") })
    }

    @Test
    fun `saved stops are stored compactly and still match the route`() {
        val stops = listOf(GeoPoint(38.7056123, -9.1452987), GeoPoint(38.71, -9.13), GeoPoint(38.7056123, -9.1452987))
        val text = RoomSavedRoutesRepository.encodeStops(stops)
        assertEquals("38.705612,-9.145299;38.710000,-9.130000;38.705612,-9.145299", text)
        val decoded = RoomSavedRoutesRepository.decodeStops(text)!!
        val saved = SavedRoute(1, DiscoveryPurpose.RIVER, emptySet(), decoded, false, 1000.0, 300.0, 0.0, null)
        assertTrue(saved.isLoop)
        assertNotNull(listOf(saved).matching(fakeDiscovered(fakeRoute(line), stops = stops)))
        assertNull(listOf(saved).matching(fakeDiscovered(fakeRoute(line), stops = stops.take(2))))
        assertNull(RoomSavedRoutesRepository.decodeStops("38.7,-9.1;oops"))
        assertNull(RoomSavedRoutesRepository.decodeStops("38.7,-9.1"))
    }
}
