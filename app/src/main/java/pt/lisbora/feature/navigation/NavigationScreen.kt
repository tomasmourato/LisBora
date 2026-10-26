package pt.lisbora.feature.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.lisbora.R
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.discover.label
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.planner.formatDuration
import pt.lisbora.feature.planner.formatMetres
import pt.lisbora.routing.navigation.Instruction
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

interface NavigationActions {
    fun onNavigationStop()
    fun onRecenter()
    fun onToggleVoice()
    fun onGiraGoToStation()
    fun onGiraSwapped()
    fun onExportGpx()
    fun onSummaryDismiss()
}

/** No-op base for previews and UI tests. */
open class NavigationActionsAdapter : NavigationActions {
    override fun onNavigationStop() = Unit
    override fun onRecenter() = Unit
    override fun onToggleVoice() = Unit
    override fun onGiraGoToStation() = Unit
    override fun onGiraSwapped() = Unit
    override fun onExportGpx() = Unit
    override fun onSummaryDismiss() = Unit
}

/** Keeps the screen on while composed (riding with the phone on the handlebar). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Top of the map while riding: the next instruction in large type (or what the app is waiting for), the
 * one after it when close, what comes soon (a climb, cobbles…) and, on a GIRA ride, the 45-minute warning.
 */
@Composable
fun NavigationTop(state: NavigationUiState, actions: NavigationActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val g = state.gira
        when {
            g != null && !g.started -> {
                // Measured from the last fix once there is one; the trip starts within PICK_UP_RADIUS_M.
                val toStationM = g.toPickUpM ?: g.trip.walkToPickUpM
                StatusCard(
                    if (toStationM <= NavigationSession.PICK_UP_RADIUS_M) {
                        stringResource(R.string.nav_gira_pick_up, g.trip.pickUp.label())
                    } else {
                        stringResource(R.string.nav_gira_walk_to, g.trip.pickUp.label(), formatLength(toStationM))
                    },
                    progress = state.waitingForGps,
                )
            }
            state.waitingForGps -> StatusCard(stringResource(R.string.nav_waiting_gps), progress = true)
            state.rerouting -> StatusCard(stringResource(R.string.nav_rerouting), progress = true)
            else -> state.next?.let { ManeuverCard(it, state.distanceToNextM ?: 0.0, state.then) }
        }
        if (state.rerouteFailed) StatusCard(stringResource(R.string.nav_reroute_failed))
        if (state.upcoming != null && state.distanceToUpcomingM != null && !state.rerouting) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, shadowElevation = 2.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.upcoming.aheadText(state.distanceToUpcomingM), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        g?.let { GiraCard(it, actions) }
    }
}

@Composable
private fun ManeuverCard(next: Instruction, distanceM: Double, then: Instruction?) {
    val text = next.text()
    val distance = formatLength(roundedDistance(distanceM))
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = listOfNotNull(distance, text, next.street).joinToString(", ")
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val degrees = next.maneuver.arrowDegrees()
                // Read at a glance on the handlebar: the arrow and the distance as large as the card allows.
                Icon(
                    painterResource(next.maneuver.iconRes()),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).let { if (degrees != null) it.rotate(degrees) else it },
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(distance, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    next.street?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            then?.let {
                Text(
                    stringResource(R.string.nav_then, it.text().replaceFirstChar { c -> c.lowercase() }),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(text: String, progress: Boolean = false) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            if (progress) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** The 45-minute warning with where to swap bikes, the detour to the station, or being there. */
@Composable
private fun GiraCard(g: GiraNavigation, actions: NavigationActions) {
    val title: String
    val body: String?
    when {
        g.atSwapStation && g.swapStation != null -> {
            title = stringResource(R.string.nav_gira_at_station, g.swapStation.label())
            body = null
        }
        g.swapStation != null -> {
            title = stringResource(R.string.nav_gira_going_to, g.swapStation.label())
            body = minutesLeftText(g)
        }
        g.warning != null -> {
            title = minutesLeftText(g)
            body = g.suggestion?.let { stringResource(R.string.nav_gira_swap_at, it.station.label(), formatLength(it.distanceM)) + it.station.counts() }
                ?: stringResource(R.string.nav_gira_no_station)
        }
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_bike), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.swapStation == null && g.suggestion != null) {
                    Button(onClick = actions::onGiraGoToStation) { Text(stringResource(R.string.nav_gira_go)) }
                }
                OutlinedButton(onClick = actions::onGiraSwapped) { Text(stringResource(R.string.nav_gira_swapped)) }
            }
        }
    }
}

@Composable
private fun minutesLeftText(g: GiraNavigation): String {
    val left = ceil(g.leftS / 60).toInt()
    return if (left <= 0) stringResource(R.string.nav_gira_over) else pluralStringResource(R.plurals.nav_gira_left, left, left)
}

/** " (6 docas livres, 3 bicicletas)" when the counts are live. */
@Composable
private fun GiraStation.counts(): String {
    val docks = freeDocks ?: return ""
    val bikes = bikes ?: return ""
    return " (" + pluralStringResource(R.plurals.discover_gira_docks, docks, docks) + ", " + pluralStringResource(R.plurals.discover_gira_bikes, bikes, bikes) + ")"
}

/**
 * Bottom of the map while riding (phone upright): the voice switch and "Recentrar" over a sheet with the
 * time and distance left, the arrival time, the GIRA trip's time and "Terminar".
 */
@Composable
fun NavigationBottom(state: NavigationUiState, actions: NavigationActions, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        NavigationButtons(state, actions, Modifier.padding(end = 16.dp, bottom = 12.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TripInfo(state, actions, Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

/**
 * The phone sideways on the handlebar (a GIRA bike's holder takes it that way): everything to read in a
 * column on the left — the next instruction on top, the time left and "Terminar" at the bottom — so the
 * map keeps the whole height on the right to show the road ahead. The summary at the end goes there too.
 */
@Composable
fun NavigationSidePanel(state: NavigationUiState, actions: NavigationActions, modifier: Modifier = Modifier) {
    val summary = state.summary
    // The instruction takes what it needs (scrolling on a very short screen); the trip card sits at the bottom.
    Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
        if (state.active) {
            NavigationTop(state, actions, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                TripInfo(state, actions, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
            }
        } else if (summary != null) {
            RideSummaryPanel(summary, actions, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), sheet = false)
        }
    }
}

/** The voice switch and "Recentrar", over the map. */
@Composable
fun NavigationButtons(state: NavigationUiState, actions: NavigationActions, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        val voice = stringResource(if (state.voiceOn) R.string.nav_voice_off else R.string.nav_voice_on)
        SmallFloatingActionButton(
            onClick = actions::onToggleVoice,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = voice },
        ) {
            val speaking = state.voiceOn && !state.voiceUnavailable
            Icon(painterResource(if (speaking) R.drawable.ic_volume_up else R.drawable.ic_volume_off), contentDescription = null)
        }
        val recenter = stringResource(R.string.nav_recenter)
        SmallFloatingActionButton(
            onClick = actions::onRecenter,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = recenter },
        ) {
            Icon(painterResource(R.drawable.ic_my_location), contentDescription = null)
        }
    }
}

/** Time and distance left with the arrival time, the GIRA trip's time, and "Terminar". */
@Composable
private fun TripInfo(state: NavigationUiState, actions: NavigationActions, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Column(Modifier.weight(1f)) {
            val progress = state.progress
            val remainingS = progress?.remainingS ?: state.route?.durationS ?: 0.0
            val remainingM = progress?.remainingM ?: state.route?.distanceM ?: 0.0
            Text(formatDuration(remainingS), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.nav_remaining, formatLength(remainingM), LocalTime.now().plusSeconds(remainingS.toLong()).format(CLOCK)),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.gira?.let { g ->
                Text(
                    if (g.started) {
                        stringResource(R.string.nav_gira_timer, (g.elapsedS / 60).toInt())
                    } else {
                        stringResource(R.string.nav_gira_timer_pending)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (g.warning != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Button(
            onClick = actions::onNavigationStop,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) {
            Text(stringResource(R.string.nav_stop))
        }
    }
}

/**
 * At the end: how the ride went, what the app learnt about the rider's pace, and the GPX. A sheet along
 * the bottom edge ([sheet]), or a card of its own in the side panel.
 */
@Composable
fun RideSummaryPanel(summary: RideSummary, actions: NavigationActions, modifier: Modifier = Modifier, sheet: Boolean = true) {
    Surface(
        shape = if (sheet) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (sheet) 8.dp else 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.then(if (sheet) Modifier.navigationBarsPadding() else Modifier).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(if (summary.arrived) R.string.nav_summary_arrived else R.string.nav_summary_title),
                style = MaterialTheme.typography.titleLarge,
            )
            SummaryLine(stringResource(R.string.nav_summary_distance), formatLength(summary.distanceM))
            SummaryLine(stringResource(R.string.nav_summary_moving), formatDuration(summary.movingS))
            SummaryLine(stringResource(R.string.nav_summary_speed), stringResource(R.string.nav_summary_kmh, summary.averageKmh.roundToInt()))
            SummaryLine(stringResource(R.string.nav_summary_climb), formatMetres(summary.climbM))
            summary.newPersonalFactor?.let { factor ->
                val percent = abs(((factor - 1) * 100).roundToInt())
                Text(
                    stringResource(if (factor >= 1) R.string.nav_summary_pace_faster else R.string.nav_summary_pace_slower, percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                OutlinedButton(onClick = actions::onExportGpx) { Text(stringResource(R.string.nav_summary_gpx)) }
                Button(onClick = actions::onSummaryDismiss) { Text(stringResource(R.string.nav_summary_close)) }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Distances to the next turn as people say them: to 10 m below 200 m, to 50 m below 1 km. */
fun roundedDistance(m: Double): Double = when {
    m < 200 -> (m / 10).roundToInt() * 10.0
    m < 1000 -> (m / 50).roundToInt() * 50.0
    else -> (m / 100).roundToInt() * 100.0
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
