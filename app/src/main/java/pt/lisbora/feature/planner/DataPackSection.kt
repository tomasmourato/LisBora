package pt.lisbora.feature.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.datapack.DataPackInfo
import pt.lisbora.data.datapack.DataUpdateStatus
import pt.lisbora.feature.map.formatDate
import java.util.Locale

/**
 * "Dados do mapa": where the data in use comes from and how recent it is, the state of the updates and a
 * button to look for newer data now; once newer data is downloaded, a button to restart the app with it.
 */
@Composable
fun DataPackSection(
    info: DataPackInfo?,
    status: DataUpdateStatus,
    onCheck: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.prefs_data_title), style = MaterialTheme.typography.titleSmall)
        val sources = buildList {
            info?.updatedAt?.let { add(stringResource(R.string.prefs_data_updated, formatDate(it))) }
            info?.cycleNetworkEditedAt?.let { add(stringResource(R.string.prefs_data_cycle_network, formatDate(it))) }
            info?.osmDataAt?.let { add(stringResource(R.string.prefs_data_osm, formatDate(it))) }
            val giraStations = info?.giraStations
            val giraFetchedAt = info?.giraFetchedAt
            if (giraStations != null && giraFetchedAt != null) add(stringResource(R.string.prefs_data_gira, giraStations, formatDate(giraFetchedAt)))
        }
        if (sources.isNotEmpty()) {
            Text(sources.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            statusText(info, status),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row {
            when (status) {
                is DataUpdateStatus.Ready -> FilledTonalButton(onClick = onRestart) { Text(stringResource(R.string.prefs_data_restart)) }
                DataUpdateStatus.Checking, is DataUpdateStatus.Downloading -> TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.prefs_data_check)) }
                else -> TextButton(onClick = onCheck) { Text(stringResource(R.string.prefs_data_check)) }
            }
        }
    }
}

@Composable
private fun statusText(info: DataPackInfo?, status: DataUpdateStatus): String = when (status) {
    DataUpdateStatus.Idle -> info?.checkedAt?.let { stringResource(R.string.prefs_data_checked, formatDate(it)) }
        ?: stringResource(R.string.prefs_data_automatic)
    DataUpdateStatus.Checking -> stringResource(R.string.prefs_data_checking)
    is DataUpdateStatus.Downloading -> stringResource(R.string.prefs_data_downloading, megabytes(status.doneBytes), megabytes(status.totalBytes))
    DataUpdateStatus.UpToDate -> stringResource(R.string.prefs_data_up_to_date)
    is DataUpdateStatus.Ready -> DataPackInfo.parseVersion(status.dataVersion)?.let { stringResource(R.string.prefs_data_ready, formatDate(it)) }
        ?: stringResource(R.string.prefs_data_ready_undated)
    DataUpdateStatus.NeedsAppUpdate -> stringResource(R.string.prefs_data_needs_app)
    is DataUpdateStatus.Failed -> stringResource(
        when (status.reason) {
            DataUpdateStatus.Failed.Reason.NETWORK -> R.string.prefs_data_failed_network
            DataUpdateStatus.Failed.Reason.NO_SPACE -> R.string.prefs_data_failed_space
            DataUpdateStatus.Failed.Reason.CORRUPT -> R.string.prefs_data_failed_corrupt
        },
    )
}

/** "12,4 MB" */
private fun megabytes(bytes: Long): String = String.format(Locale.forLanguageTag("pt-PT"), "%.1f MB", bytes / 1_048_576.0)
