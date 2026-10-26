package pt.lisbora.feature.planner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.feature.map.MapMarker
import pt.lisbora.feature.map.RouteGeoJson
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.routing.eta.BikeType
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory

class RouteOverlayTest {

    private val palette = RoutePalette.Light
    private val line = listOf(GeoPoint(38.700, -9.200), GeoPoint(38.700, -9.190), GeoPoint(38.700, -9.180), GeoPoint(38.700, -9.170))

    private fun features(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject.getValue("features").jsonArray.map { it.jsonObject }

    private fun JsonObject.property(key: String) = getValue("properties").jsonObject.getValue(key).jsonPrimitive

    @Test
    fun `labels say what sets each alternative apart`() {
        val best = fakeRoute(line, durationS = 900.0, infra = List(3) { EdgeInfra.SEGREGATED_TRACK })
        val faster = fakeRoute(line, durationS = 700.0)
        val flatter = fakeRoute(line, durationS = 880.0, elevations = listOf(0.0, 0.0, 0.0, 0.0))
        val hilly = best.copy(ascentM = 40.0)
        val options = RouteOptions.from(listOf(hilly, faster, flatter), maxGradePercent = null)
        assertEquals(listOf(RouteLabel.RECOMMENDED), options[0].labels)
        assertEquals(listOf(RouteLabel.FASTEST, RouteLabel.FLATTEST), options[1].labels)
        assertTrue(options[2].labels.isEmpty())
    }

    @Test
    fun `routes above the rider's maximum grade are flagged`() {
        val steep = fakeRoute(line).copy(maxGradePercent = 11.0)
        assertTrue(RouteOptions.from(listOf(steep), maxGradePercent = 8).single().aboveMaxGrade)
        assertTrue(!RouteOptions.from(listOf(steep), maxGradePercent = null).single().aboveMaxGrade)
    }

    @Test
    fun `the selected route is split into coloured runs, alternatives stay grey`() {
        val selected = fakeRoute(
            line,
            infra = listOf(EdgeInfra.SEGREGATED_TRACK, EdgeInfra.SEGREGATED_TRACK, EdgeInfra.NONE),
            modes = listOf(TravelMode.RIDE, TravelMode.RIDE, TravelMode.PUSH),
        )
        val other = fakeRoute(line)
        val options = RouteOptions.from(listOf(selected, other), null)
        val json = features(RouteGeoJson.routes(options, selected = 0, mode = RouteColorMode.INFRA, palette = palette))

        val alt = json.single { it.property("layer").content == "alt" }
        assertEquals(1, alt.property("index").int)
        assertEquals(palette.alternative, alt.property("color").content)
        val runs = json.filter { it.property("layer").content == "selected" }
        assertEquals(listOf(palette.infraColor(EdgeInfra.SEGREGATED_TRACK), palette.walk), runs.map { it.property("color").content })
        assertEquals(listOf("false", "true"), runs.map { it.property("dashed").content })
        // The first run covers two segments (three points), the walked one the last segment.
        assertEquals(listOf(3, 2), runs.map { it.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.size })
    }

    @Test
    fun `stress colouring follows each leg`() {
        val route = fakeRoute(line, stress = listOf(1, 4, 4))
        val runs = RouteGeoJson.runs(route, RouteColorMode.STRESS, palette)
        assertEquals(listOf(palette.stress[0], palette.stress[3]), runs.map { it.color })
        assertEquals(listOf(0 to 1, 1 to 3), runs.map { it.from to it.to })
    }

    @Test
    fun `the overlay follows the planner mode`() {
        val place = Place("Torre de Belém", PlaceCategory.MONUMENT, Belem)
        assertEquals(MapOverlay.EMPTY, plannerOverlay(PlannerMode.Browse, palette))

        val pin = features(plannerOverlay(PlannerMode.PlaceDetails(place, fromMap = true), palette).markers).single()
        assertEquals(MapMarker.Kind.PIN.name.lowercase(), pin.property("kind").content)

        val route = fakeRoute(line)
        val directions = PlannerMode.Directions(
            origin = Endpoint.MyLocation,
            destination = Endpoint.AtPlace(place),
            routes = RoutesState.Ready(RouteOptions.from(listOf(route), null)),
        )
        val overlay = plannerOverlay(directions, palette)
        val kinds = features(overlay.markers).map { it.property("kind").content }
        assertEquals(listOf("origin", "destination"), kinds)
        assertTrue(features(overlay.routes).isNotEmpty())
        // Waiting for a tap keeps showing what was there.
        assertEquals(overlay, plannerOverlay(PlannerMode.PickOnMap(SearchTarget.ORIGIN, directions), palette))
    }

    @Test
    fun `settings become routing preferences`() {
        val preferences = RidingSettings(bike = BikeType.ELECTRIC, maxGradePercent = 8, avoidTramTracks = true, allowFerries = false)
            .toRoutingPreferences()
        assertEquals(BikeType.ELECTRIC, preferences.rider.bike)
        assertEquals(8.0, preferences.maxGradePercent!!, 0.0)
        assertTrue(preferences.avoidTramTracks)
        assertTrue(!preferences.allowFerries)
    }
}
