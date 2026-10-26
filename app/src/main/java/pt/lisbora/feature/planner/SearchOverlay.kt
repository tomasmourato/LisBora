package pt.lisbora.feature.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.feature.map.formatLength
import pt.lisbora.routing.search.Place

/** Callbacks of the search screen. */
interface SearchActions {
    fun onQueryChange(query: String)
    fun onSearchClose()
    fun onPlaceChosen(place: Place)
    fun onSavedChosen(saved: SavedPlace)
    fun onMyLocation()
    fun onPickOnMap()
    fun onSetSaved(kind: SavedKind)
    fun onRemoveSaved(saved: SavedPlace)
    fun onClearRecent()
}

/**
 * Full-screen search over the map. With an empty query it lists Home, Work, saved and recent places
 * (and, when choosing an end of a trip, "My location" and "Choose on the map").
 */
@Composable
fun SearchOverlay(
    state: SearchUiState,
    saved: List<SavedPlace>,
    recent: List<Place>,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.target) { focus.requestFocus() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            ) {
                IconButton(onClick = actions::onSearchClose) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.search_back))
                }
                TextField(
                    value = state.query,
                    onValueChange = actions::onQueryChange,
                    placeholder = { Text(stringResource(state.target.hintRes()), maxLines = 1) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { actions.onQueryChange("") }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                            }
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        state.results.firstOrNull()?.let { actions.onPlaceChosen(it.place) }
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag(SEARCH_FIELD_TAG),
                )
            }
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            else Spacer(Modifier.padding(top = 4.dp))

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.query.isBlank()) {
                    shortcuts(state.target, saved, recent, actions)
                } else {
                    items(state.results, key = { "${it.place.name}|${it.place.point}" }) { item ->
                        PlaceRow(
                            icon = item.place.category.iconRes(),
                            title = item.place.displayName(),
                            subtitle = listOfNotNull(
                                stringResource(item.place.category.labelRes()),
                                item.place.context,
                                item.distanceM?.let(::formatLength),
                            ).joinToString(" · "),
                            onClick = { actions.onPlaceChosen(item.place) },
                        )
                    }
                    if (!state.searching && state.results.isEmpty()) {
                        item {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (state.failed) stringResource(R.string.search_failed)
                                    else stringResource(R.string.search_no_results, state.query),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    stringResource(R.string.search_no_results_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shortcuts(
    target: SearchTarget,
    saved: List<SavedPlace>,
    recent: List<Place>,
    actions: SearchActions,
) {
    if (target == SearchTarget.ORIGIN || target == SearchTarget.DESTINATION) {
        item {
            PlaceRow(R.drawable.ic_my_location, stringResource(R.string.search_my_location), null, actions::onMyLocation)
        }
    }
    item {
        PlaceRow(R.drawable.ic_map, stringResource(R.string.search_pick_on_map), null, actions::onPickOnMap)
    }
    if (target == SearchTarget.HOME || target == SearchTarget.WORK) return
    item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

    listOf(SavedKind.HOME, SavedKind.WORK).forEach { kind ->
        val place = saved.firstOrNull { it.kind == kind }
        item(key = "kind-$kind") {
            val label = stringResource(if (kind == SavedKind.HOME) R.string.search_home else R.string.search_work)
            PlaceRow(
                icon = if (kind == SavedKind.HOME) R.drawable.ic_home else R.drawable.ic_work,
                title = label,
                subtitle = place?.place?.let { it.displayName() + (it.context?.let { c -> " · $c" } ?: "") }
                    ?: stringResource(R.string.search_tap_to_set),
                onClick = { if (place != null) actions.onSavedChosen(place) else actions.onSetSaved(kind) },
                onRemove = place?.let { { actions.onRemoveSaved(it) } },
                removeLabel = stringResource(R.string.search_remove_saved, label),
            )
        }
    }
    val favourites = saved.filter { it.kind == SavedKind.FAVOURITE }
    if (favourites.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.search_saved)) }
        items(favourites, key = { "fav-${it.id}" }) { fav ->
            val name = fav.place.displayName()
            PlaceRow(
                icon = R.drawable.ic_star,
                title = name,
                subtitle = fav.place.context,
                onClick = { actions.onSavedChosen(fav) },
                onRemove = { actions.onRemoveSaved(fav) },
                removeLabel = stringResource(R.string.search_remove_saved, name),
            )
        }
    }
    if (recent.isNotEmpty()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.search_recent), Modifier.weight(1f))
                TextButton(onClick = actions::onClearRecent, modifier = Modifier.padding(end = 8.dp)) {
                    Text(stringResource(R.string.search_clear_recent))
                }
            }
        }
        items(recent, key = { "recent-${it.name}|${it.point}" }) { place ->
            PlaceRow(R.drawable.ic_history, place.displayName(), place.context, onClick = { actions.onPlaceChosen(place) })
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaceRow(
    icon: Int,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = removeLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun SearchTarget.hintRes(): Int = when (this) {
    SearchTarget.PLACE -> R.string.search_hint_place
    SearchTarget.ORIGIN -> R.string.search_hint_origin
    SearchTarget.DESTINATION -> R.string.search_hint_destination
    SearchTarget.HOME -> R.string.search_hint_home
    SearchTarget.WORK -> R.string.search_hint_work
}

const val SEARCH_FIELD_TAG = "search-field"
