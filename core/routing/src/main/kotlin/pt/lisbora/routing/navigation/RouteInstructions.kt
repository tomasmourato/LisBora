package pt.lisbora.routing.navigation

import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.TravelMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/** What the rider does at an [Instruction]. Left and right are relative to the direction of travel. */
enum class Maneuver { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, ROUNDABOUT, FERRY, ARRIVE }

/** Entering or leaving a cycle track (or a shared path), or neither. */
enum class CyclewayChange { NONE, ENTER, LEAVE }

/** Getting off to walk the bike, or back on, or neither. */
enum class WalkChange { NONE, START, END }

/**
 * One step of the turn-by-turn directions: at [atM] metres along the route, do [maneuver] onto [street]
 * (null when unnamed), possibly entering or leaving a cycle track or a stretch on foot. The words are the
 * app's (in pt-PT); this only says what happens. [untilNextM] is how far it is to the next instruction.
 */
data class Instruction(
    val atM: Double,
    val point: GeoPoint,
    val maneuver: Maneuver,
    val street: String?,
    val cycleway: CyclewayChange = CyclewayChange.NONE,
    val walk: WalkChange = WalkChange.NONE,
    val untilNextM: Double = 0.0,
)

/**
 * Turn-by-turn instructions from a [Route]'s legs and geometry. A new instruction comes with a turn, a
 * roundabout or a ferry, a change of street name, or entering or leaving a cycle track or a stretch on foot.
 * Short flickers (the conflated cycle track missing for a few metres, a junction's jog) are smoothed out.
 */
object RouteInstructions {

    fun build(route: Route): List<Instruction> {
        val legs = route.legs
        if (legs.isEmpty() || route.distanceM <= 0) {
            val point = route.points.firstOrNull() ?: return emptyList()
            return listOf(Instruction(0.0, point, Maneuver.ARRIVE, null))
        }
        val onFoot = smooth(legs, MIN_WALK_M) { it.mode == TravelMode.PUSH }
        val onTrack = smooth(legs, MIN_CYCLEWAY_M) { it.infra in CYCLE_TRACKS && it.mode == TravelMode.RIDE }
        val events = mutableListOf<Instruction>()
        events += Instruction(
            atM = 0.0,
            point = route.points.first(),
            maneuver = Maneuver.DEPART,
            street = firstName(legs, 0),
            cycleway = if (onTrack[0]) CyclewayChange.ENTER else CyclewayChange.NONE,
            walk = if (onFoot[0]) WalkChange.START else WalkChange.NONE,
        )
        var lastName = legs[0].name
        for (i in 1 until legs.size) {
            val prev = legs[i - 1]
            val leg = legs[i]
            val at = leg.startM
            val point = RouteAnalysis.pointAt(route, at)
            val cycleway = when {
                onTrack[i] && !onTrack[i - 1] -> CyclewayChange.ENTER
                !onTrack[i] && onTrack[i - 1] -> CyclewayChange.LEAVE
                else -> CyclewayChange.NONE
            }
            val walk = when {
                onFoot[i] && !onFoot[i - 1] -> WalkChange.START
                !onFoot[i] && onFoot[i - 1] -> WalkChange.END
                else -> WalkChange.NONE
            }
            val renamed = leg.name != null && leg.name != lastName && runLength(legs, i) { it.name == leg.name } >= MIN_NAME_RUN_M
            if (leg.name != null) lastName = leg.name
            val event = when {
                leg.flags.roundabout && prev.flags.roundabout -> null
                prev.flags.roundabout -> null // covered at the entry, which names the exit street
                leg.flags.roundabout -> Instruction(at, point, Maneuver.ROUNDABOUT, exitStreet(legs, i), cycleway, walk)
                leg.flags.isFerry && !prev.flags.isFerry -> Instruction(at, point, Maneuver.FERRY, null)
                else -> {
                    val turn = classify(turnAngle(route, at))
                    when {
                        turn != Maneuver.CONTINUE -> Instruction(at, point, turn, leg.name ?: firstName(legs, i), cycleway, walk)
                        cycleway != CyclewayChange.NONE || walk != WalkChange.NONE || renamed ->
                            Instruction(at, point, Maneuver.CONTINUE, leg.name, cycleway, walk)
                        else -> null
                    }
                }
            }
            event?.let { events += it }
        }
        val merged = settleStart(mergeJogs(route, events))
        val arrive = Instruction(route.distanceM, route.points.last(), Maneuver.ARRIVE, null)
        val all = merged + arrive
        return all.mapIndexed { i, it -> if (i < all.lastIndex) it.copy(untilNextM = all[i + 1].atM - it.atM) else it }
    }

    /**
     * Two turns closer than [JOG_M] are one (crossing a dual carriageway, a kerb ramp): the angle is
     * measured from before the first to after the second, and when it comes out straight the turn goes.
     */
    private fun mergeJogs(route: Route, events: List<Instruction>): List<Instruction> {
        val result = mutableListOf<Instruction>()
        for (event in events) {
            val last = result.lastOrNull()
            if (last != null && last.maneuver.isTurn() && event.maneuver.isTurn() && event.atM - last.atM < JOG_M) {
                val angle = RoutingGraph.turnAngle(bearing(route, last.atM - WINDOW_M, last.atM), bearing(route, event.atM, event.atM + WINDOW_M))
                val turn = classify(angle)
                result.removeAt(result.lastIndex)
                val cycleway = if (event.cycleway != CyclewayChange.NONE) event.cycleway else last.cycleway
                val walk = if (event.walk != WalkChange.NONE) event.walk else last.walk
                if (turn != Maneuver.CONTINUE || cycleway != CyclewayChange.NONE || walk != WalkChange.NONE) {
                    result += last.copy(maneuver = turn, street = event.street ?: last.street, cycleway = cycleway, walk = walk)
                }
            } else {
                result += event
            }
        }
        return result
    }

    /**
     * A turn within [SETTLE_M] of the start comes from where the start was snapped (a few metres before a
     * corner): the rider just sets off that way, so it becomes part of the departure.
     */
    private fun settleStart(events: List<Instruction>): List<Instruction> {
        val turn = events.getOrNull(1)?.takeIf { it.maneuver.isTurn() && it.atM < SETTLE_M } ?: return events
        val depart = events[0]
        return listOf(depart.copy(street = turn.street ?: depart.street)) + events.drop(2)
    }

    private fun Maneuver.isTurn() = this in TURNS

    /** Signed turn at [atM]: the bearing over [WINDOW_M] before it against the one over [WINDOW_M] after. */
    private fun turnAngle(route: Route, atM: Double): Double =
        RoutingGraph.turnAngle(bearing(route, atM - WINDOW_M, atM), bearing(route, atM, atM + WINDOW_M))

    private fun bearing(route: Route, fromM: Double, toM: Double): Double {
        val a = RouteAnalysis.pointAt(route, fromM.coerceAtLeast(0.0))
        val b = RouteAnalysis.pointAt(route, toM.coerceAtMost(route.distanceM))
        val dx = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = b.lat - a.lat
        return if (dx == 0.0 && dy == 0.0) 0.0 else RoutingGraph.normalizeBearing(Math.toDegrees(atan2(dx, dy)))
    }

    fun classify(angle: Double): Maneuver {
        val a = abs(angle)
        val left = angle < 0
        return when {
            a < STRAIGHT_DEG -> Maneuver.CONTINUE
            a < SLIGHT_DEG -> if (left) Maneuver.SLIGHT_LEFT else Maneuver.SLIGHT_RIGHT
            a < TURN_DEG -> if (left) Maneuver.LEFT else Maneuver.RIGHT
            a < SHARP_DEG -> if (left) Maneuver.SHARP_LEFT else Maneuver.SHARP_RIGHT
            else -> Maneuver.U_TURN
        }
    }

    /** The name of the first named leg from [index] within a short distance (a junction's unnamed bit). */
    private fun firstName(legs: List<RouteLeg>, index: Int): String? {
        val start = legs[index].startM
        return legs.drop(index).takeWhile { it.startM - start < NAME_LOOKAHEAD_M }.firstNotNullOfOrNull { it.name }
    }

    /** The street a roundabout entered at [index] is left by. */
    private fun exitStreet(legs: List<RouteLeg>, index: Int): String? {
        val exit = (index until legs.size).firstOrNull { !legs[it].flags.roundabout } ?: return null
        return firstName(legs, exit)
    }

    /** Length of the run of legs from [index] on that pass [test]. */
    private fun runLength(legs: List<RouteLeg>, index: Int, test: (RouteLeg) -> Boolean): Double =
        legs.drop(index).takeWhile(test).sumOf { it.lengthM }

    /**
     * Per leg, whether [test] holds, with runs shorter than [minM] between two runs of the other state
     * taken as that state (and a short run at either end as its neighbour's).
     */
    private fun smooth(legs: List<RouteLeg>, minM: Double, test: (RouteLeg) -> Boolean): BooleanArray {
        val state = BooleanArray(legs.size) { test(legs[it]) }
        var start = 0
        while (start < legs.size) {
            var end = start
            while (end + 1 < legs.size && state[end + 1] == state[start]) end++
            val length = (start..end).sumOf { legs[it].lengthM }
            val before = if (start > 0) state[start - 1] else null
            val after = if (end + 1 < legs.size) state[end + 1] else null
            val neighbour = before ?: after
            if (length < minM && neighbour != null && neighbour != state[start] && (before == null || after == null || before == after)) {
                for (k in start..end) state[k] = neighbour
            }
            start = end + 1
        }
        return state
    }

    /** Cycle tracks and shared paths: entering one is worth saying. 30+Bici streets are streets. */
    val CYCLE_TRACKS = setOf(EdgeInfra.SEGREGATED_TRACK, EdgeInfra.PAINTED_LANE, EdgeInfra.OSM_CYCLEWAY, EdgeInfra.SHARED_WITH_PEDESTRIANS)

    private val TURNS = setOf(
        Maneuver.SLIGHT_LEFT, Maneuver.LEFT, Maneuver.SHARP_LEFT, Maneuver.SLIGHT_RIGHT, Maneuver.RIGHT, Maneuver.SHARP_RIGHT, Maneuver.U_TURN,
    )

    const val STRAIGHT_DEG = 22.0
    const val SLIGHT_DEG = 50.0
    const val TURN_DEG = 130.0
    const val SHARP_DEG = 165.0

    /** Bearings are measured over this much route on each side of a junction. */
    private const val WINDOW_M = 15.0
    private const val JOG_M = 15.0
    private const val SETTLE_M = 15.0
    private const val MIN_WALK_M = 12.0
    private const val MIN_CYCLEWAY_M = 40.0
    private const val MIN_NAME_RUN_M = 30.0
    private const val NAME_LOOKAHEAD_M = 40.0
}
