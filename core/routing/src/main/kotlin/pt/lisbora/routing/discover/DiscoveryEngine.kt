package pt.lisbora.routing.discover

import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.eta.RiderModel
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.Snap
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.weighting.BikeWeighting
import pt.lisbora.routing.weighting.RoutingPreferences
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Descobrir: routes for a time available and a purpose, without a set destination (a loop) or with one
 * (A→B taking the time there is).
 *
 * 1. The time becomes a budget of straight-line metres between stops: estimated from the rider's speed
 *    (and, for A→B, the direct route), then measured on a few routes around the start (hills and the river
 *    make it vary a lot across Lisbon).
 * 2. Candidate tours go through places that suit the purpose (points of interest, riverside or green
 *    spots of the graph), chosen by a small beam search over combinations of 1 to 3 that fit the budget,
 *    or through virtual points on a circle (loops) or an ellipse (A→B) with varied headings.
 * 3. Each tour is routed through its stops with the purpose's preferences ([RoutingEngine.routeVia]: no
 *    U-turns at stops, and streets already ridden cost more). Tours that miss the time are resized once.
 * 4. Candidates that fit the time are scored on infrastructure and calm streets, scenery, places passed,
 *    climbs as the purpose wants them, repeated streets and hazards; the best clearly different ones win.
 *
 * Thread-safe; [TaskRunner] decides whether the candidate routes are computed in parallel.
 */
class DiscoveryEngine(
    private val routing: RoutingEngine,
    pois: List<Poi>,
    /** Weighted A* for the many searches of a discovery (see [RoutingEngine.routeVia]). */
    private val heuristicWeight: Double = DEFAULT_HEURISTIC_WEIGHT,
) {
    private val graph = routing.graph
    val pois = PoiIndex(pois)

    /**
     * Connected part of the network of each edge, without ferries or steps: the south bank is one of its
     * own, since Descobrir never takes the ferry. Every stop must lie in the start's part.
     */
    private val edgeComponent: IntArray = components()

    /** The rideable edge each point of interest is reached from, or [NO_EDGE]. */
    private val poiEdge = IntArray(this.pois.pois.size) { i ->
        routing.snap(this.pois.pois[i].point, RoutingPreferences(), VIA_SNAP_M) { graph.flags(it).isRideable() }?.edge ?: NO_EDGE
    }

    /** Riverside and green streets of the graph, one point per ~[SPOT_CELL_M] cell (with its edge). */
    private val riversideSpots: Map<GeoPoint, Int> = spots { it.riverside }
    private val greenSpots: Map<GeoPoint, Int> = spots { it.green }

    /** [trace] receives one line per candidate, for tuning (the pipeline's `discover --detalhe sim`). */
    fun discover(
        request: DiscoveryRequest,
        runner: TaskRunner = TaskRunner.Sequential,
        trace: ((String) -> Unit)? = null,
    ): DiscoveryResult {
        val profile = DiscoveryProfile.of(request.purpose, request.tweaks)
        val preferences = profile.preferences(request.preferences)
        val start = routing.snap(request.start, preferences)
            ?: return DiscoveryResult.Failure(DiscoveryResult.Reason.START_NOT_ON_NETWORK)
        val end = request.end?.let {
            routing.snap(it, preferences) ?: return DiscoveryResult.Failure(DiscoveryResult.Reason.END_NOT_ON_NETWORK)
        }
        return Discovery(request, profile, preferences, start, end, trace).run(runner)
    }

    /**
     * Computes a route again from the [stops] it was built through (start and end included), e.g. a saved
     * one, with today's data. Null when a stop is no longer on the network or no route joins them.
     */
    fun rebuild(
        stops: List<GeoPoint>,
        purpose: DiscoveryPurpose,
        tweaks: Set<DiscoveryTweak>,
        preferences: RoutingPreferences,
        turnsBack: Boolean = false,
    ): DiscoveredRoute? {
        if (stops.size < 2) return null
        val profile = DiscoveryProfile.of(purpose, tweaks)
        val prefs = profile.preferences(preferences)
        val start = routing.snap(stops.first(), prefs) ?: return null
        val component = edgeComponent[start.edge]
        val snaps = stops.mapIndexed { i, point ->
            when (i) {
                0 -> start
                stops.lastIndex -> routing.snap(point, prefs)
                else -> snapVia(point, prefs, component)
            } ?: return null
        }
        val route = (routing.routeVia(snaps, prefs, profile.reusePenalty, heuristicWeight, passThrough = !turnsBack) as? RouteResult.Success)
            ?.route ?: return null
        val anchors = stops.subList(1, stops.size - 1).mapNotNull { stop ->
            pois.near(stop, 1.0).firstOrNull()
        }
        return DiscoveredRoute(route, stops, anchors, turnsBack, highlights(route, anchors, profile), score = 0.0)
    }

    /**
     * A route found elsewhere (an imported GPX) described as Descobrir describes its own, for its card:
     * the river, parks and places it passes. It may turn back at its [stops], as the track did.
     */
    fun describe(route: Route, stops: List<GeoPoint>, purpose: DiscoveryPurpose = DiscoveryPurpose.LEISURE): DiscoveredRoute =
        DiscoveredRoute(route, stops, emptyList(), turnsBack = true, highlights(route, emptyList(), DiscoveryProfile.of(purpose)), score = 0.0)

    /** A stop on a rideable street of [component], preferably a calm one or one with infrastructure. */
    private fun snapVia(point: GeoPoint, preferences: RoutingPreferences, component: Int): Snap? =
        routing.snap(point, preferences, VIA_SNAP_NEAR_M) { e ->
            val flags = graph.flags(e)
            edgeComponent[e] == component && flags.isRideable() && (flags.stress <= 2 || flags.infra != EdgeInfra.NONE)
        } ?: routing.snap(point, preferences, VIA_SNAP_M) { e -> edgeComponent[e] == component && graph.flags(e).isRideable() }

    private fun EdgeFlags.isRideable() = mode(false) == TravelMode.RIDE || mode(true) == TravelMode.RIDE

    private fun components(): IntArray {
        val parent = IntArray(graph.nodeCount) { it }
        fun find(node: Int): Int {
            var root = node
            while (parent[root] != root) {
                parent[root] = parent[parent[root]]
                root = parent[root]
            }
            return root
        }
        for (e in 0 until graph.edgeCount) {
            val flags = graph.flags(e)
            if (flags.isFerry || flags.isSteps || (flags.mode(false) == TravelMode.NONE && flags.mode(true) == TravelMode.NONE)) continue
            val a = find(graph.data.edgeBase[e])
            val b = find(graph.data.edgeAdj[e])
            if (a != b) parent[a] = b
        }
        return IntArray(graph.edgeCount) { find(graph.data.edgeBase[it]) }
    }

    private fun spots(accept: (EdgeFlags) -> Boolean): Map<GeoPoint, Int> {
        val cells = LinkedHashMap<Long, Pair<GeoPoint, Int>>()
        val frame = LocalFrame(graph.nodePoint(0))
        for (edge in 0 until graph.edgeCount) {
            val flags = graph.flags(edge)
            if (!accept(flags) || !flags.isRideable() || graph.length(edge) < MIN_SPOT_EDGE_M) continue
            val middle = graph.point(edge, graph.pointCount(edge) / 2)
            val key = (Math.floorDiv(frame.y(middle).toLong(), SPOT_CELL_M.toLong()) shl 32) or
                (Math.floorDiv(frame.x(middle).toLong(), SPOT_CELL_M.toLong()) and 0xffffffffL)
            cells.putIfAbsent(key, middle to edge)
        }
        return cells.values.toMap(LinkedHashMap())
    }

    private fun highlights(route: Route, anchors: List<Poi>, profile: DiscoveryProfile): RouteHighlights {
        var riverside = 0.0
        var green = 0.0
        var segregated = 0.0
        route.legs.forEach { leg ->
            if (leg.flags.riverside) riverside += leg.lengthM
            if (leg.flags.green) green += leg.lengthM
            if (leg.infra == EdgeInfra.SEGREGATED_TRACK) segregated += leg.lengthM
        }
        val stress = RouteAnalysis.lengthByStress(route)
        val samples = (0..(route.distanceM / POI_SAMPLE_M).toInt()).map { RouteAnalysis.pointAt(route, it * POI_SAMPLE_M) }
        val passed = pois.along(samples, POI_NEAR_M) { poi ->
            poi.kind in profile.interestingKinds && poi.name != null && poi.importance >= (MIN_PASSED_IMPORTANCE[poi.kind] ?: 0)
        }
        // The same place is often mapped twice (a node and an area): name it once.
        val named = (anchors + passed).distinctBy { it.kind to it.name }
        return RouteHighlights(
            riversideM = riverside,
            greenM = green,
            segregatedM = segregated,
            infraShare = route.infraShare,
            calmShare = (stress[0] + stress[1]) / route.distanceM.coerceAtLeast(1.0),
            pois = named,
            netDescentM = route.elevations.first() - route.elevations.last(),
        )
    }

    /** A stop of a tour: a point of interest, a scenic spot, or a virtual point that shapes the loop. */
    private class Via(val point: GeoPoint, val poi: Poi? = null, val virtual: Boolean = false)

    /** Stops between [start] and [end]; [turnBack]: the route may turn back at them (out and back). */
    private class Tour(val start: GeoPoint, val end: GeoPoint, val vias: List<Via>, val turnBack: Boolean = false) {
        val points: List<GeoPoint> = listOf(start) + vias.map { it.point } + end
        val straightM: Double = points.zipWithNext { a, b -> a.distanceTo(b) }.sum().coerceAtLeast(1.0)
        val anchors: List<Poi> get() = vias.mapNotNull { it.poi }
        val hasVirtual: Boolean get() = vias.any { it.virtual }
    }

    private class Candidate(val tour: Tour, val route: Route, val highlights: RouteHighlights, val score: Double)

    /** A place worth going through, with how much it is worth to this discovery. */
    private class Anchor(val point: GeoPoint, val poi: Poi?, val value: Double)

    private inner class Discovery(
        private val request: DiscoveryRequest,
        private val profile: DiscoveryProfile,
        private val preferences: RoutingPreferences,
        private val start: Snap,
        private val end: Snap?,
        private val trace: ((String) -> Unit)?,
    ) {
        private val component = edgeComponent[start.edge]
        private val weighting = routing.weighting(preferences)
        private val random = Random(request.seed * SEED_STRIDE + request.purpose.ordinal * 31 + request.tweaks.sumOf { 1 shl it.ordinal })
        private val from = request.start
        private val to = request.end ?: request.start
        private val target = request.durationS
        private val frame = LocalFrame(from)

        /** Seconds a metre of straight line between stops takes on the network: estimated, then measured. */
        private var secondsPerMetre =
            DETOUR_FACTOR / (RiderModel(preferences.rider).ridingSpeed(0.0, SurfaceKind.PAVED) * CRUISE_SHARE)

        fun run(runner: TaskRunner): DiscoveryResult {
            if (end != null) {
                val direct = (routing.route(start, end, preferences) as? RouteResult.Success)?.route
                    ?: return DiscoveryResult.Failure(DiscoveryResult.Reason.NO_ROUTE)
                if (direct.durationS >= target * DIRECT_ONLY_SHARE) {
                    val tour = Tour(from, to, emptyList())
                    val candidate = Candidate(tour, direct, highlights(direct, emptyList(), profile), score = 0.0)
                    return DiscoveryResult.Success(listOf(candidate.toDiscovered()), directOnly = true)
                }
                val straight = from.distanceTo(to)
                if (straight > MIN_CALIBRATION_M) secondsPerMetre = (secondsPerMetre + direct.durationS / straight) / 2
            }

            // A few plain tours around the start measure how long a straight metre really takes from here.
            val calibration = evaluateAll(geometricTours(target / secondsPerMetre, CALIBRATION_TOURS), runner)
            if (calibration.isNotEmpty()) {
                secondsPerMetre = calibration.map { it.route.durationS / it.tour.straightM }.sorted().let { it[it.size / 2] }
            }
            val budget = target / secondsPerMetre
            val anchored = anchoredTours(budget, ANCHORED_TOURS)
            val candidates = (calibration + evaluateAll(anchored + geometricTours(budget, CANDIDATE_TOURS - anchored.size), runner))
                .toMutableList()
            if (candidates.isEmpty()) return DiscoveryResult.Failure(DiscoveryResult.Reason.NO_ROUTE)

            // Resize the tours that missed the time (see [resize]).
            val resized = candidates
                .filter { misfit(it.route) > FIT_TOLERANCE }
                .sortedByDescending { it.score }
                .mapNotNull { resize(it.tour, target / it.route.durationS) }
                .take(MAX_RESIZED)
            val fitting = candidates.count { misfit(it.route) <= FIT_TOLERANCE }
            val extra = if (fitting <= request.maxRoutes) geometricTours(budget, EXTRA_TOURS) else emptyList()
            candidates += evaluateAll(resized + extra, runner)

            return DiscoveryResult.Success(select(candidates).map { it.toDiscovered() })
        }

        private fun Candidate.toDiscovered() = DiscoveredRoute(route, tour.points, tour.anchors, tour.turnBack, highlights, score)

        private fun evaluateAll(tours: List<Tour>, runner: TaskRunner): List<Candidate> {
            val evaluations = runner.runAll(tours.map { tour -> { evaluate(tour) } })
            val results = evaluations.map { it.candidate }
            trace?.let { log ->
                tours.zip(evaluations).forEach { (tour, evaluation) ->
                    val c = evaluation.candidate
                    val stops = tour.vias.joinToString(" → ") { v ->
                        v.poi?.name ?: if (v.virtual) "virtual" else if (v.point in riversideSpots) "rio" else "verde"
                    }
                    log(
                        if (c == null) "        ✗ [$stops] ${evaluation.failure} (%.1f km em linha reta)".format(tour.straightM / 1000)
                        else "        %s [%s%s] %.0f min (%+.0f%%), %.1f km, rio %.0f%%, sobe %.0f m, repete %.0f%%, pontuação %.2f".format(
                            if (misfit(c.route) <= MAX_SELECTED_MISFIT) "·" else "✗", stops, if (tour.turnBack) ", ida e volta" else "",
                            c.route.durationS / 60, (c.route.durationS / target - 1) * 100, c.route.distanceM / 1000,
                            c.highlights.riversideM / c.route.distanceM * 100, c.route.ascentM, repeatedShare(c.route) * 100, c.score,
                        ),
                    )
                }
            }
            return results.filterNotNull()
        }

        private fun evaluate(tour: Tour): Evaluation {
            val snaps = ArrayList<Snap>(tour.vias.size + 2)
            val vias = ArrayList<Via>(tour.vias.size)
            snaps += start
            tour.vias.forEachIndexed { i, via ->
                val (placed, snap) = place(via) ?: return Evaluation(null, "paragem ${i + 1} fora da rede")
                vias += placed
                snaps += snap
            }
            snaps += end ?: start
            val result = routing.routeVia(snaps, preferences, profile.reusePenalty, heuristicWeight, passThrough = !tour.turnBack)
            if (result !is RouteResult.Success) return Evaluation(null, "sem caminho")
            val route = result.route
            if (route.distanceM < MIN_ROUTE_M) return Evaluation(null, "demasiado curta")
            val highlights = highlights(route, tour.anchors, profile)
            // The stops actually used, so the route can be computed again and calibration measures them.
            val placed = if (vias.indices.all { vias[it] === tour.vias[it] }) tour else Tour(tour.start, tour.end, vias, tour.turnBack)
            return Evaluation(Candidate(placed, route, highlights, score(route, highlights)), null)
        }

        /**
         * [via] on the network. A virtual stop off it (in the river, beyond the region) moves in towards the
         * start (loops) or the straight line (A→B) until it reaches a street.
         */
        private fun place(via: Via): Pair<Via, Snap>? {
            snapVia(via.point, preferences, component)?.let { return via to it }
            if (!via.virtual) return null
            val vx = frame.x(via.point)
            val vy = frame.y(via.point)
            val (px, py) = if (request.isLoop) 0.0 to 0.0 else projectOnStraight(vx, vy)
            for (share in PULL_IN_SHARES) {
                val point = frame.point(px + (vx - px) * share, py + (vy - py) * share) ?: continue
                snapVia(point, preferences, component)?.let { return Via(point, virtual = true) to it }
            }
            return null
        }

        private inner class Evaluation(val candidate: Candidate?, val failure: String?)

        private fun misfit(route: Route): Double = abs(route.durationS / target - 1)

        // --- scoring ---------------------------------------------------------------------------

        private fun score(route: Route, h: RouteHighlights): Double {
            val w = profile.weights
            val distance = route.distanceM.coerceAtLeast(1.0)
            val km = distance / 1000
            val deviation = route.durationS / target - 1
            val fit = exp(-(deviation / if (deviation > 0) FIT_SCALE_OVER else FIT_SCALE_UNDER).let { it * it })
            val busy = route.legs.filter { it.mode == TravelMode.RIDE && it.infra == EdgeInfra.NONE && it.flags.stress >= 4 }
                .sumOf { it.lengthM } / distance
            // Each kind counts for about two well-known places at most: ten statues are not a viewpoint.
            val poiValue = h.pois.groupBy { it.kind }.entries.sumOf { (kind, places) ->
                val worth = profile.anchorKinds[kind] ?: OTHER_POI_WORTH
                min(places.sumOf { worth * (1 + it.importance / 100.0) }, worth * MAX_KIND_VALUE)
            }
            val signals = route.legs.count { it.flags.trafficSignals } / km
            val roughDescent = RouteAnalysis.steepestRoughDescentPercent(route)
            val avoided = request.avoid.maxOfOrNull { RoutingEngine.overlap(it, route) } ?: 0.0
            return FIT_WEIGHT * fit +
                w.infra * h.infraShare + w.calm * h.calmShare +
                w.riverside * h.riversideM / distance + w.green * h.greenM / distance +
                w.pois * min(poiValue, MAX_POI_VALUE) +
                w.ascentPerKm * route.ascentM / km -
                w.steepClimb * max(0.0, route.maxGradePercent - w.gentleClimbPercent) +
                w.signalsPerKm * signals +
                w.smoothDescentPerKm * smoothDescentM(route) / km -
                w.repeated * repeatedShare(route) -
                BUSY_WEIGHT * busy -
                WALK_WEIGHT * route.pushedM / distance -
                ROUGH_DESCENT_WEIGHT * (roughDescent - ROUGH_DESCENT_OK_PERCENT).coerceIn(0.0, ROUGH_DESCENT_MAX_PERCENT) -
                AVOID_WEIGHT * max(0.0, avoided - AVOID_FROM_OVERLAP)
        }

        /** Share of the distance on streets the route already took (out and back the same way). */
        private fun repeatedShare(route: Route): Double {
            val seen = HashSet<Int>()
            var repeated = 0.0
            var previous = -1
            route.legs.forEach { leg ->
                // Two legs on the same edge in a row are one pass split at a stop.
                if (leg.edge != previous && !seen.add(leg.edge)) repeated += leg.lengthM
                previous = leg.edge
            }
            return repeated / route.distanceM.coerceAtLeast(1.0)
        }

        /** Metres ridden downhill on smooth surfaces (no cobbles, no tram rails). */
        private fun smoothDescentM(route: Route): Double {
            var total = 0.0
            route.legs.forEachIndexed { l, leg ->
                if (leg.mode != TravelMode.RIDE || BikeWeighting.isRough(leg.flags)) return@forEachIndexed
                val last = route.legs.getOrNull(l + 1)?.firstPoint ?: route.points.lastIndex
                for (i in leg.firstPoint until last) {
                    val dz = route.elevations[i + 1] - route.elevations[i]
                    if (dz < 0) total -= dz
                }
            }
            return total
        }

        /**
         * The best candidates that fit the time and differ clearly from each other (relaxed when there are
         * too few); when none fits, the closest ones.
         */
        private fun select(candidates: List<Candidate>): List<Candidate> {
            val fitting = candidates.filter { misfit(it.route) <= MAX_SELECTED_MISFIT }
                .ifEmpty { candidates.sortedBy { misfit(it.route) }.take(request.maxRoutes) }
            val ranked = fitting.sortedByDescending { it.score }
            val chosen = mutableListOf<Candidate>()
            for (limit in listOf(MAX_OVERLAP, RELAXED_OVERLAP)) {
                for (candidate in ranked) {
                    if (chosen.size >= request.maxRoutes) return chosen
                    if (candidate !in chosen && chosen.all { RoutingEngine.overlap(it.route, candidate.route) <= limit }) {
                        chosen += candidate
                    }
                }
            }
            return chosen
        }

        // --- tours ----------------------------------------------------------------------------

        private fun geometricTours(budget: Double, count: Int): List<Tour> {
            if (count <= 0) return emptyList()
            val base = random.nextDouble(360.0)
            return (0 until count).mapNotNull { k ->
                val heading = base + k * 360.0 / count + random.nextDouble(-HEADING_JITTER, HEADING_JITTER)
                if (request.isLoop) loopTour(heading, if (k % 2 == 0) 2 else 3, budget)
                else ellipseTour(side = if (k % 2 == 0) 1 else -1, twoVias = k >= 2, budget)
            }
        }

        /** [n] virtual stops on a circle through the start whose centre lies towards [heading]. */
        private fun loopTour(heading: Double, n: Int, budget: Double): Tour? {
            val radius = budget / (2 * (n + 1) * sin(PI / (n + 1)))
            val side = if (random.nextBoolean()) 1 else -1
            val (cx, cy) = polar(heading, radius)
            val vias = (1..n).map { j ->
                val (dx, dy) = polar(heading + 180 + side * j * 360.0 / (n + 1), radius)
                Via(frame.point(cx + dx, cy + dy) ?: return null, virtual = true)
            }
            return Tour(from, to, vias)
        }

        /** One or two virtual stops on the ellipse around start and end whose detour fits the budget. */
        private fun ellipseTour(side: Int, twoVias: Boolean, budget: Double): Tour? {
            val e = Ellipse(budget) ?: return null
            val angles = if (twoVias) {
                val spread = random.nextDouble(30.0, 55.0)
                listOf(90 + spread, 90 - spread)
            } else {
                listOf(90 + random.nextDouble(-25.0, 25.0))
            }
            return Tour(from, to, angles.map { Via(e.at(it, side) ?: return null, virtual = true) })
        }

        /** Ellipse with the start and end as foci on which every point is [budget] metres from both. */
        private inner class Ellipse(budget: Double) {
            private val ex = frame.x(to)
            private val ey = frame.y(to)
            private val c = sqrt(ex * ex + ey * ey) / 2
            private val a = max(budget / 2, c * 1.05)
            private val b = sqrt(a * a - c * c)

            fun at(angleDeg: Double, side: Int): GeoPoint? {
                if (c <= 0) return null
                val ux = ex / (2 * c)
                val uy = ey / (2 * c)
                val t = Math.toRadians(angleDeg)
                // From the start (x = -c) towards the end (x = +c): angles above 90° come first.
                val x = a * cos(t)
                val y = side * b * sin(t)
                return frame.point(ex / 2 + x * ux - y * uy, ey / 2 + x * uy + y * ux)
            }
        }

        /**
         * The tour stretched or shrunk by about [factor]: virtual stops move (loops around the start, A→B
         * detours around the straight line), and on loops each place is swapped for one of the same kind
         * about [factor] times as far in the same direction (along the river, the next spot). Null when
         * nothing could change.
         */
        private fun resize(tour: Tour, factor: Double): Tour? {
            val f = factor.coerceIn(MIN_RESIZE, MAX_RESIZE)
            var changed = false
            val vias = tour.vias.map { via ->
                val moved = when {
                    via.virtual -> {
                        val vx = frame.x(via.point)
                        val vy = frame.y(via.point)
                        val (px, py) = if (request.isLoop) 0.0 to 0.0 else projectOnStraight(vx, vy)
                        frame.point(px + (vx - px) * f, py + (vy - py) * f)?.let { Via(it, virtual = true) }
                    }
                    request.isLoop -> alike(via, from.distanceTo(via.point) * f)
                    else -> null
                }
                if (moved != null) changed = true
                moved ?: via
            }
            return if (changed) Tour(tour.start, tour.end, vias, tour.turnBack) else null
        }

        /** A place of the same kind as [via] about [distance] from the start in the same direction, or null. */
        private fun alike(via: Via, distance: Double): Via? {
            val heading = bearing(from, via.point)
            val alike: List<Via> = when {
                via.poi != null -> pois.pois.filterIndexed { i, p -> p.kind == via.poi.kind && p.name != null && usable(poiEdge[i]) }
                    .map { Via(it.point, it) }
                via.point in riversideSpots -> riversideSpots.filterValues(::usable).keys.map { Via(it) }
                else -> greenSpots.filterValues(::usable).keys.map { Via(it) }
            }
            return alike
                .filter { angleBetween(bearing(from, it.point), heading) <= REANCHOR_MAX_TURN_DEG }
                .minByOrNull { abs(from.distanceTo(it.point) - distance) }
                ?.takeIf { abs(from.distanceTo(it.point) - distance) <= distance * MAX_FIT_ERROR && it.point != via.point }
        }

        /**
         * A place reached from [edge] can be a stop: it is connected to the start and allowed by the
         * preferences (with GIRA, inside Lisbon).
         */
        private fun usable(edge: Int): Boolean = edge != NO_EDGE && edgeComponent[edge] == component &&
            (weighting.canTraverse(graph.key(edge, false)) || weighting.canTraverse(graph.key(edge, true)))

        private fun angleBetween(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

        private fun projectOnStraight(x: Double, y: Double): Pair<Double, Double> {
            val ex = frame.x(to)
            val ey = frame.y(to)
            val length2 = ex * ex + ey * ey
            if (length2 <= 0) return 0.0 to 0.0
            val t = ((x * ex + y * ey) / length2).coerceIn(0.0, 1.0)
            return t * ex to t * ey
        }

        /**
         * Tours through 1 to 3 anchors: every single anchor (a loop adds a virtual stop to close a triangle),
         * then pairs and triples of the most valuable ones, scored by worth minus how badly they fit the budget.
         * Each anchor goes into at most two tours, so the choice stays varied.
         */
        private fun anchoredTours(budget: Double, count: Int): List<Tour> {
            val anchors = anchors(budget)
            if (anchors.isEmpty() || count <= 0) return emptyList()
            val combos = mutableListOf<Pair<Tour, Double>>()
            anchors.forEach { a -> singleTours(a, budget).forEach { combos += it to a.value - ANCHOR_FIT_PENALTY * fitError(it, budget) } }
            val pool = anchors.take(PAIR_POOL)
            for (i in pool.indices) for (j in i + 1 until pool.size) {
                comboTour(listOf(pool[i], pool[j]), budget)?.let { (tour, value) -> combos += tour to value }
            }
            val triples = anchors.take(TRIPLE_POOL)
            for (i in triples.indices) for (j in i + 1 until triples.size) for (k in j + 1 until triples.size) {
                comboTour(listOf(triples[i], triples[j], triples[k]), budget)?.let { (tour, value) -> combos += tour to value }
            }

            val uses = HashMap<GeoPoint, Int>()
            val chosen = mutableListOf<Tour>()
            for ((tour, _) in combos.sortedByDescending { it.second }) {
                if (chosen.size >= count) break
                val points = tour.vias.filterNot { it.virtual }.map { it.point }
                if (points.any { (uses[it] ?: 0) >= MAX_ANCHOR_USES }) continue
                if (chosen.any { other -> other.vias.count { !it.virtual && it.point in points } >= 2 }) continue
                points.forEach { uses[it] = (uses[it] ?: 0) + 1 }
                chosen += tour
            }
            return chosen
        }

        private fun fitError(tour: Tour, budget: Double) = abs(tour.straightM / budget - 1)

        /** Points of interest and scenic spots within reach, most valuable first (with some randomness). */
        private fun anchors(budget: Double): List<Anchor> {
            fun reachable(p: GeoPoint): Boolean = if (request.isLoop) {
                from.distanceTo(p) in budget * MIN_LOOP_REACH..budget * if (profile.outAndBack) MAX_OUT_AND_BACK_REACH else MAX_LOOP_REACH
            } else {
                from.distanceTo(p) + p.distanceTo(to) <= budget * MAX_PASS_REACH
            }
            fun jitter() = random.nextDouble(1 - VALUE_JITTER, 1 + VALUE_JITTER)
            val result = mutableListOf<Anchor>()
            pois.pois.forEachIndexed { i, poi ->
                val worth = profile.anchorKinds[poi.kind] ?: return@forEachIndexed
                if (poi.name != null && usable(poiEdge[i]) && reachable(poi.point)) {
                    result += Anchor(poi.point, poi, worth * (1 + poi.importance / 100.0) * jitter())
                }
            }
            listOf(riversideSpots to profile.riversideSpot, greenSpots to profile.greenSpot).forEach { (spots, worth) ->
                if (worth <= 0) return@forEach
                spots.filter { (p, edge) -> usable(edge) && reachable(p) }.keys.shuffled(random).take(MAX_SPOTS)
                    .forEach { result += Anchor(it, null, worth * jitter()) }
            }
            return result.sortedByDescending { it.value }.take(MAX_ANCHORS)
        }

        /**
         * A loop to one anchor closed by a virtual stop to its side, plus (where the purpose allows) there and
         * back the same way; A→B simply through it.
         */
        private fun singleTours(anchor: Anchor, budget: Double): List<Tour> {
            val via = Via(anchor.point, anchor.poi)
            if (!request.isLoop) {
                val tour = Tour(from, to, listOf(via), profile.outAndBack)
                return listOfNotNull(tour.takeIf { fitError(it, budget) <= MAX_FIT_ERROR })
            }
            val outAndBack = Tour(from, to, listOf(via), turnBack = true).takeIf { profile.outAndBack && fitError(it, budget) <= MAX_FIT_ERROR }
            return listOfNotNull(outAndBack, triangleTour(anchor, via, budget))
        }

        private fun triangleTour(anchor: Anchor, via: Via, budget: Double): Tour? {
            val d = from.distanceTo(anchor.point)
            if (2 * d > budget * (1 + MAX_FIT_ERROR)) return null
            val bearing = bearing(from, anchor.point) + (if (random.nextBoolean()) 1 else -1) * random.nextDouble(45.0, 75.0)
            fun closing(t: Double): GeoPoint? = polar(bearing, t).let { (x, y) -> frame.point(x, y) }
            fun perimeter(t: Double): Double = closing(t)?.let { d + anchor.point.distanceTo(it) + t } ?: Double.MAX_VALUE
            var low = 0.0
            var high = budget / 2
            repeat(BISECTION_STEPS) {
                val mid = (low + high) / 2
                if (perimeter(mid) < budget) low = mid else high = mid
            }
            val closer = Via(closing(low) ?: return null, virtual = true)
            return Tour(from, to, if (random.nextBoolean()) listOf(via, closer) else listOf(closer, via))
        }

        /** A tour through [anchors] in a sensible order, with its value; null when it does not fit the budget. */
        private fun comboTour(anchors: List<Anchor>, budget: Double): Pair<Tour, Double>? {
            for (i in anchors.indices) for (j in i + 1 until anchors.size) {
                if (anchors[i].point.distanceTo(anchors[j].point) < MIN_ANCHOR_GAP_M) return null
            }
            val ordered = if (request.isLoop) aroundStart(anchors).let { if (random.nextBoolean()) it.reversed() else it }
            else anchors.sortedBy { projectOnStraight(frame.x(it.point), frame.y(it.point)).let { (x, y) -> x * x + y * y } }
            val tour = Tour(from, to, ordered.map { Via(it.point, it.poi) }, profile.outAndBack)
            val error = fitError(tour, budget)
            if (error > MAX_FIT_ERROR) return null
            return tour to ordered.sumOf { it.value } - ANCHOR_FIT_PENALTY * error
        }

        /** Anchors sorted by bearing from the start, beginning after the widest empty angle (a simple loop). */
        private fun aroundStart(anchors: List<Anchor>): List<Anchor> {
            val sorted = anchors.sortedBy { bearing(from, it.point) }
            val bearings = sorted.map { bearing(from, it.point) }
            var widest = 0
            var widestGap = -1.0
            for (i in sorted.indices) {
                val next = if (i == sorted.lastIndex) bearings[0] + 360 else bearings[i + 1]
                if (next - bearings[i] > widestGap) {
                    widestGap = next - bearings[i]
                    widest = i
                }
            }
            return sorted.drop(widest + 1) + sorted.take(widest + 1)
        }

        private fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val local = LocalFrame(a)
            return (Math.toDegrees(atan2(local.x(b), local.y(b))) + 360) % 360
        }
    }

    companion object {
        /** A* heuristic weight for discovery searches: much faster, routes barely longer (see `discover --amostra`). */
        const val DEFAULT_HEURISTIC_WEIGHT = 1.5

        /** Network distance over straight-line distance between stops, before measuring. */
        const val DETOUR_FACTOR = 1.3

        /** Average speed over flat cruising speed (junctions, lights, hills), before measuring. */
        const val CRUISE_SHARE = 0.85

        /** A→B: when the direct route already takes this share of the time, there is no room to explore. */
        const val DIRECT_ONLY_SHARE = 0.85

        private const val SEED_STRIDE = 1_000_003L
        private const val MIN_CALIBRATION_M = 800.0
        private const val CALIBRATION_TOURS = 4
        private const val CANDIDATE_TOURS = 10
        private const val ANCHORED_TOURS = 8
        private const val EXTRA_TOURS = 3
        private const val MAX_RESIZED = 5
        private const val MIN_RESIZE = 0.5
        private const val MAX_RESIZE = 1.8
        private const val REANCHOR_MAX_TURN_DEG = 35.0
        private const val HEADING_JITTER = 15.0

        /** A route within this share of the time asked for is a good fit. */
        const val FIT_TOLERANCE = 0.12

        /** Routes further than this from the time asked for are only shown when nothing fits better. */
        const val MAX_SELECTED_MISFIT = 0.25
        private const val FIT_WEIGHT = 3.0
        private const val FIT_SCALE_OVER = 0.12
        private const val FIT_SCALE_UNDER = 0.18
        private const val MAX_OVERLAP = 0.5
        private const val RELAXED_OVERLAP = 0.7

        private const val MAX_ANCHORS = 24
        private const val PAIR_POOL = 20
        private const val TRIPLE_POOL = 10
        private const val MAX_SPOTS = 30
        private const val MAX_ANCHOR_USES = 2
        private const val MIN_ANCHOR_GAP_M = 300.0
        private const val ANCHOR_FIT_PENALTY = 2.0
        private const val MAX_FIT_ERROR = 0.25
        private const val MIN_LOOP_REACH = 0.08
        private const val MAX_LOOP_REACH = 0.47
        private const val MAX_OUT_AND_BACK_REACH = 0.62
        private const val MAX_PASS_REACH = 1.05
        private const val VALUE_JITTER = 0.3
        private const val BISECTION_STEPS = 30

        /** Shares of its distance from the start or the straight line a virtual stop off the network is tried at. */
        private val PULL_IN_SHARES = doubleArrayOf(0.8, 0.6, 0.4)

        private const val VIA_SNAP_NEAR_M = 150.0
        private const val VIA_SNAP_M = 300.0
        private const val MIN_ROUTE_M = 500.0
        private const val SPOT_CELL_M = 250.0
        private const val MIN_SPOT_EDGE_M = 20.0
        private const val NO_EDGE = -1

        private const val POI_SAMPLE_M = 25.0
        private const val POI_NEAR_M = 40.0
        private const val OTHER_POI_WORTH = 0.3
        private const val MAX_POI_VALUE = 3.0
        private const val MAX_KIND_VALUE = 3.0

        /** Places passed (not visited) count only when well known: a statue is not a monument worth the ride. */
        private val MIN_PASSED_IMPORTANCE = mapOf(PoiKind.MONUMENT to 40, PoiKind.ATTRACTION to 40, PoiKind.PARK to 35)

        private const val BUSY_WEIGHT = 2.0
        private const val WALK_WEIGHT = 1.5
        // Steep cobbled descents cost something, but some places (Alfama's viewpoints) cannot be reached
        // without one; the route then says "better on foot" there.
        private const val ROUGH_DESCENT_WEIGHT = 0.1
        private const val ROUGH_DESCENT_OK_PERCENT = 10.0
        private const val ROUGH_DESCENT_MAX_PERCENT = 10.0
        private const val AVOID_WEIGHT = 2.0
        private const val AVOID_FROM_OVERLAP = 0.4

        private fun polar(bearingDeg: Double, distance: Double): Pair<Double, Double> {
            val b = Math.toRadians(bearingDeg)
            return distance * sin(b) to distance * cos(b)
        }
    }
}

/** Planar metres east ([x]) and north ([y]) of [origin]; accurate to <0.1% at city scale. */
internal class LocalFrame(private val origin: GeoPoint) {
    private val cosLat = cos(Math.toRadians(origin.lat))

    fun x(p: GeoPoint): Double = (p.lon - origin.lon) * METRES_PER_DEGREE * cosLat

    fun y(p: GeoPoint): Double = (p.lat - origin.lat) * METRES_PER_DEGREE

    /** The point at ([x], [y]), or null outside valid coordinates. */
    fun point(x: Double, y: Double): GeoPoint? {
        val lat = origin.lat + y / METRES_PER_DEGREE
        val lon = origin.lon + x / (METRES_PER_DEGREE * cosLat)
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
    }

    private companion object {
        const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M
    }
}
