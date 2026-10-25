package pt.lisbora.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.lisbora.R
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.LisboraMap
import pt.lisbora.ui.theme.LisboraTheme

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> viewModel.onLocationPermissionResult(result.values.any { it }) }

    LaunchedEffect(Unit) { viewModel.onLocationPermissionChecked(context.hasLocationPermission()) }

    HomeScreen(
        uiState = uiState,
        darkTheme = darkTheme,
        onSearchClick = viewModel::onSearchClick,
        onDiscoverClick = viewModel::onDiscoverClick,
        onMessageShown = viewModel::onMessageShown,
        onLegendToggle = viewModel::onLegendToggle,
        onCycleNetworkVisibleChange = viewModel::onCycleNetworkVisibleChange,
        onSegmentDismiss = viewModel::onSegmentDismiss,
        onMyLocationClick = {
            if (context.hasLocationPermission()) {
                viewModel.onMyLocationClick()
            } else {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        },
    ) { contentPadding ->
        val map = uiState.map
        if (map is MapLoadState.Ready) {
            LisboraMap(
                styleJson = if (darkTheme) map.data.darkStyle else map.data.lightStyle,
                cycleNetworkVisible = uiState.cycleNetworkVisible,
                selectedSegmentId = uiState.selectedSegment?.id,
                locationEnabled = uiState.locationPermissionGranted,
                recenterRequest = uiState.recenterRequest,
                contentPadding = contentPadding,
                onSegmentClick = viewModel::onSegmentClick,
                onRecenterResult = viewModel::onRecenterResult,
                modifier = Modifier.fillMaxSize(),
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
 * without MapLibre; it receives the padding that keeps MapLibre's own controls clear of the overlays.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    darkTheme: Boolean,
    onSearchClick: () -> Unit,
    onDiscoverClick: () -> Unit,
    onMessageShown: () -> Unit,
    onLegendToggle: () -> Unit,
    onCycleNetworkVisibleChange: (Boolean) -> Unit,
    onSegmentDismiss: () -> Unit,
    onMyLocationClick: () -> Unit,
    modifier: Modifier = Modifier,
    mapContent: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.pendingMessage?.let { stringResource(it.textRes) }
    LaunchedEffect(uiState.pendingMessage) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    val palette = if (darkTheme) CycleNetworkPalette.Dark else CycleNetworkPalette.Light
    val statusBars = WindowInsets.statusBars.asPaddingValues()
    val navigationBars = WindowInsets.navigationBars.asPaddingValues()
    val mapPadding = PaddingValues(
        start = 16.dp,
        top = statusBars.calculateTopPadding() + 80.dp,
        bottom = navigationBars.calculateBottomPadding() + 8.dp,
    )

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        mapContent(mapPadding)

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

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchPill(onClick = onSearchClick, modifier = Modifier.fillMaxWidth())
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
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
            val discoverLabel = stringResource(R.string.home_discover)
            ExtendedFloatingActionButton(
                onClick = onDiscoverClick,
                // M3 clears the label's semantics, so name the button explicitly for TalkBack.
                modifier = Modifier.semantics { contentDescription = discoverLabel },
                icon = { Icon(painterResource(R.drawable.ic_explore), contentDescription = null) },
                text = { Text(discoverLabel) },
            )
            uiState.selectedSegment?.let { segment ->
                SegmentCard(
                    segment = segment,
                    palette = palette,
                    onDismiss = onSegmentDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                text = stringResource(R.string.home_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            onSearchClick = {},
            onDiscoverClick = {},
            onMessageShown = {},
            onLegendToggle = {},
            onCycleNetworkVisibleChange = {},
            onSegmentDismiss = {},
            onMyLocationClick = {},
        ) {}
    }
}
