package pt.lisbora.feature.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.planner.InfraBar
import pt.lisbora.feature.planner.PlannerActions
import pt.lisbora.feature.planner.PlannerMode
import pt.lisbora.feature.planner.RouteDetails
import pt.lisbora.feature.planner.StartAndDetails
import pt.lisbora.feature.planner.formatDuration
import pt.lisbora.feature.planner.formatMetres
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryTweak

interface DiscoverActions {
    fun onDiscoverOpen()
    fun onDiscoverBack()
    fun onDiscoverDestinationChange(toDestination: Boolean)
    fun onDiscoverMinutes(minutes: Int)
    fun onDiscoverPurpose(purpose: DiscoveryPurpose)
    fun onDiscoverGira(on: Boolean)
    fun onImportGpx()
    fun onDiscoverSearch()
    fun onDiscoverAnother()
    fun onDiscoverTweak(tweak: DiscoveryTweak)
    fun onDiscoverEdit()
    fun onDiscoverSave()
    fun onSavedRouteOpen(route: SavedRoute)
    fun onSavedRouteRemove(route: SavedRoute)
}

/** Test tag of the button that starts a discovery. */
const val DISCOVER_SEARCH_TAG = "discover-search"

/**
 * Bottom panel of Descobrir: the choices (loop or destination, time, purpose) and the saved routes,
 * then the routes found, each with why it is worth riding, "Outra rota" and the quick adjustments.
 */
@Composable
fun DiscoverPanel(
    discover: PlannerMode.Discover,
    savedRoutes: List<SavedRoute>,
    maxGradePercent: Int?,
    palette: RoutePalette,
    maxHeight: Dp,
    actions: PlannerActions,
    modifier: Modifier = Modifier,
    giraStations: List<GiraStation> = emptyList(),
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(top = 12.dp, bottom = 12.dp)) {
            when (val results = discover.results) {
                DiscoverResults.None -> SetupContent(discover.setup, savedRoutes, giraStations, actions, Modifier.heightIn(max = maxHeight))
                DiscoverResults.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.discover_loading), style = MaterialTheme.typography.bodyLarge)
                }
                is DiscoverResults.Failed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(results.reason.textRes), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (results.reason == DiscoverFailure.LOCATION_UNAVAILABLE) {
                            Button(onClick = actions::onChooseOrigin) { Text(stringResource(R.string.directions_choose_origin)) }
                        } else if (results.reason.isGpx) {
                            Button(onClick = actions::onImportGpx) { Text(stringResource(R.string.discover_import_other)) }
                        } else if (results.reason != DiscoverFailure.SAVED_UNAVAILABLE) {
                            Button(onClick = actions::onRetry) { Text(stringResource(R.string.routes_retry)) }
                        }
                        OutlinedButton(onClick = actions::onDiscoverEdit) { Text(stringResource(R.string.discover_edit)) }
                    }
                }
                is DiscoverResults.Ready -> ResultsContent(
                    discover = discover,
                    ready = results,
                    saved = discover.selectedOption?.let { savedRoutes.matching(it.discovered) } != null,
                    maxGradePercent = maxGradePercent,
                    palette = palette,
                    maxDetailsHeight = maxHeight * 0.7f,
                    actions = actions,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupContent(
    setup: DiscoverSetup,
    savedRoutes: List<SavedRoute>,
    giraStations: List<GiraStation>,
    actions: DiscoverActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEachIndexed { i, toDestination ->
                SegmentedButton(
                    selected = setup.toDestination == toDestination,
                    onClick = { actions.onDiscoverDestinationChange(toDestination) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) {
                    Text(stringResource(if (toDestination) R.string.discover_to_destination else R.string.discover_loop), maxLines = 1)
                }
            }
        }
        GiraRow(setup.gira, giraStations, stringResource(R.string.discover_gira_hint), onChange = actions::onDiscoverGira)
        ChoiceSection(stringResource(R.string.discover_time)) {
            DiscoverSetup.MINUTE_OPTIONS.forEach { minutes ->
                FilterChip(selected = setup.minutes == minutes, onClick = { actions.onDiscoverMinutes(minutes) }, label = { Text(minutesLabel(minutes)) })
            }
        }
        ChoiceSection(stringResource(R.string.discover_purpose)) {
            DiscoveryPurpose.entries.forEach { purpose ->
                FilterChip(selected = setup.purpose == purpose, onClick = { actions.onDiscoverPurpose(purpose) }, label = { Text(stringResource(purpose.labelRes())) })
            }
        }
        Button(
            onClick = actions::onDiscoverSearch,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(DISCOVER_SEARCH_TAG),
        ) {
            Icon(painterResource(R.drawable.ic_explore), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discover_search))
        }
        if (savedRoutes.isNotEmpty()) {
            Column {
                Text(stringResource(R.string.discover_saved_title), style = MaterialTheme.typography.titleSmall)
                savedRoutes.forEach { route ->
                    SavedRouteRow(route, onOpen = { actions.onSavedRouteOpen(route) }, onRemove = { actions.onSavedRouteRemove(route) })
                }
            }
        }
        OutlinedButton(onClick = actions::onImportGpx, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_map), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discover_import_gpx))
        }
    }
}

/** "Bicicleta GIRA" with what it changes ([hint]); once on, whether the counts are live. Also in "Pedalar". */
@Composable
internal fun GiraRow(on: Boolean, stations: List<GiraStation>, hint: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange),
    ) {
        Icon(painterResource(R.drawable.ic_bike), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.discover_gira), style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (on && stations.isNotEmpty()) {
                val withBikes = stations.count { it.hasBike }
                Text(
                    if (stations.any { it.isLive }) {
                        pluralStringResource(R.plurals.discover_gira_live, withBikes, withBikes)
                    } else {
                        stringResource(R.string.discover_gira_offline)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** Where the GIRA bike is taken and returned. */
@Composable
private fun GiraLine(trip: GiraTrip, modifier: Modifier = Modifier) {
    val pickUp = if (trip.walkToPickUpM < NEAR_STATION_M) {
        stringResource(R.string.discover_gira_pickup_here, trip.pickUp.label())
    } else {
        stringResource(R.string.discover_gira_pickup, trip.pickUp.label(), formatLength(trip.walkToPickUpM))
    }
    val bikes = trip.pickUp.bikes?.let { " (" + pluralStringResource(R.plurals.discover_gira_bikes, it, it) + ")" }.orEmpty()
    val walkFrom = trip.walkFromDropOffM
    val dropOff = when {
        trip.returnsWhereTaken -> stringResource(R.string.discover_gira_return_same)
        walkFrom == null || walkFrom < NEAR_STATION_M -> stringResource(R.string.discover_gira_return_here, trip.dropOff.label())
        else -> stringResource(R.string.discover_gira_return, trip.dropOff.label(), formatLength(walkFrom))
    }
    val docks = trip.dropOff.freeDocks?.takeIf { !trip.returnsWhereTaken }
        ?.let { " (" + pluralStringResource(R.plurals.discover_gira_docks, it, it) + ")" }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_bike), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("$pickUp$bikes · $dropOff$docks", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceSection(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun SavedRouteRow(route: SavedRoute, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen),
    ) {
        Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.discover_saved_line,
                    stringResource(route.purpose.labelRes()),
                    formatLength(route.distanceM),
                    formatDuration(route.durationS),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                listOfNotNull(
                    stringResource(if (route.isLoop) R.string.discover_loop else R.string.discover_to_destination),
                    stringResource(R.string.discover_saved_gira).takeIf { route.gira },
                    route.highlight,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.discover_saved_remove))
        }
    }
}

@Composable
private fun ResultsContent(
    discover: PlannerMode.Discover,
    ready: DiscoverResults.Ready,
    saved: Boolean,
    maxGradePercent: Int?,
    palette: RoutePalette,
    maxDetailsHeight: Dp,
    actions: PlannerActions,
) {
    if (ready.directOnly) {
        Text(
            stringResource(R.string.discover_direct_only),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).height(IntrinsicSize.Max),
    ) {
        ready.options.forEachIndexed { i, option ->
            DiscoverCard(
                option = option,
                index = i,
                count = ready.options.size,
                selected = i == discover.selected,
                palette = palette,
                onClick = { actions.onRouteSelect(i) },
            )
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // A route that follows a GPX file is that route: no other one, no adjustments, nothing to save.
        if (!ready.directOnly && ready.imported == null) {
            AssistChip(
                onClick = actions::onDiscoverAnother,
                label = { Text(stringResource(R.string.discover_another)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
            DiscoveryTweak.entries.forEach { tweak ->
                FilterChip(
                    selected = tweak in discover.tweaks,
                    onClick = { actions.onDiscoverTweak(tweak) },
                    label = { Text(stringResource(tweak.labelRes())) },
                )
            }
        }
        if (ready.imported == null) AssistChip(
            onClick = actions::onDiscoverSave,
            label = { Text(stringResource(if (saved) R.string.discover_saved_label else R.string.discover_save)) },
            leadingIcon = {
                Icon(
                    painterResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_border),
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        AssistChip(
            onClick = actions::onDiscoverEdit,
            label = { Text(stringResource(R.string.discover_edit)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        )
    }
    ready.gira?.let { GiraLine(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
    val selected = discover.selectedOption ?: return
    val places = selected.places.mapNotNull { it.name }
    if (places.isNotEmpty()) {
        Text(
            stringResource(R.string.discover_passes, places.take(MAX_PLACES_LISTED).joinToString(", ") + if (places.size > MAX_PLACES_LISTED) "…" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    StartAndDetails(discover.detailsExpanded, actions)
    if (discover.detailsExpanded) {
        RouteDetails(
            option = selected.option,
            colorMode = discover.colorMode,
            maxGradePercent = maxGradePercent,
            palette = palette,
            onColorMode = actions::onColorMode,
            modifier = Modifier.heightIn(max = maxDetailsHeight).verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun DiscoverCard(option: DiscoverOption, index: Int, count: Int, selected: Boolean, palette: RoutePalette, onClick: () -> Unit) {
    val route = option.option.route
    val duration = formatDuration(route.durationS)
    val distance = formatLength(route.distanceM)
    val reasons = option.reasons.map { it.text() }
    val description = stringResource(R.string.route_option, index + 1, count) + ": $duration, $distance. " + reasons.joinToString(", ")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .width(CARD_WIDTH)
            .fillMaxHeight()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Tab
                this.selected = selected
            },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(duration, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (option.option.aboveMaxGrade) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            Text("$distance · ${stringResource(R.string.route_climb, formatMetres(route.ascentM))}", style = MaterialTheme.typography.bodyMedium)
            InfraBar(option.option, palette, Modifier.fillMaxWidth().height(6.dp))
            reasons.take(MAX_CARD_REASONS).forEach { reason ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(reason, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            option.option.labels.firstOrNull()?.let { label ->
                Text(stringResource(label.textRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val CARD_WIDTH = 216.dp

/** Closer than this, the rider is already at the GIRA station. */
private const val NEAR_STATION_M = 25.0
private const val MAX_CARD_REASONS = 3
private const val MAX_PLACES_LISTED = 4
