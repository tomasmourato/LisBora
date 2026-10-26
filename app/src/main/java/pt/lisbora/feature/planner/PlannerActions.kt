package pt.lisbora.feature.planner

import pt.lisbora.data.saved.SavedKind
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.feature.discover.DiscoverActions
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.Place

/** Everything the planner panels and the map can ask of the planner. */
interface PlannerActions : SearchActions, PlaceActions, DirectionsActions, RoutesActions, DiscoverActions {
    fun onSearchOpen()
    fun onPickCancel()
    fun onMapLongPress(point: GeoPoint)
    fun onMapPointPicked(point: GeoPoint)
    fun onMapCameraIdle(center: GeoPoint)
    fun onCameraHandled(id: Long)
    fun onPreferencesClose()
    fun onSettingsChange(transform: (RidingSettings) -> RidingSettings)
    fun onMessageShown()
}

/** No-op base for previews and UI tests; override what the test needs. */
open class PlannerActionsAdapter : PlannerActions {
    override fun onSearchOpen() = Unit
    override fun onPickCancel() = Unit
    override fun onMapLongPress(point: GeoPoint) = Unit
    override fun onMapPointPicked(point: GeoPoint) = Unit
    override fun onMapCameraIdle(center: GeoPoint) = Unit
    override fun onCameraHandled(id: Long) = Unit
    override fun onPreferencesClose() = Unit
    override fun onSettingsChange(transform: (RidingSettings) -> RidingSettings) = Unit
    override fun onMessageShown() = Unit
    override fun onQueryChange(query: String) = Unit
    override fun onSearchClose() = Unit
    override fun onPlaceChosen(place: Place) = Unit
    override fun onSavedChosen(saved: SavedPlace) = Unit
    override fun onMyLocation() = Unit
    override fun onPickOnMap() = Unit
    override fun onSetSaved(kind: SavedKind) = Unit
    override fun onRemoveSaved(saved: SavedPlace) = Unit
    override fun onClearRecent() = Unit
    override fun onDirectionsTo(place: Place) = Unit
    override fun onDirectionsFrom(place: Place) = Unit
    override fun onToggleFavourite(place: Place) = Unit
    override fun onSaveAs(place: Place, kind: SavedKind) = Unit
    override fun onPlaceDismiss() = Unit
    override fun onEndpointClick(target: SearchTarget) = Unit
    override fun onSwap() = Unit
    override fun onDirectionsClose() = Unit
    override fun onPreferences() = Unit
    override fun onRouteSelect(index: Int) = Unit
    override fun onRetry() = Unit
    override fun onToggleDetails() = Unit
    override fun onColorMode(mode: RouteColorMode) = Unit
    override fun onChooseOrigin() = Unit
    override fun onStartNavigation() = Unit
    override fun onDiscoverOpen() = Unit
    override fun onDiscoverBack() = Unit
    override fun onDiscoverDestinationChange(toDestination: Boolean) = Unit
    override fun onDiscoverMinutes(minutes: Int) = Unit
    override fun onDiscoverPurpose(purpose: DiscoveryPurpose) = Unit
    override fun onDiscoverGira(on: Boolean) = Unit
    override fun onImportGpx() = Unit
    override fun onDiscoverSearch() = Unit
    override fun onDiscoverAnother() = Unit
    override fun onDiscoverTweak(tweak: DiscoveryTweak) = Unit
    override fun onDiscoverEdit() = Unit
    override fun onDiscoverSave() = Unit
    override fun onSavedRouteOpen(route: SavedRoute) = Unit
    override fun onSavedRouteRemove(route: SavedRoute) = Unit
}

/**
 * Forwards to [PlannerViewModel]. "My location" goes through [onMyLocationRequested], which asks for
 * the location permission first when needed; "Iniciar" goes to [onStartNavigationRequested] (the
 * navigation has its own ViewModel and needs the permission too), and "Importar GPX" to
 * [onImportGpxRequested], which opens the file picker.
 */
class PlannerViewModelActions(
    private val viewModel: PlannerViewModel,
    private val onMyLocationRequested: () -> Unit,
    private val onStartNavigationRequested: () -> Unit = {},
    private val onImportGpxRequested: () -> Unit = {},
) : PlannerActions {
    override fun onSearchOpen() = viewModel.onSearchOpen()
    override fun onPickCancel() = viewModel.onPickCancel()
    override fun onMapLongPress(point: GeoPoint) = viewModel.onMapLongPress(point)
    override fun onMapPointPicked(point: GeoPoint) = viewModel.onMapPointPicked(point)
    override fun onMapCameraIdle(center: GeoPoint) = viewModel.onMapCameraIdle(center)
    override fun onCameraHandled(id: Long) = viewModel.onCameraHandled(id)
    override fun onPreferencesClose() = viewModel.onPreferencesClose()
    override fun onSettingsChange(transform: (RidingSettings) -> RidingSettings) = viewModel.onSettingsChange(transform)
    override fun onMessageShown() = viewModel.onMessageShown()
    override fun onQueryChange(query: String) = viewModel.onSearchQueryChange(query)
    override fun onSearchClose() = viewModel.onSearchClose()
    override fun onPlaceChosen(place: Place) = viewModel.onPlaceChosen(place)
    override fun onSavedChosen(saved: SavedPlace) = viewModel.onSavedPlaceChosen(saved)
    override fun onMyLocation() = onMyLocationRequested()
    override fun onPickOnMap() = viewModel.onPickOnMapChosen()
    override fun onSetSaved(kind: SavedKind) = viewModel.onSetSavedKind(kind)
    override fun onRemoveSaved(saved: SavedPlace) = viewModel.onRemoveSaved(saved)
    override fun onClearRecent() = viewModel.onClearRecent()
    override fun onDirectionsTo(place: Place) = viewModel.onDirectionsTo(place)
    override fun onDirectionsFrom(place: Place) = viewModel.onDirectionsFrom(place)
    override fun onToggleFavourite(place: Place) = viewModel.onToggleFavourite(place)
    override fun onSaveAs(place: Place, kind: SavedKind) = viewModel.onSaveAs(place, kind)
    override fun onPlaceDismiss() = viewModel.onPlaceDismiss()
    override fun onEndpointClick(target: SearchTarget) = viewModel.onEndpointClick(target)
    override fun onSwap() = viewModel.onSwapEndpoints()
    override fun onDirectionsClose() = viewModel.onDirectionsClose()
    override fun onPreferences() = viewModel.onPreferencesOpen()
    override fun onRouteSelect(index: Int) = viewModel.onRouteSelected(index)
    override fun onRetry() = viewModel.onRetryRoutes()
    override fun onToggleDetails() = viewModel.onDetailsToggle()
    override fun onColorMode(mode: RouteColorMode) = viewModel.onColorModeChange(mode)
    override fun onChooseOrigin() = viewModel.onEndpointClick(SearchTarget.ORIGIN)
    override fun onStartNavigation() = onStartNavigationRequested()
    override fun onDiscoverOpen() = viewModel.onDiscoverOpen()
    override fun onDiscoverBack() = viewModel.onDiscoverBack()
    override fun onDiscoverDestinationChange(toDestination: Boolean) = viewModel.onDiscoverDestinationChange(toDestination)
    override fun onDiscoverMinutes(minutes: Int) = viewModel.onDiscoverMinutesChange(minutes)
    override fun onDiscoverPurpose(purpose: DiscoveryPurpose) = viewModel.onDiscoverPurposeChange(purpose)
    override fun onDiscoverGira(on: Boolean) = viewModel.onDiscoverGiraChange(on)
    override fun onImportGpx() = onImportGpxRequested()
    override fun onDiscoverSearch() = viewModel.onDiscoverSearch()
    override fun onDiscoverAnother() = viewModel.onDiscoverAnother()
    override fun onDiscoverTweak(tweak: DiscoveryTweak) = viewModel.onDiscoverTweak(tweak)
    override fun onDiscoverEdit() = viewModel.onDiscoverEdit()
    override fun onDiscoverSave() = viewModel.onDiscoverSave()
    override fun onSavedRouteOpen(route: SavedRoute) = viewModel.onSavedRouteOpen(route)
    override fun onSavedRouteRemove(route: SavedRoute) = viewModel.onSavedRouteRemove(route)
}
