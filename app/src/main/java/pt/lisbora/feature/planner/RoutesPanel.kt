package pt.lisbora.feature.planner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.map.hexColor

interface RoutesActions {
    fun onRouteSelect(index: Int)
    fun onRetry()
    fun onToggleDetails()
    fun onColorMode(mode: RouteColorMode)
    fun onChooseOrigin()
    fun onStartNavigation()
}

/** Bottom panel while planning: the alternatives side by side, then the selected route in detail. */
@Composable
fun RoutesPanel(
    directions: PlannerMode.Directions,
    maxGradePercent: Int?,
    palette: RoutePalette,
    maxDetailsHeight: Dp,
    actions: RoutesActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(top = 8.dp, bottom = 12.dp)) {
            when (val routes = directions.routes) {
                RoutesState.Idle -> PanelMessage(stringResource(R.string.routes_waiting))
                RoutesState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.routes_loading), style = MaterialTheme.typography.bodyLarge)
                }
                is RoutesState.Failed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(routes.reason.textRes), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (routes.reason == RouteFailure.LOCATION_UNAVAILABLE) {
                            Button(onClick = actions::onChooseOrigin) { Text(stringResource(R.string.directions_choose_origin)) }
                        }
                        OutlinedButton(onClick = actions::onRetry) { Text(stringResource(R.string.routes_retry)) }
                    }
                }
                is RoutesState.Ready -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    ) {
                        routes.options.forEachIndexed { i, option ->
                            RouteCard(
                                option = option,
                                index = i,
                                count = routes.options.size,
                                selected = i == directions.selected,
                                palette = palette,
                                onClick = { actions.onRouteSelect(i) },
                            )
                        }
                        if (routes.loadingAlternatives) {
                            Box(Modifier.widthIn(min = 72.dp).padding(top = 40.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    val selected = directions.selectedOption ?: return@Column
                    StartAndDetails(directions.detailsExpanded, actions)
                    if (directions.detailsExpanded) {
                        RouteDetails(
                            option = selected,
                            colorMode = directions.colorMode,
                            maxGradePercent = maxGradePercent,
                            palette = palette,
                            onColorMode = actions::onColorMode,
                            modifier = Modifier.heightIn(max = maxDetailsHeight).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

/** "Iniciar" (follow the selected route) and "Ver detalhes", under the route cards. */
@Composable
internal fun StartAndDetails(detailsExpanded: Boolean, actions: RoutesActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Button(onClick = actions::onStartNavigation) {
            Icon(painterResource(R.drawable.ic_directions), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nav_start))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = actions::onToggleDetails) {
            Icon(painterResource(if (detailsExpanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less), contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (detailsExpanded) R.string.route_details_hide else R.string.route_details_show))
        }
    }
}

@Composable
private fun PanelMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

@Composable
private fun RouteCard(option: RouteOption, index: Int, count: Int, selected: Boolean, palette: RoutePalette, onClick: () -> Unit) {
    val duration = formatDuration(option.durationS)
    val distance = formatLength(option.distanceM)
    val description = stringResource(R.string.route_option, index + 1, count) + ": $duration, $distance"
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .widthIn(min = 160.dp, max = 220.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Tab
                this.selected = selected
            },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(duration, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (option.aboveMaxGrade) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "$distance · ${stringResource(R.string.route_climb, formatMetres(option.route.ascentM))}",
                style = MaterialTheme.typography.bodyMedium,
            )
            InfraBar(option, palette, Modifier.fillMaxWidth().height(6.dp))
            Text(
                stringResource(R.string.route_infra_share, formatPercent(option.route.infraShare)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            option.labels.firstOrNull()?.let { label ->
                Text(
                    stringResource(label.textRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Stacked bar of the route's length by infrastructure, in map colours. */
@Composable
internal fun InfraBar(option: RouteOption, palette: RoutePalette, modifier: Modifier = Modifier) {
    StackedBar(option.infraShares.map { (infra, share) -> hexColor(palette.infraColor(infra)) to share }, modifier)
}

@Composable
private fun StackedBar(parts: List<Pair<Color, Double>>, modifier: Modifier = Modifier) {
    Row(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
        parts.filter { it.second > 0.0 }.forEach { (color, share) ->
            Box(Modifier.weight(share.toFloat()).fillMaxWidth().height(10.dp).background(color))
        }
    }
}

@Composable
internal fun RouteDetails(
    option: RouteOption,
    colorMode: RouteColorMode,
    maxGradePercent: Int?,
    palette: RoutePalette,
    onColorMode: (RouteColorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = option.route
    Column(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(
                R.string.route_summary,
                formatLength(route.distanceM),
                formatMetres(route.ascentM),
                formatMetres(route.descentM),
                formatGrade(route.maxGradePercent),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (option.aboveMaxGrade && maxGradePercent != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.route_above_max_grade, maxGradePercent), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        Section(stringResource(R.string.route_section_infra)) {
            InfraBar(option, palette, Modifier.fillMaxWidth().height(10.dp))
            option.infraShares.forEach { (infra, share) ->
                LegendLine(hexColor(palette.infraColor(infra)), stringResource(infra.labelRes()), formatPercent(share))
            }
            if (option.walkedM > 0) LegendLine(hexColor(palette.walk), stringResource(R.string.infra_walk), formatLength(option.walkedM))
        }

        Section(stringResource(R.string.route_section_stress)) {
            val ridden = option.stressLengths.sum().coerceAtLeast(1.0)
            StackedBar(option.stressLengths.mapIndexed { i, m -> hexColor(palette.stress[i]) to m / ridden }, Modifier.fillMaxWidth().height(10.dp))
            option.stressLengths.forEachIndexed { i, m ->
                if (m > 0) LegendLine(hexColor(palette.stress[i]), stringResource(stressLabelRes(i + 1)), formatPercent(m / ridden))
            }
        }

        Section(stringResource(R.string.route_section_profile)) {
            ElevationProfile(route, option.hazards, palette, Modifier.fillMaxWidth())
        }

        Section(stringResource(R.string.route_section_hazards)) {
            if (option.hazards.isEmpty()) {
                Text(stringResource(R.string.route_no_hazards), style = MaterialTheme.typography.bodyMedium)
            }
            option.hazards.forEach { hazard ->
                LegendLine(
                    color = hexColor(palette.hazardColor(hazard.kind)),
                    label = stringResource(R.string.hazard_where, hazard.title(), formatLength(hazard.startM)),
                    value = if (hazard.lengthM >= 1) formatLength(hazard.lengthM) else "",
                )
            }
        }

        Section(stringResource(R.string.route_color_by)) {
            val modes = RouteColorMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = mode == colorMode,
                        onClick = { onColorMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) {
                        Text(
                            stringResource(
                                when (mode) {
                                    RouteColorMode.INFRA -> R.string.route_color_infra
                                    RouteColorMode.GRADE -> R.string.route_color_grade
                                    RouteColorMode.STRESS -> R.string.route_color_stress
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (colorMode == RouteColorMode.GRADE) {
                listOf(R.string.grade_bucket_0, R.string.grade_bucket_1, R.string.grade_bucket_2, R.string.grade_bucket_3)
                    .forEachIndexed { i, label -> LegendLine(hexColor(palette.grade[i]), stringResource(label), "") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(R.string.route_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun LegendLine(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
