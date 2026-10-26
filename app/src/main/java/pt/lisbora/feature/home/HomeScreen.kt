package pt.lisbora.feature.home

import android.Manifest
import android.content.Context
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
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.LisboraMap
import pt.lisbora.feature.map.RoutePalette
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val planner by plannerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    // "My location" picked in the search before the permission was granted.
    var myLocationPending by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        if (myLocationPending) {
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
    val plannerActions = remember(plannerViewModel) {
        PlannerViewModelActions(plannerViewModel) {
            if (context.hasLocationPermission()) {
                plannerViewModel.onMyLocationChosen()
            } else {
                myLocationPending = true
                requestPermission()
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.onLocationPermissionChecked(context.hasLocationPermission()) }
    BackHandler(enabled = planner.mode != PlannerMode.Browse || planner.search != null || planner.preferencesOpen) {
        plannerViewModel.onBack()
    }

    val routePalette = if (darkTheme) RoutePalette.Dark else RoutePalette.Light
    val overlay = remember(planner.mode, routePalette) { plannerOverlay(planner.mode, routePalette) }

    HomeScreen(
        uiState = uiState,
        darkTheme = darkTheme,
        onDiscoverClick = viewModel::onDiscoverClick,
        onMessageShown = viewModel::onMessageShown,
        onLegendToggle = viewModel::onLegendToggle,
        onCycleNetworkVisibleChange = viewModel::onCycleNetworkVisibleChange,
        onSegmentDismiss = viewModel::onSegmentDismiss,
        onMyLocationClick = {
            if (context.hasLocationPermission()) viewModel.onMyLocationClick() else requestPermission()
        },
        planner = planner,
        plannerActions = plannerActions,
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
                pickMode = planner.mode is PlannerMode.PickOnMap,
                dimCycleNetwork = planner.mode is PlannerMode.Directions,
                onLongPress = plannerActions::onMapLongPress,
                onPointPicked = plannerActions::onMapPointPicked,
                onRouteClick = plannerActions::onRouteSelect,
                onCameraIdle = plannerActions::onMapCameraIdle,
                onCameraRequestHandled = plannerActions::onCameraHandled,
            )
        }
    }
}

private fun Context.hasLocationPermission(): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Map-first home screen. The map itself is a slot ([mapContent]) so the overlays can be tested
 * without MapLibre. What sits on top depends on the planner: the search pill and legend while browsing,
 * a place card, or the trip header and the routes panel while planning.
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
    planner: PlannerUiState = PlannerUiState(),
    plannerActions: PlannerActions = PlannerActionsAdapter(),
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
    val palette = if (darkTheme) CycleNetworkPalette.Dark else CycleNetworkPalette.Light
    val routePalette = if (darkTheme) RoutePalette.Dark else RoutePalette.Light
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
            when (mode) {
                is PlannerMode.Directions -> DirectionsHeader(mode, routePalette, plannerActions, measureTop)
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
                        CycleNetworkLegend(
                            meta = (uiState.map as? MapLoadState.Ready)?.data?.cycleNetworkMeta,
                            palette = palette,
                            cycleNetworkVisible = uiState.cycleNetworkVisible,
                            onVisibleChange = onCycleNetworkVisibleChange,
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
                SmallFloatingActionButton(
                    onClick = onMyLocationClick,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.semantics { contentDescription = myLocationLabel },
                ) {
                    Icon(painterResource(R.drawable.ic_my_location), contentDescription = null)
                }
                if (mode == PlannerMode.Browse) {
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
            when (mode) {
                is PlannerMode.Directions -> RoutesPanel(
                    directions = mode,
                    maxGradePercent = planner.settings.maxGradePercent,
                    palette = routePalette,
                    maxDetailsHeight = screenHeight * 0.45f,
                    actions = plannerActions,
                    modifier = measureBottom,
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
        planner.search?.let { search ->
            SearchOverlay(state = search, saved = planner.saved, recent = planner.recent, actions = plannerActions)
        }
        if (planner.preferencesOpen) {
            PreferencesSheet(
                settings = planner.settings,
                onChange = plannerActions::onSettingsChange,
                onDismiss = plannerActions::onPreferencesClose,
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
