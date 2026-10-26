package pt.lisbora.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.map.hexColor
import pt.lisbora.feature.planner.EndpointField
import pt.lisbora.feature.planner.PlannerMode
import pt.lisbora.feature.planner.SearchTarget
import pt.lisbora.feature.planner.endpointName

/** Top of the map in Descobrir: where the ride starts and, towards a destination, where it ends. */
@Composable
fun DiscoverHeader(
    discover: PlannerMode.Discover,
    palette: RoutePalette,
    onBack: () -> Unit,
    onEndpointClick: (SearchTarget) -> Unit,
    onPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val setup = discover.setup
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.discover_back))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val imported = (discover.results as? DiscoverResults.Ready)?.imported
                Text(
                    text = if (discover.results == DiscoverResults.None) {
                        stringResource(R.string.discover_title)
                    } else if (imported != null) {
                        imported.ifBlank { stringResource(R.string.discover_gpx_untitled) }
                    } else {
                        stringResource(
                            if (setup.gira) R.string.discover_summary_gira else R.string.discover_summary,
                            minutesLabel(setup.minutes),
                            stringResource(setup.purpose.labelRes()),
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                )
                EndpointField(
                    label = stringResource(R.string.directions_origin),
                    value = setup.start?.let { endpointName(it) },
                    placeholder = stringResource(R.string.directions_choose_origin),
                    dot = hexColor(palette.origin),
                    onClick = { onEndpointClick(SearchTarget.ORIGIN) },
                )
                if (setup.toDestination) {
                    EndpointField(
                        label = stringResource(R.string.directions_destination),
                        value = setup.end?.let { endpointName(it) },
                        placeholder = stringResource(R.string.directions_choose_destination),
                        dot = hexColor(palette.destination),
                        onClick = { onEndpointClick(SearchTarget.DESTINATION) },
                    )
                }
            }
            IconButton(onClick = onPreferences) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = stringResource(R.string.directions_preferences))
            }
        }
    }
}
