package pt.lisbora.feature.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.discover.GiraRow

/** "Pedalar": a ride without a route, on the rider's own bike or a GIRA one. */
data class FreeRideSetup(val gira: Boolean = false)

interface FreeRideActions {
    fun onFreeRideOpen()
    fun onFreeRideClose()
    fun onFreeRideGira(on: Boolean)

    /** "Começar": asks for the location permission when needed, then starts the ride. */
    fun onFreeRideStart()
}

/**
 * "Pedalar": a ride with no destination or instructions, recorded for its summary at the end (distance,
 * time, speed, climb). On a GIRA bike the 45 minutes are counted from the start and, before they run
 * out, the nearest station to swap bikes at is named.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeRideSheet(setup: FreeRideSetup, giraStations: List<GiraStation>, actions: FreeRideActions) {
    ModalBottomSheet(onDismissRequest = actions::onFreeRideClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        FreeRideContent(setup, giraStations, actions)
    }
}

@Composable
fun FreeRideContent(setup: FreeRideSetup, giraStations: List<GiraStation>, actions: FreeRideActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.free_ride_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.free_ride_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GiraRow(setup.gira, giraStations, stringResource(R.string.free_ride_gira_hint), onChange = actions::onFreeRideGira)
        Button(onClick = actions::onFreeRideStart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_bike), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.free_ride_start))
        }
    }
}
