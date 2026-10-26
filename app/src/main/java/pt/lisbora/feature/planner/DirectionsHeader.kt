package pt.lisbora.feature.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.map.hexColor

interface DirectionsActions {
    fun onEndpointClick(target: SearchTarget)
    fun onSwap()
    fun onDirectionsClose()
    fun onPreferences()
}

/** Origin and destination fields at the top of the map while planning a trip. */
@Composable
fun DirectionsHeader(
    directions: PlannerMode.Directions,
    palette: RoutePalette,
    actions: DirectionsActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = actions::onDirectionsClose) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.directions_close))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                EndpointField(
                    label = stringResource(R.string.directions_origin),
                    value = directions.origin?.let { endpointName(it) },
                    placeholder = stringResource(R.string.directions_choose_origin),
                    dot = hexColor(palette.origin),
                    onClick = { actions.onEndpointClick(SearchTarget.ORIGIN) },
                )
                Spacer(Modifier.padding(top = 4.dp))
                EndpointField(
                    label = stringResource(R.string.directions_destination),
                    value = directions.destination?.let { endpointName(it) },
                    placeholder = stringResource(R.string.directions_choose_destination),
                    dot = hexColor(palette.destination),
                    onClick = { actions.onEndpointClick(SearchTarget.DESTINATION) },
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = actions::onPreferences) {
                    Icon(painterResource(R.drawable.ic_tune), contentDescription = stringResource(R.string.directions_preferences))
                }
                IconButton(onClick = actions::onSwap) {
                    Icon(painterResource(R.drawable.ic_swap_vert), contentDescription = stringResource(R.string.directions_swap))
                }
            }
        }
    }
}

@Composable
internal fun endpointName(endpoint: Endpoint): String = when (endpoint) {
    Endpoint.MyLocation -> stringResource(R.string.search_my_location)
    is Endpoint.AtPlace -> endpoint.place.displayName()
}

@Composable
internal fun EndpointField(label: String, value: String?, placeholder: String, dot: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label: ${value ?: placeholder}" }
            .padding(horizontal = 14.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(dot, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value ?: placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown while waiting for a tap on the map. */
@Composable
fun PickOnMapBanner(target: SearchTarget, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(
                    when (target) {
                        SearchTarget.ORIGIN -> R.string.pick_origin
                        SearchTarget.DESTINATION -> R.string.pick_destination
                        SearchTarget.HOME -> R.string.pick_home
                        SearchTarget.WORK -> R.string.pick_work
                        SearchTarget.PLACE -> R.string.pick_place
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.pick_cancel), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}
