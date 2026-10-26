package pt.lisbora.feature.planner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.feature.map.formatLength
import pt.lisbora.routing.search.Place
import java.util.Locale

/** Callbacks of the place card. */
interface PlaceActions {
    fun onDirectionsTo(place: Place)
    fun onDirectionsFrom(place: Place)
    fun onToggleFavourite(place: Place)
    fun onSaveAs(place: Place, kind: SavedKind)
    fun onPlaceDismiss()
}

/** A searched place or a pin dropped on the map, with what can be done with it. */
@Composable
fun PlaceCard(
    place: Place,
    fromMap: Boolean,
    isFavourite: Boolean,
    distanceM: Double?,
    actions: PlaceActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (fromMap && place.name.isBlank()) R.drawable.ic_place else place.category.iconRes()),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (place.name.isBlank()) stringResource(R.string.place_pin) else stringResource(place.category.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                PlaceMenu(place, actions)
                IconButton(onClick = actions::onPlaceDismiss) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.map_close))
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = place.displayName(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    place.context,
                    distanceM?.let { stringResource(R.string.place_distance, formatLength(it)) },
                    if (place.name.isBlank()) String.format(Locale.ROOT, "%.5f, %.5f", place.point.lat, place.point.lon) else null,
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.padding(top = 12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(end = 12.dp),
            ) {
                Button(onClick = { actions.onDirectionsTo(place) }) {
                    Icon(painterResource(R.drawable.ic_directions), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.place_directions))
                }
                OutlinedButton(onClick = { actions.onDirectionsFrom(place) }) {
                    Text(stringResource(R.string.place_start_here))
                }
                OutlinedIconToggleButton(checked = isFavourite, onCheckedChange = { actions.onToggleFavourite(place) }) {
                    Icon(
                        painterResource(if (isFavourite) R.drawable.ic_star else R.drawable.ic_star_border),
                        contentDescription = stringResource(if (isFavourite) R.string.place_saved else R.string.place_save),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceMenu(place: Place, actions: PlaceActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.place_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_set_home)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_home), contentDescription = null) },
                onClick = { open = false; actions.onSaveAs(place, SavedKind.HOME) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.place_set_work)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_work), contentDescription = null) },
                onClick = { open = false; actions.onSaveAs(place, SavedKind.WORK) },
            )
        }
    }
}
