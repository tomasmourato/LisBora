package pt.lisbora.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.CycleSegment
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.map.labelRes

/** Details of the cycle-network segment the user tapped on the map. */
@Composable
fun SegmentCard(
    segment: CycleSegment,
    palette: CycleNetworkPalette,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val classLabel = stringResource(segment.infraClass.labelRes())
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineSwatch(
                    color = palette.composeColor(segment.infraClass),
                    dashed = palette.style(segment.infraClass).dashed,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = classLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.map_close))
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = segment.name ?: segment.typology ?: classLabel,
                    style = MaterialTheme.typography.titleLarge,
                )
                listOfNotNull(segment.typology, segment.segregation).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
                val facts = listOfNotNull(
                    segment.parish?.let { stringResource(R.string.segment_parish, it) },
                    segment.year?.let { stringResource(R.string.segment_year, it) },
                    segment.lengthM?.let(::formatLength),
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (segment.contraflow) {
                    Text(
                        stringResource(R.string.segment_contraflow),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
