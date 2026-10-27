package pt.lisbora.feature.navigation

import pt.lisbora.data.gira.GiraStation
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.navigation.CyclewayChange
import pt.lisbora.routing.navigation.Instruction
import pt.lisbora.routing.navigation.Maneuver
import pt.lisbora.routing.navigation.WalkChange
import kotlin.math.ceil

/** Something worth saying while riding. */
sealed interface VoicePrompt {
    /** The next instruction, [distanceM] ahead or right now (null); [then] follows it closely. */
    data class Turn(val instruction: Instruction, val distanceM: Double?, val then: Instruction? = null) : VoicePrompt

    /** A climb, cobbles, tram rails… [distanceM] ahead. */
    data class Ahead(val hazard: RouteHazard, val distanceM: Double) : VoicePrompt

    data object OffRoute : VoicePrompt

    /** No new route from here; [free]: riding free, where the only route is the way to a GIRA station. */
    data class RerouteFailed(val free: Boolean = false) : VoicePrompt

    /** A GIRA trip's 45 minutes running out ([minutesLeft] 0 when over), and where to swap bikes. */
    data class GiraTime(val warning: GiraWarning, val minutesLeft: Int, val station: NearbyStation?) : VoicePrompt

    data class GiraAtStation(val station: GiraStation) : VoicePrompt

    data object Arrived : VoicePrompt
}

/**
 * What to say, decided from one navigation state to the next, each thing once: the next instruction
 * about 150 m before it and again just before it (with the one after, when it follows closely), and with
 * its distance when it is still far after the last one; a hazard 150 m ahead; leaving the route; the
 * GIRA warnings; arriving. A new street name alone is shown but not said. Nothing while waiting for the
 * GPS or walking to the GIRA station. Pure, so it is unit-tested; [NavigationService] speaks what it returns.
 */
class VoicePrompts {

    private var route: Route? = null
    private var instructions: List<Instruction>? = null
    private var spoken: List<Instruction> = emptyList()
    private var lastNext: Instruction? = null
    private val far = HashSet<Instruction>()
    private val near = HashSet<Instruction>()
    private val hazards = HashSet<RouteHazard>()
    private var rerouting = false
    private var rerouteFailed = false
    private var giraWarning: GiraWarning? = null
    private var atStation = false
    private var ended = false

    fun next(state: NavigationUiState): List<VoicePrompt> {
        if (!state.active) {
            if (ended) return emptyList()
            ended = true
            return if (state.summary?.arrived == true) listOf(VoicePrompt.Arrived) else emptyList()
        }
        if (ended) reset()
        val out = mutableListOf<VoicePrompt>()
        if (state.rerouting && !rerouting) out += VoicePrompt.OffRoute
        rerouting = state.rerouting
        if (state.rerouteFailed && !rerouteFailed) out += VoicePrompt.RerouteFailed(state.free)
        rerouteFailed = state.rerouteFailed
        if (state.route !== route) {
            route = state.route
            lastNext = null
            far.clear()
            near.clear()
            hazards.clear()
        }

        if (state.instructions !== instructions) {
            instructions = state.instructions
            spoken = state.instructions.filter { it.isSpoken() }
        }

        val g = state.gira
        if (g != null) {
            if (g.warning != null && g.warning != giraWarning) {
                out += VoicePrompt.GiraTime(g.warning, ceil(g.leftS / 60).toInt().coerceAtLeast(0), g.suggestion)
            }
            giraWarning = g.warning
            if (g.atSwapStation && !atStation && g.swapStation != null) out += VoicePrompt.GiraAtStation(g.swapStation)
            atStation = g.atSwapStation
        }

        val progress = state.progress
        val guiding = progress != null && !state.waitingForGps && !state.rerouting && (g == null || (g.started && !g.atSwapStation))
        if (!guiding) return out
        val along = progress.alongM
        val index = spoken.indexOfFirst { it.atM > along + NavigationSession.NEXT_MARGIN_M }
        val next = spoken.getOrNull(index)
        if (next != null) {
            val d = next.atM - along
            when {
                // Arriving is said once, when the ride ends.
                d <= NEAR_M -> if (near.add(next) && next.maneuver != Maneuver.ARRIVE) {
                    far += next
                    val then = spoken.getOrNull(index + 1)?.takeIf { it.atM - next.atM <= NavigationSession.THEN_WITHIN_M }
                    out += VoicePrompt.Turn(next, null, then)
                }
                d <= FAR_M -> if (far.add(next) && d > NEAR_M + MIN_GAP_M) out += VoicePrompt.Turn(next, d)
                next != lastNext && d > PREVIEW_M -> out += VoicePrompt.Turn(next, d)
            }
            lastNext = next
        }
        val hazard = state.upcoming
        val dh = state.distanceToUpcomingM
        if (hazard != null && dh != null && dh <= HAZARD_M && hazards.add(hazard)) out += VoicePrompt.Ahead(hazard, dh)
        return out
    }

    private fun reset() {
        route = null
        instructions = null
        spoken = emptyList()
        lastNext = null
        far.clear()
        near.clear()
        hazards.clear()
        rerouting = false
        rerouteFailed = false
        giraWarning = null
        atStation = false
        ended = false
    }

    /** Turns, roundabouts, ferries, cycle tracks and walking; not a new street name alone. */
    private fun Instruction.isSpoken() = maneuver != Maneuver.DEPART &&
        !(maneuver == Maneuver.CONTINUE && cycleway == CyclewayChange.NONE && walk == WalkChange.NONE)

    companion object {
        /** "Vire à esquerda": some 7 s ahead at 20 km/h, time to hear it and look. */
        const val NEAR_M = 40.0

        /** "Daqui a 150 metros, vire à esquerda." */
        const val FAR_M = 160.0

        /** Not worth a separate "daqui a" so close to "now". */
        const val MIN_GAP_M = 30.0

        /** After a turn, the next one is announced with its distance when it is farther than this. */
        const val PREVIEW_M = 300.0
        const val HAZARD_M = 150.0
    }
}
