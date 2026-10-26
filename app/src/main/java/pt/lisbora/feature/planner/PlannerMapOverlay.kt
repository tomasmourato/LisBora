package pt.lisbora.feature.planner

import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.discover.DiscoverResults
import pt.lisbora.feature.map.MapMarker
import pt.lisbora.feature.map.RouteGeoJson
import pt.lisbora.feature.map.RoutePalette

/** GeoJSON the map draws for the planner: routes and markers. */
data class MapOverlay(val routes: String, val markers: String) {
    companion object {
        val EMPTY = MapOverlay(RouteGeoJson.EMPTY, RouteGeoJson.EMPTY)
    }
}

/**
 * What to draw on the map in each [PlannerMode]; [giraStations] are shown while planning a GIRA ride.
 * Pure, so it is unit-tested.
 */
fun plannerOverlay(mode: PlannerMode, palette: RoutePalette, giraStations: List<GiraStation> = emptyList()): MapOverlay = when (mode) {
    PlannerMode.Browse -> MapOverlay.EMPTY
    is PlannerMode.PickOnMap -> plannerOverlay(mode.previous, palette, giraStations)
    is PlannerMode.PlaceDetails -> MapOverlay(
        routes = RouteGeoJson.EMPTY,
        markers = RouteGeoJson.markers(
            listOf(MapMarker(mode.place.point, if (mode.fromMap) MapMarker.Kind.PIN else MapMarker.Kind.DESTINATION)),
            palette,
        ),
    )
    is PlannerMode.Directions -> {
        val options = (mode.routes as? RoutesState.Ready)?.options.orEmpty()
        val selected = mode.selectedOption
        // A typed origin is drawn where it was chosen; "my location" where the route starts (the blue dot is there).
        val origin = (mode.origin as? Endpoint.AtPlace)?.place?.point
            ?: selected?.route?.points?.firstOrNull()?.takeIf { mode.origin == Endpoint.MyLocation }
        val destination = (mode.destination as? Endpoint.AtPlace)?.place?.point ?: selected?.route?.points?.lastOrNull()
        val markers = buildList {
            selected?.let { addAll(RouteGeoJson.hazardMarkers(it.hazards)) }
            origin?.let { add(MapMarker(it, MapMarker.Kind.ORIGIN)) }
            destination?.let { add(MapMarker(it, MapMarker.Kind.DESTINATION)) }
        }
        MapOverlay(
            routes = if (options.isEmpty()) RouteGeoJson.EMPTY else RouteGeoJson.routes(options, mode.selected, mode.colorMode, palette),
            markers = RouteGeoJson.markers(markers, palette),
        )
    }
    is PlannerMode.Discover -> {
        val options = mode.options.map { it.option }
        val selected = mode.selectedOption
        val route = selected?.option?.route
        val gira = mode.setup.gira
        val trip = (mode.results as? DiscoverResults.Ready)?.gira
        // A GIRA ride starts and ends at its stations, drawn as such: the ends of the route are not repeated.
        val start = (mode.setup.start as? Endpoint.AtPlace)?.place?.point
            ?: route?.points?.firstOrNull()?.takeIf { mode.setup.start == Endpoint.MyLocation && !gira }
        val end = (mode.setup.destination as? Endpoint.AtPlace)?.place?.point
            ?: route?.points?.lastOrNull()?.takeIf { mode.setup.toDestination && !gira }
        val markers = buildList {
            if (gira) giraStations.forEach { add(MapMarker(it.point, if (it.hasBike) MapMarker.Kind.GIRA else MapMarker.Kind.GIRA_EMPTY)) }
            selected?.let { addAll(RouteGeoJson.hazardMarkers(it.option.hazards)) }
            trip?.let { t ->
                (if (t.returnsWhereTaken) listOf(t.pickUp) else listOf(t.pickUp, t.dropOff)).forEach { station ->
                    add(MapMarker(station.point, MapMarker.Kind.GIRA_STOP, label = station.mapLabel()))
                }
            }
            // The places that make the route worth riding, named on the map.
            selected?.places?.take(MAX_PLACE_MARKERS)?.forEach { add(MapMarker(it.point, MapMarker.Kind.POI, label = it.name)) }
            start?.let { add(MapMarker(it, MapMarker.Kind.ORIGIN)) }
            end?.let { add(MapMarker(it, MapMarker.Kind.DESTINATION)) }
        }
        MapOverlay(
            routes = if (options.isEmpty()) RouteGeoJson.EMPTY else RouteGeoJson.routes(options, mode.selected, mode.colorMode, palette),
            markers = RouteGeoJson.markers(markers, palette),
        )
    }
}

/** "GIRA 101" next to a station's marker: a brand and a number, the same in every language. */
private fun GiraStation.mapLabel(): String = number?.let { "GIRA $it" } ?: name

/** Named places of a discovered route drawn on the map (the ones it was built through first). */
const val MAX_PLACE_MARKERS = 8
