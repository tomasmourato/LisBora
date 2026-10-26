package pt.lisbora.feature.planner

import pt.lisbora.feature.map.MapMarker
import pt.lisbora.feature.map.RouteGeoJson
import pt.lisbora.feature.map.RoutePalette

/** GeoJSON the map draws for the planner: routes and markers. */
data class MapOverlay(val routes: String, val markers: String) {
    companion object {
        val EMPTY = MapOverlay(RouteGeoJson.EMPTY, RouteGeoJson.EMPTY)
    }
}

/** What to draw on the map in each [PlannerMode]. Pure, so it is unit-tested. */
fun plannerOverlay(mode: PlannerMode, palette: RoutePalette): MapOverlay = when (mode) {
    PlannerMode.Browse -> MapOverlay.EMPTY
    is PlannerMode.PickOnMap -> plannerOverlay(mode.previous, palette)
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
}
