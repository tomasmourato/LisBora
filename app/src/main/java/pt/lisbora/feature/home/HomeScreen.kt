package pt.lisbora.feature.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.lisbora.R
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.feature.discover.DiscoverHeader
import pt.lisbora.feature.discover.DiscoverPanel
import pt.lisbora.feature.discover.DiscoverResults
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.LisboraMap
import pt.lisbora.feature.map.PoiLayer
import pt.lisbora.feature.map.PoiPalette
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.navigation.KeepScreenOn
import pt.lisbora.feature.navigation.NavigationActions
import pt.lisbora.feature.navigation.NavigationActionsAdapter
import pt.lisbora.feature.navigation.NavigationBottom
import pt.lisbora.feature.navigation.NavigationMessage
import pt.lisbora.feature.navigation.NavigationTop
import pt.lisbora.feature.navigation.NavigationUiState
import pt.lisbora.feature.navigation.NavigationViewModel
import pt.lisbora.feature.navigation.RideSummaryPanel
import pt.lisbora.feature.navigation.navigationOverlay
import pt.lisbora.feature.planner.DirectionsHeader
import pt.lisbora.feature.planner.PickOnMapBanner
import pt.lisbora.feature.planner.PlaceCard
import pt.lisbora.feature.planner.PlannerActions
import pt.lisbora.feature.planner.PlannerActionsAdapter
import pt.lisbora.feature.planner.PlannerMode
import pt.lisbora.feature.planner.PlannerUiState
import pt.lisbora.feature.planner.PlannerViewModel
import pt.lisbora.feature.planner.PlannerViewModelActions
import pt.lisbora.feature.planner.PreferencesSheet
import pt.lisbora.feature.planner.RoutesPanel
import pt.lisbora.feature.planner.SearchOverlay
import pt.lisbora.feature.planner.displayName
import pt.lisbora.feature.planner.plannerOverlay
import pt.lisbora.feature.planner.samePlace
import pt.lisbora.ui.theme.LisboraTheme

/** Paddings handed to the map: [controls] for MapLibre's compass and attribution, [camera] for framing routes. */
data class MapInsets(val controls: PaddingValues, val camera: PaddingValues)

@Composable
fun HomeRoute(
    viewModel: HomeViewModel = hiltViewModel(),
    plannerViewModel: PlannerViewModel = hiltViewModel(),
    navigationViewModel: NavigationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val planner by plannerViewModel.uiState.collectAsStateWithLifecycle()
    val navigation by navigationViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    // "My location" picked in the search before the permission was granted.
    var myLocationPending by remember { mutableStateOf(false) }
    // "Iniciar" tapped before the permission was granted.
    var navigationPending by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val startNavigation = { plannerViewModel.navigationRequest()?.let(navigationViewModel::start) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        if (navigationPending) {
            navigationPending = false
            viewModel.onLocationPermissionResult(granted)
            if (granted) startNavigation()
        } else if (myLocationPending) {
            myLocationPending = false
            if (granted) {
                viewModel.onLocationPermissionChecked(true)
                plannerViewModel.onMyLocationChosen()
            } else {
                viewModel.onLocationPermissionResult(false)
            }
        } else {
            viewModel.onLocationPermissionResult(granted)
        }
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val gpxImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { plannerViewModel.onGpxImport(it.toString()) }
    }
    val plannerActions = remember(plannerViewModel, navigationViewModel, gpxImportLauncher) {
        PlannerViewModelActions(
            viewModel = plannerViewModel,
            onMyLocationRequested = {
                if (context.hasLocationPermission()) {
                    plannerViewModel.onMyLocationChosen()
                } else {
                    myLocationPending = true
                    requestPermission()
                }
            },
            onStartNavigationRequested = {
                if (context.hasLocationPermission()) {
                    startNavigation()
                } else {
                    navigationPending = true
                    requestPermission()
                }
            },
            // Other apps save GPX under many types: offer them all, the file is checked when read.
            onImportGpxRequested = { gpxImportLauncher.launch(arrayOf(GPX_MIME, "application/xml", "text/xml", "application/octet-stream", "*/*")) },
        )
    }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME)) { uri ->
        uri?.let { navigationViewModel.onExportGpx(it.toString()) }
    }
    val navigationActions = remember(navigationViewModel) {
        object : NavigationActions {
            override fun onNavigationStop() { confirmStop = true }
            override fun onRecenter() = navigationViewModel.onRecenter()
            override fun onGiraGoToStation() = navigationViewModel.onGiraGoToStation()
            override fun onGiraSwapped() = navigationViewModel.onGiraSwapped()
            override fun onExportGpx() = gpxLauncher.launch(gpxFileName())
            override fun onSummaryDismiss() = navigationViewModel.onSummaryDismiss()
        }
    }

    LaunchedEffect(Unit) { viewModel.onLocationPermissionChecked(context.hasLocationPermission()) }
    BackHandler(enabled = !navigation.showing && (planner.mode != PlannerMode.Browse || planner.search != null || planner.preferencesOpen)) {
        plannerViewModel.onBack()
    }
    BackHandler(enabled = navigation.showing) {
        if (navigation.active) confirmStop = true else navigationViewModel.onSummaryDismiss()
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.nav_stop_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    navigationViewModel.onStop()
                }) { Text(stringResource(R.string.nav_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.nav_keep_going)) } },
        )
    }

    val routePalette = if (darkTheme) RoutePalette.Dark else RoutePalette.Light
    val plannerMapOverlay = remember(planner.mode, routePalette, planner.giraStations) {
        plannerOverlay(planner.mode, routePalette, planner.giraStations)
    }
    val navigationMapOverlay = remember(navigation.remaining, navigation.gira, navigation.destination, routePalette) {
        navigationOverlay(navigation, routePalette)
    }
    val overlay = if (navigation.active) navigationMapOverlay else plannerMapOverlay

    HomeScreen(
        uiState = uiState,
        darkTheme = darkTheme,
        onDiscoverClick = plannerActions::onDiscoverOpen,
        onMessageShown = viewModel::onMessageShown,
        onLegendToggle = viewModel::onLegendToggle,
        onCycleNetworkVisibleChange = viewModel::onCycleNetworkVisibleChange,
        onPoiLayerChange = viewModel::onPoiLayerChange,
        onSegmentDismiss = viewModel::onSegmentDismiss,
        onMyLocationClick = {
            if (context.hasLocationPermission()) viewModel.onMyLocationClick() else requestPermission()
        },
        planner = planner,
        plannerActions = plannerActions,
        navigation = navigation,
        navigationActions = navigationActions,
        onNavigationMessageShown = navigationViewModel::onMessageShown,
        onCheckDataUpdates = viewModel::onCheckDataUpdates,
        onRestart = context::restartApp,
    ) { insets ->
        val map = uiState.map
        if (map is MapLoadState.Ready) {
            LisboraMap(
                styleJson = if (darkTheme) map.data.darkStyle else map.data.lightStyle,
                cycleNetworkVisible = uiState.cycleNetworkVisible,
                selectedSegmentId = uiState.selectedSegment?.id,
                locationEnabled = uiState.locationPermissionGranted,
                recenterRequest = uiState.recenterRequest,
                contentPadding = insets.controls,
                onSegmentClick = viewModel::onSegmentClick,
                onRecenterResult = viewModel::onRecenterResult,
                modifier = Modifier.fillMaxSize(),
                routesGeoJson = overlay.routes,
                markersGeoJson = overlay.markers,
                cameraRequest = planner.camera,
                cameraPadding = insets.camera,
                pickMode = planner.mode is PlannerMode.PickOnMap && !navigation.active,
                dimCycleNetwork = navigation.active || planner.mode is PlannerMode.Directions ||
                    (planner.mode as? PlannerMode.Discover)?.results is DiscoverResults.Ready,
                poiLayers = uiState.poiLayers,
                followUser = navigation.active,
                followRequest = navigation.followRequest,
                onLongPress = plannerActions::onMapLongPress,
                onPointPicked = plannerActions::onMapPointPicked,
                onRouteClick = plannerActions::onRouteSelect,
                onCameraIdle = plannerActions::onMapCameraIdle,
                onCameraRequestHandled = plannerActions::onCameraHandled,
            )
        }
    }
}

private const val GPX_MIME = "application/gpx+xml"

/** "lisbora-20261026-1530.gpx" */
private fun gpxFileName(): String =
    "lisbora-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".gpx"

/**
 * Starts the app again in a new process, so newer data downloaded in the background takes over: the
 * data pack is only switched at start-up, before the map, the router and the search read it.
 */
private fun Context.restartApp() {
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.component ?: return
    startActivity(Intent.makeRestartActivityTask(launch))
    Runtime.getRuntime().exit(0)
}

private fun Context.hasLocationPermission(): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Map-first home screen. The map itself is a slot ([mapContent]) so the overlays can be tested
 * without MapLibre. What sits on top depends on the planner: the search pill and legend while browsing,
 * a place card, or the trip header and the routes panel while planning; while riding, the navigation
 * (next instruction, time left) replaces all of it, and its summary at the end.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    darkTheme: Boolean,
    onDiscoverClick: () -> Unit,
    onMessageShown: () -> Unit,
    onLegendToggle: () -> Unit,
    onCycleNetworkVisibleChange: (Boolean) -> Unit,
    onSegmentDismiss: () -> Unit,
    onMyLocationClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPoiLayerChange: (PoiLayer, Boolean) -> Unit = { _, _ -> },
    planner: PlannerUiState = PlannerUiState(),
    plannerActions: PlannerActions = PlannerActionsAdapter(),
    navigation: NavigationUiState = NavigationUiState(),
    navigationActions: NavigationActions = NavigationActionsAdapter(),
    onNavigationMessageShown: () -> Unit = {},
    onCheckDataUpdates: () -> Unit = {},
    onRestart: () -> Unit = {},
    mapContent: @Composable (insets: MapInsets) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.pendingMessage?.let { stringResource(it.textRes) }
    LaunchedEffect(uiState.pendingMessage) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    val plannerMessage = planner.message?.let { stringResource(it.textRes) }
    LaunchedEffect(planner.message) {
        if (plannerMessage != null) {
            snackbarHostState.showSnackbar(plannerMessage)
            plannerActions.onMessageShown()
        }
    }
    val navigationMessage = navigation.message?.let {
        stringResource(if (it == NavigationMessage.GPX_SAVED) R.string.nav_gpx_saved else R.string.nav_gpx_failed)
    }
    LaunchedEffect(navigation.message) {
        if (navigationMessage != null) {
            snackbarHostState.showSnackbar(navigationMessage)
            onNavigationMessageShown()
        }
    }
    if (navigation.active) KeepScreenOn()
    val palette = if (darkTheme) CycleNetworkPalette.Dark else CycleNetworkPalette.Light
    val routePalette = if (darkTheme) RoutePalette.Dark else RoutePalette.Light
    val poiPalette = if (darkTheme) PoiPalette.Dark else PoiPalette.Light
    val mode = planner.mode

    // Heights of what covers the map at the top and at the bottom, measured so the map keeps its
    // controls visible and frames routes in the free area between them.
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val statusBars = WindowInsets.statusBars.asPaddingValues()
    val navigationBars = WindowInsets.navigationBars.asPaddingValues()
    val top = with(density) { topPx.toDp() }
    val bottom = with(density) { bottomPx.toDp() }
    val insets = MapInsets(
        controls = PaddingValues(
            start = 16.dp,
            top = max(top, statusBars.calculateTopPadding() + 64.dp) + 16.dp,
            bottom = max(bottom, navigationBars.calculateBottomPadding()) + 8.dp,
        ),
        camera = PaddingValues(
            start = 40.dp,
            end = 40.dp,
            top = max(top, statusBars.calculateTopPadding()) + 24.dp,
            bottom = max(bottom, navigationBars.calculateBottomPadding()) + 24.dp,
        ),
    )

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val screenHeight = maxHeight
        mapContent(insets)

        when (val map = uiState.map) {
            MapLoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            MapLoadState.Failed -> Text(
                text = stringResource(R.string.message_map_failed),
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            is MapLoadState.Ready -> if (!map.data.hasBasemap) {
                Text(
                    text = stringResource(R.string.map_no_basemap),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
        }

        // --- top ---------------------------------------------------------------------------------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val measureTop = Modifier.fillMaxWidth().onSizeChanged { size ->
                topPx = size.height + with(density) { (statusBars.calculateTopPadding() + 12.dp).roundToPx() }
            }
            if (navigation.showing) {
                if (navigation.active) NavigationTop(navigation, navigationActions, measureTop)
            } else when (mode) {
                is PlannerMode.Directions -> DirectionsHeader(mode, routePalette, plannerActions, measureTop)
                is PlannerMode.Discover -> DiscoverHeader(
                    discover = mode,
                    palette = routePalette,
                    onBack = plannerActions::onDiscoverBack,
                    onEndpointClick = plannerActions::onEndpointClick,
                    onPreferences = plannerActions::onPreferences,
                    modifier = measureTop,
                )
                is PlannerMode.PickOnMap -> PickOnMapBanner(mode.target, plannerActions::onPickCancel, measureTop)
                else -> {
                    SearchPill(
                        text = (mode as? PlannerMode.PlaceDetails)?.place?.displayName(),
                        onClick = plannerActions::onSearchOpen,
                        modifier = measureTop,
                    )
                    val legendLabel = stringResource(R.string.map_legend_toggle)
                    SmallFloatingActionButton(
                        onClick = onLegendToggle,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.semantics { contentDescription = legendLabel },
                    ) {
                        Icon(painterResource(R.drawable.ic_layers), contentDescription = null)
                    }
                    AnimatedVisibility(visible = uiState.legendExpanded) {
                        val mapData = (uiState.map as? MapLoadState.Ready)?.data
                        CycleNetworkLegend(
                            meta = mapData?.cycleNetworkMeta,
                            palette = palette,
                            cycleNetworkVisible = uiState.cycleNetworkVisible,
                            onVisibleChange = onCycleNetworkVisibleChange,
                            poiLayers = uiState.poiLayers.takeIf { mapData?.hasPois == true },
                            poiPalette = poiPalette,
                            onPoiLayerChange = onPoiLayerChange,
                        )
                    }
                }
            }
        }

        // --- bottom ------------------------------------------------------------------------------
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SnackbarHost(snackbarHostState)
                val myLocationLabel = stringResource(R.string.map_my_location)
                if (!navigation.showing) {
                    SmallFloatingActionButton(
                        onClick = onMyLocationClick,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.semantics { contentDescription = myLocationLabel },
                    ) {
                        Icon(painterResource(R.drawable.ic_my_location), contentDescription = null)
                    }
                }
                if (mode == PlannerMode.Browse && !navigation.showing) {
                    val discoverLabel = stringResource(R.string.home_discover)
                    ExtendedFloatingActionButton(
                        onClick = onDiscoverClick,
                        // M3 clears the label's semantics, so name the button explicitly for TalkBack.
                        modifier = Modifier.semantics { contentDescription = discoverLabel },
                        icon = { Icon(painterResource(R.drawable.ic_explore), contentDescription = null) },
                        text = { Text(discoverLabel) },
                    )
                }
            }
            val measureBottom = Modifier.fillMaxWidth().onSizeChanged { bottomPx = it.height }
            val summary = navigation.summary
            if (navigation.active) {
                NavigationBottom(navigation, navigationActions, measureBottom)
            } else if (summary != null) {
                RideSummaryPanel(summary, navigationActions, measureBottom)
            } else when (mode) {
                is PlannerMode.Directions -> RoutesPanel(
                    directions = mode,
                    maxGradePercent = planner.settings.maxGradePercent,
                    palette = routePalette,
                    maxDetailsHeight = screenHeight * 0.45f,
                    actions = plannerActions,
                    modifier = measureBottom,
                )
                is PlannerMode.Discover -> DiscoverPanel(
                    discover = mode,
                    savedRoutes = planner.savedRoutes,
                    maxGradePercent = planner.settings.maxGradePercent,
                    palette = routePalette,
                    maxHeight = screenHeight * 0.55f,
                    actions = plannerActions,
                    modifier = measureBottom,
                    giraStations = planner.giraStations,
                )
                is PlannerMode.PlaceDetails -> Box(measureBottom.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    PlaceCard(
                        place = mode.place,
                        fromMap = mode.fromMap,
                        isFavourite = planner.saved.any { it.kind == SavedKind.FAVOURITE && it.place.samePlace(mode.place) },
                        distanceM = planner.userLocation?.distanceTo(mode.place.point),
                        actions = plannerActions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> Box(measureBottom.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    uiState.selectedSegment?.let { segment ->
                        SegmentCard(segment = segment, palette = palette, onDismiss = onSegmentDismiss, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // --- full-screen layers ------------------------------------------------------------------
        if (navigation.showing) return@BoxWithConstraints
        planner.search?.let { search ->
            SearchOverlay(state = search, saved = planner.saved, recent = planner.recent, actions = plannerActions)
        }
        if (planner.preferencesOpen) {
            PreferencesSheet(
                settings = planner.settings,
                onChange = plannerActions::onSettingsChange,
                onDismiss = plannerActions::onPreferencesClose,
                dataInfo = uiState.dataInfo,
                dataStatus = uiState.dataStatus,
                onCheckDataUpdates = onCheckDataUpdates,
                onRestart = onRestart,
            )
        }
    }
}

@Composable
private fun SearchPill(text: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text ?: stringResource(R.string.home_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = if (text == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "Claro")
@Preview(name = "Escuro", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenPreview() {
    LisboraTheme {
        HomeScreen(
            uiState = HomeUiState(legendExpanded = true),
            darkTheme = isSystemInDarkTheme(),
            onDiscoverClick = {},
            onMessageShown = {},
            onLegendToggle = {},
            onCycleNetworkVisibleChange = {},
            onSegmentDismiss = {},
            onMyLocationClick = {},
        ) {}
    }
}
