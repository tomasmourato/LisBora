package pt.lisbora.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.datapack.CycleNetworkMeta
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.formatDate
import pt.lisbora.feature.map.formatKm
import pt.lisbora.feature.map.labelRes
import pt.lisbora.routing.model.CycleInfraClass

/** Legend card: one row per infrastructure class with its map colour and length, plus a visibility switch. */
@Composable
fun CycleNetworkLegend(
    meta: CycleNetworkMeta?,
    palette: CycleNetworkPalette,
    cycleNetworkVisible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(300.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val showLabel = stringResource(R.string.map_legend_show)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.map_legend_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = cycleNetworkVisible,
                    onCheckedChange = onVisibleChange,
                    modifier = Modifier.semantics { contentDescription = showLabel },
                )
            }
            CycleInfraClass.entries.forEach { infraClass ->
                LegendRow(
                    label = stringResource(infraClass.labelRes()),
                    color = palette.composeColor(infraClass),
                    dashed = palette.style(infraClass).dashed,
                    length = meta?.lengthKmByClass?.get(infraClass)?.let(::formatKm),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = buildString {
                    append(stringResource(R.string.map_legend_source))
                    meta?.sourceLastEdit?.let { append(" · ").append(stringResource(R.string.map_legend_updated, formatDate(it))) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendRow(label: String, color: Color, dashed: Boolean, length: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LineSwatch(color = color, dashed = dashed)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (length != null) {
            Text(length, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A short line drawn like the map layer (solid or dashed), so the legend reads the same as the map. */
@Composable
fun LineSwatch(color: Color, dashed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 28.dp, height = 12.dp)) {
        val y = size.height / 2
        drawLine(
            color = color,
            start = Offset(2.dp.toPx(), y),
            end = Offset(size.width - 2.dp.toPx(), y),
            strokeWidth = 4.dp.toPx(),
            cap = if (dashed) StrokeCap.Butt else StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())) else null,
        )
    }
}
