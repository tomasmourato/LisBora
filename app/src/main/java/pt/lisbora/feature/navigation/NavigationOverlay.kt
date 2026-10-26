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
 * ride, the stations that matter now (where to take the bike, swap it, return it). Pure, so it is unit-tested.
 */
fun navigationOverlay(state: NavigationUiState, palette: RoutePalette): MapOverlay {
    val remaining = state.remaining ?: return MapOverlay.EMPTY
    val option = RouteOptions.from(listOf(remaining), null).singleOrNull() ?: return MapOverlay.EMPTY
    val g = state.gira
    val stations = buildList {
        if (g != null) {
            if (!g.started) add(g.trip.pickUp)
            g.swapStation?.let(::add) ?: g.suggestion?.station?.let(::add)
            add(g.trip.dropOff)
        }
    }.distinct()
    val markers = buildList {
        addAll(RouteGeoJson.hazardMarkers(option.hazards))
        stations.forEach { add(MapMarker(it.point, MapMarker.Kind.GIRA_STOP, label = it.mapLabel())) }
        // A GIRA ride ends at its station, already drawn.
        if (g == null) state.destination?.let { add(MapMarker(it, MapMarker.Kind.DESTINATION)) }
    }
    return MapOverlay(
        routes = RouteGeoJson.routes(listOf(option), 0, RouteColorMode.INFRA, palette),
        markers = RouteGeoJson.markers(markers, palette),
    )
}
