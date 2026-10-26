package pt.lisbora.feature.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.map.hexColor
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteHazard
import kotlin.math.max
import kotlin.math.min

/** The route's elevation against distance, downsampled for drawing. */
class ProfileSamples(val distances: DoubleArray, val elevations: DoubleArray, val grades: DoubleArray) {
    val total: Double get() = distances.lastOrNull() ?: 0.0
    val minElevation: Double get() = elevations.minOrNull() ?: 0.0
    val maxElevation: Double get() = elevations.maxOrNull() ?: 0.0

    /** Index of the sample closest to [distance]. */
    fun indexAt(distance: Double): Int {
        val i = java.util.Arrays.binarySearch(distances, distance).let { if (it >= 0) it else -it - 1 }
        if (i <= 0) return 0
        if (i >= distances.size) return distances.lastIndex
        return if (distance - distances[i - 1] < distances[i] - distance) i - 1 else i
    }

    companion object {
        fun of(route: Route, maxPoints: Int = 400): ProfileSamples {
            val grades = RouteAnalysis.grades(route)
            val n = route.distances.size
            val step = max(1, (n + maxPoints - 1) / maxPoints)
            val indexes = (0 until n step step).toMutableList()
            if (n > 0 && indexes.last() != n - 1) indexes += n - 1
            return ProfileSamples(
                distances = DoubleArray(indexes.size) { route.distances[indexes[it]] },
                elevations = DoubleArray(indexes.size) { route.elevations[indexes[it]] },
                grades = DoubleArray(indexes.size) { grades[indexes[it]] },
            )
        }
    }
}

/**
 * Elevation profile: the line is coloured by grade like the map, hazards are bands along the bottom,
 * and touching or dragging shows the distance, elevation and grade at that point.
 */
@Composable
fun ElevationProfile(route: Route, hazards: List<RouteHazard>, palette: RoutePalette, modifier: Modifier = Modifier) {
    val samples = remember(route) { ProfileSamples.of(route) }
    var inspected by remember(route) { mutableStateOf<Int?>(null) }
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    val gradeColors = palette.grade.map(::hexColor)
    val description = stringResource(
        R.string.profile_description,
        formatMetres(samples.elevations.firstOrNull() ?: 0.0),
        formatMetres(samples.elevations.lastOrNull() ?: 0.0),
        formatMetres(samples.minElevation),
        formatMetres(samples.maxElevation),
    )

    Column(modifier) {
        val i = inspected
        Text(
            text = if (i != null) {
                stringResource(
                    R.string.profile_inspect,
                    formatLength(samples.distances[i]),
                    formatMetres(samples.elevations[i]),
                    formatGrade(samples.grades[i] * 100),
                )
            } else " ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The blank line only keeps the room for the reading: nothing for a screen reader.
            modifier = if (i == null) Modifier.clearAndSetSemantics {} else Modifier,
        )
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(samples) {
                        detectTapGestures { offset -> inspected = samples.indexAt(offset.x / size.width * samples.total) }
                    }
                    .pointerInput(samples) {
                        detectHorizontalDragGestures(
                            onDragEnd = { inspected = null },
                            onDragCancel = { inspected = null },
                        ) { change, _ ->
                            inspected = samples.indexAt((change.position.x / size.width).coerceIn(0f, 1f) * samples.total)
                        }
                    },
            ) {
                if (samples.distances.size < 2 || samples.total <= 0) return@Canvas
                val band = 8.dp.toPx()
                val top = 4.dp.toPx()
                val chartHeight = size.height - band - top
                // At least 20 m of vertical range, so a flat ride does not look like a mountain stage.
                val range = max(20.0, samples.maxElevation - samples.minElevation)
                val floor = samples.minElevation - (range - (samples.maxElevation - samples.minElevation)) / 2
                fun x(d: Double) = (d / samples.total * size.width).toFloat()
                fun y(e: Double) = (top + chartHeight - (e - floor) / range * chartHeight).toFloat()

                val area = Path().apply {
                    moveTo(0f, top + chartHeight)
                    for (k in samples.distances.indices) lineTo(x(samples.distances[k]), y(samples.elevations[k]))
                    lineTo(size.width, top + chartHeight)
                    close()
                }
                drawPath(area, fill)
                for (k in 0 until samples.distances.lastIndex) {
                    drawLine(
                        color = gradeColors[RoutePalette.gradeBucket(samples.grades[k])],
                        start = Offset(x(samples.distances[k]), y(samples.elevations[k])),
                        end = Offset(x(samples.distances[k + 1]), y(samples.elevations[k + 1])),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                hazards.forEach { h ->
                    val start = x(h.startM)
                    val width = max(3.dp.toPx(), x(h.endM) - start)
                    drawRect(
                        color = hexColor(palette.hazardColor(h.kind)),
                        topLeft = Offset(min(start, size.width - width), size.height - band + 2.dp.toPx()),
                        size = Size(width, band - 2.dp.toPx()),
                    )
                }
                inspected?.let { k ->
                    val px = x(samples.distances[k])
                    drawLine(guide, Offset(px, top), Offset(px, top + chartHeight), strokeWidth = 1.dp.toPx())
                    drawCircle(guide, radius = 4.dp.toPx(), center = Offset(px, y(samples.elevations[k])), style = Stroke(2.dp.toPx()))
                }
            }
            Text(
                formatMetres(samples.maxElevation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp),
            )
            Text(
                formatMetres(samples.minElevation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 2.dp, bottom = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatLength(samples.total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
