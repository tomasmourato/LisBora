package pt.lisbora.feature.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.routing.eta.BikeType
import pt.lisbora.routing.eta.RiderLevel
import pt.lisbora.routing.weighting.TripPurpose
import kotlin.math.abs
import kotlin.math.roundToInt

/** Route preferences, saved as soon as they change; open routes are recomputed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesSheet(
    settings: RidingSettings,
    onChange: ((RidingSettings) -> RidingSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PreferencesContent(settings, onChange, onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferencesContent(
    settings: RidingSettings,
    onChange: ((RidingSettings) -> RidingSettings) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.prefs_title), style = MaterialTheme.typography.titleLarge)

        ChoiceGroup(
            title = stringResource(R.string.prefs_bike),
            options = BikeType.entries,
            selected = settings.bike,
            label = {
                stringResource(
                    when (it) {
                        BikeType.CITY -> R.string.bike_city
                        BikeType.ROAD -> R.string.bike_road
                        BikeType.MOUNTAIN -> R.string.bike_mountain
                        BikeType.ELECTRIC -> R.string.bike_electric
                    },
                )
            },
            onSelect = { bike -> onChange { s -> s.copy(bike = bike) } },
        )
        ChoiceGroup(
            title = stringResource(R.string.prefs_level),
            options = RiderLevel.entries,
            selected = settings.level,
            label = {
                stringResource(
                    when (it) {
                        RiderLevel.RELAXED -> R.string.level_relaxed
                        RiderLevel.MEDIUM -> R.string.level_medium
                        RiderLevel.SPORTY -> R.string.level_sporty
                    },
                )
            },
            onSelect = { level -> onChange { s -> s.copy(level = level) } },
        )
        ChoiceGroup(
            title = stringResource(R.string.prefs_purpose),
            options = TripPurpose.entries,
            selected = settings.purpose,
            label = {
                stringResource(
                    when (it) {
                        TripPurpose.COMMUTE -> R.string.purpose_commute
                        TripPurpose.LEISURE -> R.string.purpose_leisure
                        TripPurpose.SPORT -> R.string.purpose_sport
                    },
                )
            },
            onSelect = { purpose -> onChange { s -> s.copy(purpose = purpose) } },
        )
        ChoiceGroup(
            title = stringResource(R.string.prefs_max_grade),
            options = RidingSettings.MAX_GRADE_OPTIONS,
            selected = settings.maxGradePercent,
            label = { grade -> if (grade == null) stringResource(R.string.grade_no_limit) else stringResource(R.string.grade_percent, grade) },
            onSelect = { grade -> onChange { s -> s.copy(maxGradePercent = grade) } },
        )

        Column {
            SwitchRow(stringResource(R.string.prefs_avoid_cobbles), settings.avoidCobbles) { v -> onChange { it.copy(avoidCobbles = v) } }
            SwitchRow(stringResource(R.string.prefs_avoid_trams), settings.avoidTramTracks) { v -> onChange { it.copy(avoidTramTracks = v) } }
            SwitchRow(stringResource(R.string.prefs_prefer_segregated), settings.preferSegregated) { v -> onChange { it.copy(preferSegregated = v) } }
            SwitchRow(stringResource(R.string.prefs_allow_ferries), settings.allowFerries) { v -> onChange { it.copy(allowFerries = v) } }
        }

        // The pace learnt from finished rides, which the ETAs use; it can be forgotten.
        if (abs(settings.personalFactor - 1.0) >= 0.005) {
            val percent = abs(((settings.personalFactor - 1) * 100).roundToInt())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.prefs_pace,
                        stringResource(if (settings.personalFactor > 1) R.string.prefs_pace_faster else R.string.prefs_pace_slower, percent),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onChange { it.copy(personalFactor = 1.0) } }) { Text(stringResource(R.string.prefs_pace_reset)) }
            }
        }

        Button(onClick = onDone, modifier = Modifier.align(Alignment.End).padding(bottom = 8.dp)) {
            Text(stringResource(R.string.prefs_done))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
