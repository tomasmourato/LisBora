package pt.lisbora.routing.weighting

import pt.lisbora.routing.eta.BikeType
import pt.lisbora.routing.eta.RiderModel
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cost model of the router. The cost of a stretch of road is its travel time (from [RiderModel])
 * multiplied by comfort factors: infrastructure, traffic stress, surface, tram tracks, pushing and
 * grades above the rider's limit. Turns add penalties. Travel *time* (the ETA) uses the same speeds
 * without the comfort factors.
 *
 * Cycle infrastructure lowers the cost, but nothing here makes a route "safe"; that word is never used.
 */
class BikeWeighting(val graph: RoutingGraph, val preferences: RoutingPreferences) {

    val rider = RiderModel(preferences.rider)
    private val maxGrade = preferences.maxGradePercent?.div(100)
    private val fullCost = FloatArray(graph.edgeCount * 2) { Float.NaN }
    private val fullTime = FloatArray(graph.edgeCount * 2) { Float.NaN }

    /**
     * Lower bound of cost per metre over any edge, for an admissible A* heuristic: the smallest comfort
     * factor these preferences can give (every other multiplier is at least 1) at the fastest possible speed.
     */
    val minCostPerMetre: Double = EdgeInfra.entries.minOf { infraFactor(it, stress = 1) }.coerceAtMost(1.0) / max(
        preferences.rider.level.maxDescentKmh / 3.6 * preferences.rider.personalFactor,
        FERRY_SPEED_MPS,
    )

    /** Whether the directed edge [key] may be used at all. */
    fun canTraverse(key: Int): Boolean {
        val flags = graph.flags(graph.edgeOf(key))
        return flags.mode(graph.isReverse(key)) != TravelMode.NONE && edgeFactor(flags, graph.isReverse(key)).isFinite()
    }

    /** Cost of travelling the whole directed edge [key]; infinite when it cannot be used. */
    fun edgeCost(key: Int): Double {
        val cached = fullCost[key]
        if (!cached.isNaN()) return cached.toDouble()
        val edge = graph.edgeOf(key)
        return evaluate(edge, graph.isReverse(key), 0.0, graph.length(edge), wantCost = true)
            .also { fullCost[key] = it.toFloat() }
    }

    /** Travel time in seconds of the whole directed edge [key]. */
    fun edgeTime(key: Int): Double {
        val cached = fullTime[key]
        if (!cached.isNaN()) return cached.toDouble()
        val edge = graph.edgeOf(key)
        return evaluate(edge, graph.isReverse(key), 0.0, graph.length(edge), wantCost = false)
            .also { fullTime[key] = it.toFloat() }
    }

    /**
     * Cost of the part of [edge] between [from] and [to] metres from its base node (from ≤ to),
     * travelled base→adj or, when [reverse], adj→base.
     */
    fun partialCost(edge: Int, reverse: Boolean, from: Double, to: Double): Double =
        evaluate(edge, reverse, from, to, wantCost = true)

    fun partialTime(edge: Int, reverse: Boolean, from: Double, to: Double): Double =
        evaluate(edge, reverse, from, to, wantCost = false)

    /** Penalty in seconds for turning from [inKey] onto [outKey] at [via]; infinite when forbidden. */
    fun turnCost(inKey: Int, via: Int, outKey: Int): Double {
        if (graph.edgeOf(inKey) == graph.edgeOf(outKey) && inKey != outKey) {
            return if (isDeadEnd(inKey, via)) U_TURN_DEAD_END_S else Double.POSITIVE_INFINITY
        }
        if (graph.mode(inKey) == TravelMode.RIDE && graph.mode(outKey) == TravelMode.RIDE &&
            graph.isTurnRestricted(graph.edgeOf(inKey), via, graph.edgeOf(outKey))
        ) {
            return Double.POSITIVE_INFINITY
        }
        val angle = RoutingGraph.turnAngle(graph.endBearing(inKey), graph.startBearing(outKey))
        val junctionStress = graph.nodeStress(via)
        var penalty = when {
            angle < -STRAIGHT_DEGREES -> when {
                junctionStress >= 3 -> LEFT_TURN_BUSY_S
                junctionStress == 2 -> LEFT_TURN_MEDIUM_S
                else -> LEFT_TURN_CALM_S
            }
            angle > STRAIGHT_DEGREES -> RIGHT_TURN_S
            junctionStress >= 3 && max(stressOf(inKey), stressOf(outKey)) < junctionStress -> CROSS_BUSY_S
            else -> 0.0
        }
        if (RoutingGraph.isSharpTurn(angle)) penalty += SHARP_TURN_S
        return penalty
    }

    /** Expected delay in seconds of the same turn, for the ETA. */
    fun turnTime(inKey: Int, via: Int, outKey: Int): Double {
        if (graph.edgeOf(inKey) == graph.edgeOf(outKey)) return U_TURN_TIME_S
        val angle = RoutingGraph.turnAngle(graph.endBearing(inKey), graph.startBearing(outKey))
        return when {
            angle < -STRAIGHT_DEGREES -> LEFT_TURN_TIME_S
            angle > STRAIGHT_DEGREES -> RIGHT_TURN_TIME_S
            else -> 0.0
        }
    }

    private fun isDeadEnd(inKey: Int, via: Int): Boolean {
        val back = graph.reverseKey(inKey)
        graph.forEachOutgoing(via) { key -> if (key != back && canTraverse(key)) return false }
        return true
    }

    private fun stressOf(key: Int): Int = graph.flags(graph.edgeOf(key)).stress

    private fun evaluate(edge: Int, reverse: Boolean, from: Double, to: Double, wantCost: Boolean): Double {
        val flags = graph.flags(edge)
        val mode = flags.mode(reverse)
        if (mode == TravelMode.NONE) return Double.POSITIVE_INFINITY
        val factor = if (wantCost) edgeFactor(flags, reverse) else 1.0
        if (factor == Double.POSITIVE_INFINITY) return factor
        val length = graph.length(edge)
        if (to <= from || length <= 0) return 0.0

        var total = 0.0
        var start = 0.0
        val segments = graph.segmentCount(edge)
        for (i in 0 until segments) {
            val segmentLength = graph.segmentLength(edge, i)
            val end = if (i == segments - 1) max(length, start + segmentLength) else start + segmentLength
            val overlap = min(end, to) - max(start, from)
            if (overlap > 0) {
                val grade = graph.segmentGrade(edge, i).let { if (reverse) -it else it }
                val speed = speed(flags, mode, grade)
                var seconds = overlap / speed
                if (wantCost) seconds *= gradeFactor(flags, grade)
                total += seconds
            }
            start = end
            if (start >= to) break
        }
        val fraction = ((to - from) / length).coerceIn(0.0, 1.0)
        if (flags.trafficSignals) total += SIGNAL_DELAY_S * fraction
        if (flags.isFerry) total += FERRY_WAIT_S * fraction
        return total * factor
    }

    private fun speed(flags: EdgeFlags, mode: TravelMode, grade: Double): Double {
        if (flags.isFerry) return FERRY_SPEED_MPS
        if (mode == TravelMode.PUSH) return rider.walkingSpeed(grade)
        var speed = rider.ridingSpeed(grade, flags.surface)
        if (flags.infra == EdgeInfra.SHARED_WITH_PEDESTRIANS) speed = min(speed, SHARED_PATH_MAX_MPS)
        if (flags.tram) speed = min(speed, TRAM_TRACKS_MAX_MPS)
        return speed
    }

    /** Comfort multiplier of a whole edge in one direction (grade aside); infinite when excluded. */
    private fun edgeFactor(flags: EdgeFlags, reverse: Boolean): Double {
        if (flags.isSteps) return Double.POSITIVE_INFINITY
        if (flags.isFerry) return if (preferences.allowFerries) 1.0 else Double.POSITIVE_INFINITY
        val bike = preferences.rider.bike
        var factor = infraFactor(flags.infra, flags.stress)
        if (flags.mode(reverse) == TravelMode.PUSH) factor *= PUSH_FACTOR
        if (flags.tram) factor *= if (preferences.avoidTramTracks) 3.0 else 1.4
        factor *= when (flags.surface) {
            SurfaceKind.COBBLESTONE -> (if (preferences.avoidCobbles) 2.0 else 1.25) * if (bike == BikeType.ROAD) 1.3 else 1.0
            SurfaceKind.PAVING_STONES -> 1.05
            SurfaceKind.COMPACTED -> if (bike == BikeType.ROAD) 1.8 else 1.0
            SurfaceKind.GRAVEL, SurfaceKind.UNPAVED, SurfaceKind.SOFT, SurfaceKind.OTHER -> when (bike) {
                BikeType.ROAD -> 2.5
                BikeType.MOUNTAIN -> 1.0
                else -> if (flags.infra == EdgeInfra.TRAIL) 1.0 else 1.3
            }
            else -> 1.0
        }
        return factor
    }

    /** How the rider values a kind of infrastructure at a given traffic stress (below 1 = preferred). */
    private fun infraFactor(infra: EdgeInfra, stress: Int): Double = when (infra) {
        EdgeInfra.SEGREGATED_TRACK -> if (preferences.preferSegregated) 0.6 else 0.75
        EdgeInfra.PAINTED_LANE -> 0.9 * (1 + 0.1 * (stress - 1))
        EdgeInfra.SHARED_STREET_30 -> 0.95 * (1 + 0.05 * (stress - 1))
        EdgeInfra.SHARED_WITH_PEDESTRIANS -> when (preferences.purpose) {
            TripPurpose.LEISURE -> 0.85
            TripPurpose.COMMUTE -> 0.95
            TripPurpose.SPORT -> 1.2
        }
        EdgeInfra.TRAIL -> when (preferences.rider.bike) {
            BikeType.ROAD -> 4.0
            BikeType.CITY -> 1.5
            BikeType.ELECTRIC -> 1.4
            BikeType.MOUNTAIN -> 0.9
        }
        EdgeInfra.OSM_CYCLEWAY -> 0.8
        EdgeInfra.NONE -> STRESS_FACTORS[stress - 1] * if (preferences.preferSegregated) 1.15 else 1.0
    }

    /** Extra multiplier for the grade of a segment in the direction of travel. */
    private fun gradeFactor(flags: EdgeFlags, grade: Double): Double {
        var factor = 1.0
        if (maxGrade != null && grade > maxGrade) factor *= STEEP_CLIMB_FACTOR
        val rough = flags.tram || flags.surface == SurfaceKind.COBBLESTONE
        if (rough && grade < -STEEP_DESCENT_GRADE) factor *= ROUGH_DESCENT_FACTOR
        return factor
    }

    companion object {
        /** Multipliers by traffic stress (1..4) on roads without cycling infrastructure. */
        val STRESS_FACTORS = doubleArrayOf(1.0, 1.2, 1.7, 2.6)
        const val PUSH_FACTOR = 2.5
        const val STEEP_CLIMB_FACTOR = 6.0
        const val STEEP_DESCENT_GRADE = 0.08
        const val ROUGH_DESCENT_FACTOR = 1.5
        const val SHARED_PATH_MAX_MPS = 15 / 3.6
        const val TRAM_TRACKS_MAX_MPS = 25 / 3.6
        const val FERRY_SPEED_MPS = 5.0
        const val FERRY_WAIT_S = 15 * 60.0
        const val SIGNAL_DELAY_S = 15.0
        const val STRAIGHT_DEGREES = 35.0
        const val LEFT_TURN_BUSY_S = 12.0
        const val LEFT_TURN_MEDIUM_S = 4.0
        const val LEFT_TURN_CALM_S = 2.0
        const val RIGHT_TURN_S = 1.0
        const val CROSS_BUSY_S = 6.0
        const val SHARP_TURN_S = 4.0
        const val U_TURN_DEAD_END_S = 10.0
        const val U_TURN_TIME_S = 8.0
        const val LEFT_TURN_TIME_S = 3.0
        const val RIGHT_TURN_TIME_S = 1.0
    }
}

/** Absolute grade in percent, for display. */
fun Double.asPercent(): Double = abs(this) * 100
