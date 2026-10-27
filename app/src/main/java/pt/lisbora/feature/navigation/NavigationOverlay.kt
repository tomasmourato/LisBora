package pt.lisbora.feature.navigation

import pt.lisbora.feature.map.MapMarker
import pt.lisbora.feature.map.RouteGeoJson
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.planner.MapOverlay
import pt.lisbora.feature.planner.RouteColorMode
import pt.lisbora.feature.planner.RouteOptions
import pt.lisbora.feature.planner.mapLabel

/**
 * What to draw while riding: what is left of the route with its hazards, the destination and, on a GIRA
 * ride, the stations that matter now (where to take the bike, swap it, return it). Riding free there is no
 * route, only the station to swap bikes at. Pure, so it is unit-tested.
 */
fun navigationOverlay(state: NavigationUiState, palette: RoutePalette): MapOverlay {
    val option = state.remaining?.let { RouteOptions.from(listOf(it), null).singleOrNull() }
    val g = state.gira
    if (option == null && g == null) return MapOverlay.EMPTY
    val stations = buildList {
        if (g != null) {
            val trip = g.trip
            if (trip != null && !g.started) add(trip.pickUp)
            g.swapStation?.let(::add) ?: g.suggestion?.station?.let(::add)
            trip?.let { add(it.dropOff) }
        }
    }.distinct()
    val markers = buildList {
        option?.let { addAll(RouteGeoJson.hazardMarkers(it.hazards)) }
        stations.forEach { add(MapMarker(it.point, MapMarker.Kind.GIRA_STOP, label = it.mapLabel())) }
        // A GIRA ride ends at its station, already drawn.
        if (g == null) state.destination?.let { add(MapMarker(it, MapMarker.Kind.DESTINATION)) }
    }
    return MapOverlay(
        routes = option?.let { RouteGeoJson.routes(listOf(it), 0, RouteColorMode.INFRA, palette) } ?: RouteGeoJson.EMPTY,
        markers = RouteGeoJson.markers(markers, palette),
    )
}
