package pt.lisbora.feature.navigation

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import pt.lisbora.R
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.planner.formatGrade
import pt.lisbora.routing.HazardKind
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.navigation.CyclewayChange
import pt.lisbora.routing.navigation.Instruction
import pt.lisbora.routing.navigation.Maneuver
import pt.lisbora.routing.navigation.WalkChange

/**
 * The instruction in words: "Vire à esquerda", "Vire à direita e entre na ciclovia", "Desmonte e siga a pé".
 * The street goes on a line of its own (no article to guess: "a Rua", "o Largo").
 */
@Composable
fun Instruction.text(): String = text(LocalResources.current)

/** [text] outside Compose: the notification and the voice. */
fun Instruction.text(res: Resources): String {
    val base = when (maneuver) {
        Maneuver.DEPART -> R.string.nav_depart
        Maneuver.CONTINUE -> R.string.nav_continue
        Maneuver.SLIGHT_LEFT -> R.string.nav_slight_left
        Maneuver.LEFT -> R.string.nav_left
        Maneuver.SHARP_LEFT -> R.string.nav_sharp_left
        Maneuver.SLIGHT_RIGHT -> R.string.nav_slight_right
        Maneuver.RIGHT -> R.string.nav_right
        Maneuver.SHARP_RIGHT -> R.string.nav_sharp_right
        Maneuver.U_TURN -> R.string.nav_u_turn
        Maneuver.ROUNDABOUT -> R.string.nav_roundabout
        Maneuver.FERRY -> R.string.nav_ferry
        Maneuver.ARRIVE -> R.string.nav_arrive
    }
    // Going straight on, a change of track or of mode is the instruction itself.
    val plain = maneuver == Maneuver.CONTINUE || maneuver == Maneuver.DEPART
    val change = when {
        walk == WalkChange.START -> R.string.nav_walk_start to R.string.nav_and_walk_start
        walk == WalkChange.END -> R.string.nav_walk_end to R.string.nav_and_walk_end
        cycleway == CyclewayChange.ENTER -> R.string.nav_enter_cycleway to R.string.nav_and_enter_cycleway
        cycleway == CyclewayChange.LEAVE -> R.string.nav_leave_cycleway to R.string.nav_and_leave_cycleway
        else -> null
    }
    return when {
        change == null -> res.getString(base)
        plain -> res.getString(change.first)
        else -> res.getString(change.second, res.getString(base))
    }
}

/** The arrow's rotation for a turn, in degrees clockwise; null for maneuvers drawn with their own icon. */
fun Maneuver.arrowDegrees(): Float? = when (this) {
    Maneuver.DEPART, Maneuver.CONTINUE, Maneuver.ROUNDABOUT -> 0f
    Maneuver.SLIGHT_LEFT -> -45f
    Maneuver.LEFT -> -90f
    Maneuver.SHARP_LEFT -> -135f
    Maneuver.SLIGHT_RIGHT -> 45f
    Maneuver.RIGHT -> 90f
    Maneuver.SHARP_RIGHT -> 135f
    Maneuver.U_TURN -> 180f
    Maneuver.FERRY, Maneuver.ARRIVE -> null
}

@DrawableRes
fun Maneuver.iconRes(): Int = when (this) {
    Maneuver.FERRY -> R.drawable.ic_boat
    Maneuver.ARRIVE -> R.drawable.ic_place
    else -> R.drawable.ic_arrow_upward
}

/** "Subida de 9% daqui a 150 m", "Calçada daqui a 80 m"… */
@Composable
fun RouteHazard.aheadText(distanceM: Double): String = aheadText(LocalResources.current, formatLength(distanceM))

/** [aheadText] with the distance already in words ("150 metros" for the voice). */
fun RouteHazard.aheadText(res: Resources, distance: String): String = when (kind) {
    HazardKind.STEEP_CLIMB -> res.getString(R.string.nav_ahead_climb, formatGrade(gradePercent ?: 0.0), distance)
    HazardKind.STEEP_DESCENT -> res.getString(
        if (walkAdvised) R.string.nav_ahead_descent_walk else R.string.nav_ahead_descent,
        formatGrade(gradePercent ?: 0.0),
        distance,
    )
    HazardKind.COBBLES -> res.getString(R.string.nav_ahead_cobbles, distance)
    HazardKind.TRAM_TRACKS -> res.getString(R.string.nav_ahead_tram, distance)
    HazardKind.WALK -> res.getString(R.string.nav_ahead_walk, distance)
    HazardKind.BUSY_ROAD -> res.getString(R.string.nav_ahead_busy, distance)
    HazardKind.FERRY -> res.getString(R.string.nav_ahead_ferry, distance)
}
