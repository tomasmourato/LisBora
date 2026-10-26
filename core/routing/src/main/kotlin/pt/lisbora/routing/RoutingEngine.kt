package pt.lisbora.routing

import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphData
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.Snap
import pt.lisbora.routing.graph.SpatialIndex
import pt.lisbora.routing.weighting.BikeWeighting
import pt.lisbora.routing.weighting.RoutingPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * Edge-based A* router. Search states are directed edges ("arrived at the head of edge key k"),
 * which lets turn restrictions and turn penalties depend on both the incoming and outgoing edge.
 *
 * The start and destination are snapped onto edges; the partial edges around them are costed exactly.
 * Thread-safe: each search takes its own state (arrays as large as the graph, pooled between searches).
 */
class RoutingEngine(
    val graph: RoutingGraph,
    private val index: SpatialIndex = SpatialIndex(graph),
) {
    private val spaces = ArrayDeque<SearchSpace>()

    private val weightings = object : LinkedHashMap<RoutingPreferences, BikeWeighting>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RoutingPreferences, BikeWeighting>) = size > 4
    }

    fun weighting(preferences: RoutingPreferences): BikeWeighting =
        synchronized(weightings) { weightings.getOrPut(preferences) { BikeWeighting(graph, preferences) } }

    /** Snaps [point] onto the nearest edge usable under [preferences] (never a ferry or tunnel) that passes [accept]. */
    fun snap(
        point: GeoPoint,
        preferences: RoutingPreferences = RoutingPreferences(),
        maxDistanceM: Double = SpatialIndex.DEFAULT_MAX_SNAP_M,
        accept: (edge: Int) -> Boolean = { true },
    ): Snap? {
        val weighting = weighting(preferences)
        return index.snap(point, maxDistanceM) { edge ->
            val flags = graph.flags(edge)
            !flags.isFerry && !flags.tunnel && accept(edge) &&
                (weighting.canTraverse(graph.key(edge, false)) || weighting.canTraverse(graph.key(edge, true)))
        }
    }

    fun route(from: GeoPoint, to: GeoPoint, preferences: RoutingPreferences = RoutingPreferences()): RouteResult {
        val start = snap(from, preferences) ?: return RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK)
        val end = snap(to, preferences) ?: return RouteResult.Failure(RouteResult.Reason.END_NOT_ON_NETWORK)
        return route(start, end, preferences)
    }

    /**
     * Routes between two snapped positions. [heuristicWeight] 0 turns A* into Dijkstra (used by tests
     * to check that the heuristic never changes the result).
     */
    fun route(start: Snap, end: Snap, preferences: RoutingPreferences, heuristicWeight: Double = HEURISTIC_SAFETY): RouteResult {
        val weighting = weighting(preferences)
        val route = search(weighting, Origin(start), end, heuristicWeight) { it.buildRoute() }
        return if (route != null) RouteResult.Success(route) else RouteResult.Failure(RouteResult.Reason.NO_PATH)
    }

    /**
     * A route through [stops] in order (Descobrir). With [passThrough], each leg leaves a stop the way it
     * arrived, so the route goes on instead of turning back (unless there is no other way); without it the
     * route may turn back at a stop (out and back along the river). Edges used by earlier legs cost
     * [reusePenalty]x more, so a loop does not return along the streets it took. A [heuristicWeight] above 1
     * makes the searches much faster at the price of slightly costlier routes.
     */
    fun routeVia(
        stops: List<Snap>,
        preferences: RoutingPreferences,
        reusePenalty: Float = 1f,
        heuristicWeight: Double = HEURISTIC_SAFETY,
        passThrough: Boolean = true,
    ): RouteResult {
        require(stops.size >= 2) { "a route needs at least two stops" }
        require(reusePenalty >= 1f) { "reusePenalty must be at least 1" }
        val weighting = weighting(preferences)
        val penalty = if (reusePenalty > 1f) FloatArray(graph.edgeCount) { 1f } else null
        val builder = RouteBuilder(graph, weighting)
        var origin = Origin(stops[0])
        var settled = 0
        for (i in 1 until stops.size) {
            val legsBefore = builder.legCount
            val stop = stops[i]
            var found = search(weighting, origin, stop, heuristicWeight, penalty) { s -> s.appendTo(builder); settled += s.settled }
            if (found == null && origin.direction != null) {
                // Leaving the way it arrived is impossible (a one-way dead end): allow turning back.
                found = search(weighting, Origin(origin.snap), stop, heuristicWeight, penalty) { s -> s.appendTo(builder); settled += s.settled }
            }
            if (found == null) return RouteResult.Failure(RouteResult.Reason.NO_PATH)
            val added = builder.legsSince(legsBefore)
            penalty?.let { p -> added.forEach { p[it.edge] *= reusePenalty } }
            val arrival = added.lastOrNull()
            origin = if (arrival == null) Origin(stop)
            else Origin(Snap(arrival.edge, if (arrival.reverse) arrival.from else arrival.to, stop.point, 0.0), arrival.reverse.takeIf { passThrough })
        }
        if (builder.isEmpty()) builder.addPoint(stops[0].point, graph.pointElevation(stops[0].edge, 0))
        return RouteResult.Success(builder.build(settled))
    }

    /** The best route plus up to [maxAlternatives] clearly different ones, see [alternatives]. */
    fun route(from: GeoPoint, to: GeoPoint, preferences: RoutingPreferences, maxAlternatives: Int): RouteResult {
        val start = snap(from, preferences) ?: return RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK)
        val end = snap(to, preferences) ?: return RouteResult.Failure(RouteResult.Reason.END_NOT_ON_NETWORK)
        return alternatives(start, end, preferences, maxAlternatives)
    }

    /**
     * Penalty method: after each route found, the edges it uses cost [ALTERNATIVE_PENALTY]x more and the
     * search runs again. A candidate is kept when its real cost stays within [MAX_ALTERNATIVE_STRETCH]x the
     * best and it shares at most [MAX_ALTERNATIVE_OVERLAP] of its length with every route already kept
     * (Jaccard index over edge lengths). When the best route keeps under the rider's maximum grade, so must
     * the alternatives. Penalties are >= 1, so the A* heuristic stays admissible.
     */
    fun alternatives(
        start: Snap,
        end: Snap,
        preferences: RoutingPreferences,
        maxAlternatives: Int = 2,
        /** The best route between the same snaps, when already computed (to show it before the alternatives). */
        best: Route? = null,
    ): RouteResult {
        val weighting = weighting(preferences)
        val best = best ?: search(weighting, Origin(start), end, HEURISTIC_SAFETY) { it.buildRoute() }
            ?: return RouteResult.Failure(RouteResult.Reason.NO_PATH)
        if (maxAlternatives <= 0) return RouteResult.Success(best)

        val gradeLimit = preferences.maxGradePercent?.takeIf { best.maxGradePercent <= it + GRADE_TOLERANCE_PERCENT }
        val penalty = FloatArray(graph.edgeCount) { 1f }
        val kept = mutableListOf<Route>()
        var last = best
        for (attempt in 1..maxAlternatives + EXTRA_ALTERNATIVE_ATTEMPTS) {
            last.legs.forEach { penalty[it.edge] *= ALTERNATIVE_PENALTY }
            val candidate = search(weighting, Origin(start), end, HEURISTIC_SAFETY, penalty) { it.buildRoute() } ?: break
            last = candidate
            if (candidate.cost > best.cost * MAX_ALTERNATIVE_STRETCH) continue
            if (gradeLimit != null && candidate.maxGradePercent > gradeLimit + GRADE_TOLERANCE_PERCENT) continue
            if ((kept + best).all { overlap(it, candidate) <= MAX_ALTERNATIVE_OVERLAP }) kept += candidate
            if (kept.size >= maxAlternatives) break
        }
        return RouteResult.Success(best, kept.sortedBy { it.cost })
    }

    /** Runs one search from [origin] to [end]; [result] reads it while its pooled arrays are still held. */
    private inline fun <T> search(
        weighting: BikeWeighting,
        origin: Origin,
        end: Snap,
        heuristicWeight: Double,
        penalty: FloatArray? = null,
        result: (Search) -> T,
    ): T? {
        val space = synchronized(spaces) { spaces.removeLastOrNull() } ?: SearchSpace(graph.edgeCount * 2 + 1)
        try {
            val search = Search(space, weighting, origin, end, heuristicWeight, penalty)
            return if (search.run()) result(search) else null
        } finally {
            synchronized(spaces) { if (spaces.size < MAX_POOLED_SPACES) spaces.addLast(space) }
        }
    }

    /** Where a search starts: a snapped position, left in one [direction] of its edge (true = adj→base) or either. */
    private class Origin(val snap: Snap, val direction: Boolean? = null)

    private inner class Search(
        private val space: SearchSpace,
        private val weighting: BikeWeighting,
        origin: Origin,
        private val end: Snap,
        heuristicWeight: Double,
        /** Per-edge cost multipliers (>= 1) used to look for alternatives and loops; null for a plain search. */
        private val penalty: FloatArray? = null,
    ) {
        private val start = origin.snap
        private val direction = origin.direction
        private val target = graph.edgeCount * 2
        private val heap = space.heap
        private val targetPoint = end.point
        private val costPerMetre = weighting.minCostPerMetre * heuristicWeight
        private var targetReverse = false
        var settled = 0
            private set

        init {
            space.reset()
        }

        private fun cost(key: Int): Double = space.cost(key)
        private fun parent(key: Int): Int = space.parent(key)

        fun run(): Boolean {
            seedStart()
            while (heap.isNotEmpty()) {
                val priority = heap.peekPriority()
                val key = heap.pop()
                if (key == target) return true
                val keyCost = cost(key)
                if (priority > keyCost + heuristic(graph.headNode(key)) + EPSILON) continue // stale entry
                settled++
                val via = graph.headNode(key)
                tryFinish(key, via)
                graph.forEachOutgoing(via) { out ->
                    val edgeCost = weighting.edgeCost(out) * penaltyOf(graph.edgeOf(out))
                    if (edgeCost.isFinite()) {
                        val turn = weighting.turnCost(key, via, out)
                        if (turn.isFinite()) relax(out, keyCost + turn + edgeCost, key)
                    }
                }
            }
            return false
        }

        private fun seedStart() {
            val edge = start.edge
            val length = graph.length(edge)
            val forward = direction != true
            val backward = direction != false
            // A start exactly on a node needs no travel along the edge, whatever its direction rules.
            if (forward) relax(graph.key(edge, false), partialOrZero(edge, false, start.along, length), FROM_START)
            if (backward) relax(graph.key(edge, true), partialOrZero(edge, true, 0.0, start.along), FROM_START)
            if (end.edge == edge) {
                if (forward && end.along >= start.along) {
                    relaxTarget(partialOrZero(edge, false, start.along, end.along), DIRECT, reverse = false)
                }
                if (backward && end.along <= start.along) {
                    relaxTarget(partialOrZero(edge, true, end.along, start.along), DIRECT, reverse = true)
                }
            }
        }

        private fun tryFinish(key: Int, via: Int) {
            val edge = end.edge
            if (via == graph.data.edgeBase[edge]) finishVia(key, via, reverse = false)
            if (via == graph.data.edgeAdj[edge]) finishVia(key, via, reverse = true)
        }

        private fun finishVia(key: Int, via: Int, reverse: Boolean) {
            val edge = end.edge
            val (from, to) = if (reverse) end.along to graph.length(edge) else 0.0 to end.along
            if (to - from <= ZERO_LENGTH_M) {
                // The destination is this very node.
                relaxTarget(cost(key), key, reverse)
                return
            }
            val out = graph.key(edge, reverse)
            if (!weighting.canTraverse(out)) return
            val turn = weighting.turnCost(key, via, out)
            if (!turn.isFinite()) return
            relaxTarget(cost(key) + turn + weighting.partialCost(edge, reverse, from, to) * penaltyOf(edge), key, reverse)
        }

        private fun partialOrZero(edge: Int, reverse: Boolean, from: Double, to: Double): Double =
            if (to - from <= ZERO_LENGTH_M) 0.0 else weighting.partialCost(edge, reverse, from, to) * penaltyOf(edge)

        /** The start and destination edges are penalised too: a loop must not turn back along its first street. */
        private fun penaltyOf(edge: Int): Float = penalty?.get(edge) ?: 1f

        private fun relax(key: Int, newCost: Double, from: Int) {
            if (!newCost.isFinite() || newCost >= cost(key)) return
            space.set(key, newCost, from)
            heap.push(newCost + heuristic(graph.headNode(key)), key)
        }

        private fun relaxTarget(newCost: Double, from: Int, reverse: Boolean) {
            if (!newCost.isFinite() || newCost >= cost(target)) return
            space.set(target, newCost, from)
            targetReverse = reverse
            heap.push(newCost, target)
        }

        private fun heuristic(node: Int): Double =
            if (costPerMetre == 0.0) 0.0 else graph.nodePoint(node).distanceTo(targetPoint) * costPerMetre

        fun buildRoute(): Route {
            val builder = RouteBuilder(graph, weighting)
            appendTo(builder)
            if (builder.isEmpty()) builder.addPoint(start.point, graph.pointElevation(start.edge, 0))
            return builder.build(settled)
        }

        /** Adds the legs found, from the start to the destination, to [builder]. */
        fun appendTo(builder: RouteBuilder) {
            // Directed edge keys from start to destination, then convert each into a leg.
            val keys = ArrayDeque<Int>()
            var current = parent(target)
            while (current >= 0) {
                keys.addFirst(current)
                current = parent(current)
            }
            if (parent(target) == DIRECT) {
                val (from, to) = if (targetReverse) end.along to start.along else start.along to end.along
                builder.addLeg(end.edge, targetReverse, from, to)
            } else {
                keys.forEachIndexed { i, key ->
                    val edge = graph.edgeOf(key)
                    val reverse = graph.isReverse(key)
                    val (from, to) = when {
                        i == 0 && reverse -> 0.0 to start.along
                        i == 0 -> start.along to graph.length(edge)
                        else -> 0.0 to graph.length(edge)
                    }
                    if (i > 0) builder.addTurn(keys[i - 1], key)
                    builder.addLeg(edge, reverse, from, to)
                }
                val (from, to) = if (targetReverse) end.along to graph.length(end.edge) else 0.0 to end.along
                if (to - from > ZERO_LENGTH_M) {
                    builder.addTurn(keys.last(), graph.key(end.edge, targetReverse))
                    builder.addLeg(end.edge, targetReverse, from, to)
                }
            }
        }
    }

    companion object {
        /** Slightly shrinks the heuristic so rounding never makes it overestimate. */
        const val HEURISTIC_SAFETY = 0.99

        /** Cost multiplier applied to the edges of each route found while looking for alternatives. */
        const val ALTERNATIVE_PENALTY = 1.5f

        /** Alternatives may cost at most this much more than the best route. */
        const val MAX_ALTERNATIVE_STRETCH = 1.3

        /** Largest Jaccard overlap (by length) an alternative may have with any route already kept. */
        const val MAX_ALTERNATIVE_OVERLAP = 0.6

        /** Searches allowed beyond the requested alternatives, for candidates that turn out too similar or too long. */
        const val EXTRA_ALTERNATIVE_ATTEMPTS = 3

        /** Slack when comparing a route's steepest grade with the rider's maximum (DEM noise). */
        const val GRADE_TOLERANCE_PERCENT = 0.5

        /** Search arrays kept for reuse (each is ~16 bytes per directed edge, ~6 MB for Lisbon). */
        private const val MAX_POOLED_SPACES = 4

        /** Jaccard index of two routes over the lengths of the edges they use (0 = disjoint, 1 = same). */
        fun overlap(a: Route, b: Route): Double {
            val lengthsA = a.legs.groupingBy { it.edge }.fold(0.0) { sum, leg -> sum + leg.lengthM }
            val lengthsB = b.legs.groupingBy { it.edge }.fold(0.0) { sum, leg -> sum + leg.lengthM }
            val shared = lengthsA.entries.sumOf { (edge, length) -> min(length, lengthsB[edge] ?: 0.0) }
            val union = a.distanceM + b.distanceM - shared
            return if (union <= 0) 1.0 else shared / union
        }

        private const val FROM_START = -2
        private const val DIRECT = -3
        private const val EPSILON = 1e-9
        internal const val ZERO_LENGTH_M = 0.01
    }
}

/**
 * The arrays of one search, reused by the next: [generation] marks which entries belong to the current
 * search, so nothing has to be cleared between searches.
 */
private class SearchSpace(size: Int) {
    private val cost = DoubleArray(size)
    private val parent = IntArray(size)
    private val stamp = IntArray(size)
    private var generation = 0
    val heap = MinHeap()

    fun reset() {
        heap.clear()
        if (++generation == Int.MAX_VALUE) {
            stamp.fill(0)
            generation = 1
        }
    }

    fun cost(key: Int): Double = if (stamp[key] == generation) cost[key] else Double.POSITIVE_INFINITY

    fun parent(key: Int): Int = if (stamp[key] == generation) parent[key] else NO_PARENT

    fun set(key: Int, newCost: Double, from: Int) {
        stamp[key] = generation
        cost[key] = newCost
        parent[key] = from
    }

    private companion object {
        const val NO_PARENT = -1
    }
}

/** Accumulates legs into a [Route]: geometry, elevation profile, distance, ETA and climb. */
private class RouteBuilder(private val graph: RoutingGraph, private val weighting: BikeWeighting) {
    private val points = ArrayList<GeoPoint>()
    private val elevations = ArrayList<Double>()
    /** Distance along the route of each point. */
    private val along = ArrayList<Double>()
    private val legs = ArrayList<RouteLeg>()
    private var distance = 0.0
    private var time = 0.0
    private var cost = 0.0
    private var ascent = 0.0
    private var descent = 0.0

    fun isEmpty() = points.isEmpty()

    val legCount: Int get() = legs.size

    fun legsSince(index: Int): List<RouteLeg> = legs.subList(index, legs.size)

    fun addPoint(point: GeoPoint, elevation: Double) {
        along += if (points.isEmpty()) 0.0 else along.last() + points.last().distanceTo(point)
        points += point
        elevations += elevation
    }

    fun addTurn(inKey: Int, outKey: Int) {
        val via = graph.tailNode(outKey)
        time += weighting.turnTime(inKey, via, outKey)
        cost += weighting.turnCost(inKey, via, outKey)
    }

    fun addLeg(edge: Int, reverse: Boolean, from: Double, to: Double) {
        val length = max(0.0, to - from)
        if (length <= RoutingEngine.ZERO_LENGTH_M) return
        val legTime = weighting.partialTime(edge, reverse, from, to)
        // Whole edges use the cached cost, exactly as the search added it.
        cost += if (from <= 0.0 && to >= graph.length(edge)) weighting.edgeCost(graph.key(edge, reverse))
        else weighting.partialCost(edge, reverse, from, to)
        val first = if (points.isEmpty()) 0 else points.size - 1
        val slice = slice(edge, from, to)
        if (reverse) slice.reverse()
        slice.forEachIndexed { i, (point, elevation) ->
            if (i == 0 && points.isNotEmpty()) return@forEachIndexed // shared with the previous leg's end
            if (elevations.isNotEmpty()) {
                val dz = elevation - elevations.last()
                if (dz > 0) ascent += dz else descent -= dz
            }
            addPoint(point, elevation)
        }
        legs += RouteLeg(edge, reverse, from, to, graph.flags(edge), graph.name(edge), length, legTime, first, along[first])
        distance += length
        time += legTime
    }

    fun build(settled: Int) = Route(
        points = points.toList(),
        elevations = elevations.toList(),
        distances = along.toList(),
        legs = legs.toList(),
        distanceM = distance,
        durationS = time,
        ascentM = ascent,
        descentM = descent,
        maxGradePercent = RouteAnalysis.steepestClimbPercent(along, elevations),
        cost = cost,
        settledStates = settled,
    )

    /** Shape points of [edge] between [from] and [to] metres from its base, with interpolated ends. */
    private fun slice(edge: Int, from: Double, to: Double): MutableList<Pair<GeoPoint, Double>> {
        val result = ArrayList<Pair<GeoPoint, Double>>()
        val count = graph.pointCount(edge)
        var along = 0.0
        result += interpolate(edge, from)
        for (i in 1 until count - 1) {
            along += graph.segmentLength(edge, i - 1)
            if (along > from && along < to) result += graph.point(edge, i) to graph.pointElevation(edge, i)
        }
        result += interpolate(edge, to)
        return result
    }

    private fun interpolate(edge: Int, distance: Double): Pair<GeoPoint, Double> {
        var along = 0.0
        val last = graph.pointCount(edge) - 1
        for (i in 0 until last) {
            val length = graph.segmentLength(edge, i)
            if (distance <= along + length || i == last - 1) {
                val t = if (length > 0) ((distance - along) / length).coerceIn(0.0, 1.0) else 0.0
                val lat = graph.pointLatE7(edge, i) + t * (graph.pointLatE7(edge, i + 1) - graph.pointLatE7(edge, i))
                val lon = graph.pointLonE7(edge, i) + t * (graph.pointLonE7(edge, i + 1) - graph.pointLonE7(edge, i))
                val ele = graph.pointElevation(edge, i) + t * (graph.pointElevation(edge, i + 1) - graph.pointElevation(edge, i))
                return GeoPoint(lat / GraphData.E7, lon / GraphData.E7) to ele
            }
            along += length
        }
        return graph.point(edge, 0) to graph.pointElevation(edge, 0)
    }

    companion object {
        /** Stretch over which the route's steepest grade is measured, roughly what a rider perceives. */
    }
}

/** Binary min-heap of (priority, int value) pairs, allowing duplicates. */
private class MinHeap {
    private var priorities = DoubleArray(1024)
    private var values = IntArray(1024)
    private var size = 0

    fun isNotEmpty() = size > 0

    fun clear() {
        size = 0
    }

    fun peekPriority(): Double = priorities[0]

    fun push(priority: Double, value: Int) {
        if (size == priorities.size) {
            priorities = priorities.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val up = (i - 1) / 2
            if (priorities[up] <= priority) break
            priorities[i] = priorities[up]
            values[i] = values[up]
            i = up
        }
        priorities[i] = priority
        values[i] = value
    }

    fun pop(): Int {
        val top = values[0]
        size--
        if (size > 0) {
            val priority = priorities[size]
            val value = values[size]
            var i = 0
            while (true) {
                var child = 2 * i + 1
                if (child >= size) break
                if (child + 1 < size && priorities[child + 1] < priorities[child]) child++
                if (priorities[child] >= priority) break
                priorities[i] = priorities[child]
                values[i] = values[child]
                i = child
            }
            priorities[i] = priority
            values[i] = value
        }
        return top
    }
}
