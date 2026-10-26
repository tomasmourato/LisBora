package pt.lisbora.feature.discover

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import pt.lisbora.R
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.planner.formatMetres
import pt.lisbora.feature.planner.formatPercent
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryTweak

@StringRes
fun DiscoveryPurpose.labelRes(): Int = when (this) {
    DiscoveryPurpose.LEISURE -> R.string.discover_purpose_leisure
    DiscoveryPurpose.SCENERY -> R.string.discover_purpose_scenery
    DiscoveryPurpose.RIVER -> R.string.discover_purpose_river
    DiscoveryPurpose.SPORT -> R.string.discover_purpose_sport
    DiscoveryPurpose.CLIMBS -> R.string.discover_purpose_climbs
    DiscoveryPurpose.DESCENTS -> R.string.discover_purpose_descents
    DiscoveryPurpose.FAMILY -> R.string.discover_purpose_family
    DiscoveryPurpose.CULTURE -> R.string.discover_purpose_culture
}

@StringRes
fun DiscoveryTweak.labelRes(): Int = when (this) {
    DiscoveryTweak.MORE_RIVER -> R.string.discover_tweak_more_river
    DiscoveryTweak.FEWER_CLIMBS -> R.string.discover_tweak_fewer_climbs
}

/** "estação GIRA 101", or its name when it has no number. */
@Composable
fun GiraStation.label(): String = label(LocalResources.current)

fun GiraStation.label(res: Resources): String = number?.let { res.getString(R.string.discover_gira_station, it) } ?: name

/** Time available: "45 min", "1 h", "1 h 30". */
@Composable
fun minutesLabel(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.discover_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.discover_hours, minutes / 60)
    else -> stringResource(R.string.discover_hours_minutes, minutes / 60, minutes % 60)
}

/** "3,2 km junto ao rio", "2 miradouros", "Quase plana"… */
@Composable
fun RouteReason.text(): String = when (this) {
    is RouteReason.Riverside -> stringResource(R.string.reason_riverside, formatLength(metres))
    is RouteReason.Green -> stringResource(R.string.reason_green, formatLength(metres))
    is RouteReason.Viewpoints -> pluralStringResource(R.plurals.reason_viewpoints, count, count)
    is RouteReason.Culture -> pluralStringResource(R.plurals.reason_culture, count, count)
    is RouteReason.Parks -> pluralStringResource(R.plurals.reason_parks, count, count)
    is RouteReason.Playgrounds -> pluralStringResource(R.plurals.reason_playgrounds, count, count)
    is RouteReason.Segregated -> stringResource(R.string.reason_segregated, formatPercent(share))
    is RouteReason.Infra -> stringResource(R.string.reason_infra, formatPercent(share))
    is RouteReason.Calm -> stringResource(R.string.reason_calm, formatPercent(share))
    is RouteReason.Climb -> stringResource(R.string.reason_climb, formatMetres(metres))
    is RouteReason.Descent -> stringResource(R.string.reason_descent, formatMetres(metres))
    is RouteReason.EndsLower -> stringResource(R.string.reason_ends_lower, formatMetres(metres))
    RouteReason.Flat -> stringResource(R.string.reason_flat)
    RouteReason.GentleClimbs -> stringResource(R.string.reason_gentle)
}
