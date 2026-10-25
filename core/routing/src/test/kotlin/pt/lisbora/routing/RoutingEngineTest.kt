package pt.lisbora.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.Snap
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.weighting.RoutingPreferences
import kotlin.random.Random

class RoutingEngineTest {

    private val defaults = RoutingPreferences()

    private fun RoutingEngine.success(from: GeoPoint, to: GeoPoint, preferences: RoutingPreferences = defaults): Route {
        val result = route(from, to, preferences)
        check(result is RouteResult.Success) { "expected a route, got $result" }
        return result.route
    }

    private fun Route.edges(): List<Int> = legs.map { it.edge }

    @Test
    fun `follows a simple street and reports distance and time`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(500.0, 0.0)
        val c = g.node(1000.0, 0.0)
        val ab = g.edge(a, b, name = "Rua A")
        val bc = g.edge(b, c, name = "Rua B")
        val engine = RoutingEngine(g.build())

        val route = engine.success(g.at(100.0, 0.0), g.at(900.0, 0.0))
        assertEquals(listOf(ab, bc), route.edges())
        assertEquals(800.0, route.distanceM, 1.0)
        assertEquals(route.legs.sumOf { it.lengthM }, route.distanceM, 1e-9)
        // About 20 km/h on the flat.
        assertTrue(route.durationS in 120.0..180.0, "duration ${route.durationS}")
        assertEquals(100.0, route.points.first().distanceTo(g.at(0.0, 0.0)), 1.0)
        assertEquals(100.0, route.points.last().distanceTo(g.at(1000.0, 0.0)), 1.0)
    }

    @Test
    fun `respects one-way streets`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(500.0, 0.0)
        val c = g.node(500.0, 300.0)
        val d = g.node(0.0, 300.0)
        val oneway = g.edge(a, b, backward = TravelMode.NONE)
        val around = listOf(g.edge(b, c), g.edge(c, d), g.edge(d, a))
        val engine = RoutingEngine(g.build())

        val forward = engine.success(g.at(50.0, 0.0), g.at(450.0, 0.0))
        assertEquals(listOf(oneway), forward.edges())
        val backward = engine.success(g.at(450.0, 0.0), g.at(50.0, 0.0))
        assertEquals(listOf(oneway) + around + listOf(oneway), backward.edges())
    }

    @Test
    fun `pushes against a one-way street only when it pays off`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(100.0, 0.0)
        val c = g.node(100.0, 2000.0)
        val d = g.node(0.0, 2000.0)
        val oneway = g.edge(a, b, backward = TravelMode.PUSH)
        g.edge(b, c); g.edge(c, d); g.edge(d, a)
        val engine = RoutingEngine(g.build())

        val route = engine.success(g.at(95.0, 0.0), g.at(5.0, 0.0))
        assertEquals(listOf(oneway), route.edges())
        assertEquals(TravelMode.PUSH, route.legs.single().mode)
        assertEquals(90.0, route.pushedM, 1.0)
    }

    @Test
    fun `never uses steps`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(100.0, 0.0)
        val c = g.node(50.0, 400.0)
        val steps = g.edge(a, b, forward = TravelMode.PUSH, backward = TravelMode.PUSH, road = RoadKind.STEPS)
        g.edge(a, c); g.edge(c, b)
        val engine = RoutingEngine(g.build())

        // Start and end on the long way round, so snapping does not land on the steps.
        val route = engine.success(g.at(10.0, 80.0), g.at(90.0, 80.0))
        assertFalse(steps in route.edges())
    }

    @Test
    fun `obeys turn restrictions`() {
        val g = TestGraphBuilder()
        val south = g.node(0.0, -300.0)
        val center = g.node(0.0, 0.0)
        val west = g.node(-300.0, 0.0)
        val north = g.node(0.0, 300.0)
        val northWest = g.node(-300.0, 300.0)
        val inbound = g.edge(south, center)
        val left = g.edge(center, west)
        val ahead = g.edge(center, north)
        val detour = g.edge(north, northWest)
        val back = g.edge(northWest, west)
        g.restrict(inbound, center, left)
        val engine = RoutingEngine(g.build())

        val route = engine.success(g.at(0.0, -250.0), g.at(-250.0, 0.0))
        assertEquals(listOf(inbound, ahead, detour, back, left), route.edges())
    }

    @Test
    fun `turns around only at dead ends`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(200.0, 0.0)
        val deadEnd = g.node(200.0, 200.0)
        val ab = g.edge(a, b, backward = TravelMode.NONE)
        val spur = g.edge(b, deadEnd)
        val engine = RoutingEngine(g.build())

        // From the one-way street there is no way back except via the dead-end spur and a U-turn there.
        val route = engine.success(g.at(100.0, 0.0), g.at(200.0, 50.0))
        assertEquals(listOf(ab, spur), route.edges())
        val result = engine.route(g.at(200.0, 50.0), g.at(100.0, 0.0), defaults)
        assertEquals(RouteResult.Failure(RouteResult.Reason.NO_PATH), result)
    }

    @Test
    fun `prefers a segregated track over a busy road of similar length`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(1000.0, 0.0)
        val road = g.edge(a, b, road = RoadKind.PRIMARY, stress = 4)
        val track = g.edge(a, b, road = RoadKind.CYCLEWAY, infra = EdgeInfra.SEGREGATED_TRACK, pillars = listOf(Triple(500.0, 60.0, 0.0)))
        val engine = RoutingEngine(g.build())

        val route = engine.success(g.at(0.0, -20.0), g.at(1000.0, -20.0))
        assertEquals(listOf(track), route.edges())
        assertFalse(road in route.edges())
        assertEquals(1.0, route.infraShare, 1e-9)
    }

    @Test
    fun `maximum grade steers around steep climbs`() {
        val g = TestGraphBuilder()
        val bottom = g.node(0.0, 0.0, ele = 0.0)
        val top = g.node(300.0, 0.0, ele = 36.0) // 12%
        val bend = g.node(600.0, 400.0, ele = 18.0)
        val steep = g.edge(bottom, top, name = "Calçada")
        val gentle = listOf(g.edge(bottom, bend), g.edge(bend, top))
        val engine = RoutingEngine(g.build())

        val direct = engine.success(g.at(0.0, 0.0), g.at(300.0, 0.0))
        assertEquals(listOf(steep), direct.edges())
        assertEquals(12.0, direct.maxGradePercent, 0.5)
        assertEquals(36.0, direct.ascentM, 0.5)

        val limited = engine.success(g.at(0.0, 0.0), g.at(300.0, 0.0), RoutingPreferences(maxGradePercent = 8.0))
        assertEquals(gentle, limited.edges())
        assertTrue(limited.maxGradePercent < 8.0)
    }

    @Test
    fun `avoids cobbles and tram tracks when asked`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(1000.0, 0.0)
        val rough = g.edge(a, b, surface = SurfaceKind.COBBLESTONE, tram = true)
        val smooth = g.edge(a, b, pillars = listOf(Triple(500.0, 1500.0, 0.0)))
        val engine = RoutingEngine(g.build())

        assertEquals(listOf(rough), engine.success(g.at(0.0, -10.0), g.at(1000.0, -10.0)).edges())
        val careful = RoutingPreferences(avoidCobbles = true, avoidTramTracks = true)
        assertEquals(listOf(smooth), engine.success(g.at(0.0, -10.0), g.at(1000.0, -10.0), careful).edges())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `routes along a single edge in either direction`(backwards: Boolean) {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(1000.0, 0.0)
        val edge = g.edge(a, b)
        val engine = RoutingEngine(g.build())

        val (from, to) = if (backwards) 800.0 to 200.0 else 200.0 to 800.0
        val route = engine.success(g.at(from, 0.0), g.at(to, 0.0))
        assertEquals(listOf(edge), route.edges())
        assertEquals(backwards, route.legs.single().reverse)
        assertEquals(600.0, route.distanceM, 1.0)
    }

    @Test
    fun `starts on a node at the tail of a one-way edge`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(300.0, 0.0)
        val c = g.node(0.0, 300.0)
        g.edge(a, b, backward = TravelMode.NONE)
        val ac = g.edge(a, c)
        val graph = g.build()
        val engine = RoutingEngine(graph)

        // Snapped exactly onto node a, at the start of the one-way edge a→b.
        val start = Snap(edge = 0, along = 0.0, point = g.point(a), distance = 0.0)
        val end = engine.snap(g.at(0.0, 250.0))!!
        val result = engine.route(start, end, defaults)
        assertTrue(result is RouteResult.Success)
        assertEquals(listOf(ac), (result as RouteResult.Success).route.edges())
    }

    @Test
    fun `reports unreachable destinations and positions off the network`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(300.0, 0.0)
        val c = g.node(2000.0, 0.0)
        val d = g.node(2300.0, 0.0)
        g.edge(a, b)
        g.edge(c, d)
        val engine = RoutingEngine(g.build())

        assertEquals(RouteResult.Failure(RouteResult.Reason.NO_PATH), engine.route(g.at(100.0, 0.0), g.at(2100.0, 0.0), defaults))
        assertEquals(RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK), engine.route(g.at(1000.0, 1000.0), g.at(100.0, 0.0), defaults))
        assertEquals(RouteResult.Failure(RouteResult.Reason.END_NOT_ON_NETWORK), engine.route(g.at(100.0, 0.0), g.at(1000.0, 1000.0), defaults))
    }

    @Test
    fun `route geometry is continuous and ends at the snapped points`() {
        val g = TestGraphBuilder()
        val nodes = (0..5).map { g.node(it * 200.0, if (it % 2 == 0) 0.0 else 50.0, ele = it * 2.0) }
        nodes.zipWithNext { u, v -> g.edge(u, v, pillars = listOf(Triple((u + v) * 100.0, 25.0, u + v + 0.0))) }
        val engine = RoutingEngine(g.build())

        val route = engine.success(g.at(30.0, 5.0), g.at(950.0, 30.0))
        val length = route.points.zipWithNext { p, q -> p.distanceTo(q) }.sum()
        assertEquals(route.distanceM, length, route.distanceM * 0.01)
        assertEquals(route.points.size, route.elevations.size)
        route.legs.zipWithNext { first, second -> assertTrue(second.firstPoint >= first.firstPoint) }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])
    fun `A star finds the same cost as Dijkstra on random graphs`(seed: Int) {
        val random = Random(seed)
        val g = TestGraphBuilder()
        val size = 12
        val nodes = Array(size) { row -> IntArray(size) { col ->
            g.node(col * 120.0 + random.nextDouble(-30.0, 30.0), row * 120.0 + random.nextDouble(-30.0, 30.0), ele = random.nextDouble(0.0, 40.0))
        } }
        val modes = listOf(TravelMode.RIDE, TravelMode.RIDE, TravelMode.RIDE, TravelMode.PUSH, TravelMode.NONE)
        for (row in 0 until size) for (col in 0 until size) {
            listOf(row to col + 1, row + 1 to col).forEach { (r, c) ->
                if (r < size && c < size && random.nextDouble() < 0.85) {
                    g.edge(
                        nodes[row][col], nodes[r][c],
                        forward = modes.random(random),
                        backward = modes.random(random),
                        infra = EdgeInfra.entries.random(random),
                        surface = SurfaceKind.entries.random(random),
                        stress = random.nextInt(1, 5),
                        tram = random.nextDouble() < 0.1,
                        signals = random.nextDouble() < 0.1,
                    )
                }
            }
        }
        val graph = g.build()
        val engine = RoutingEngine(graph)
        val preferences = RoutingPreferences(maxGradePercent = 6.0)
        repeat(10) {
            val from = engine.snap(g.at(random.nextDouble(0.0, 1300.0), random.nextDouble(0.0, 1300.0)), preferences) ?: return@repeat
            val to = engine.snap(g.at(random.nextDouble(0.0, 1300.0), random.nextDouble(0.0, 1300.0)), preferences) ?: return@repeat
            val astar = engine.route(from, to, preferences)
            val dijkstra = engine.route(from, to, preferences, heuristicWeight = 0.0)
            assertEquals(dijkstra is RouteResult.Success, astar is RouteResult.Success)
            if (astar is RouteResult.Success && dijkstra is RouteResult.Success) {
                assertEquals(dijkstra.route.cost, astar.route.cost, 1e-6 * dijkstra.route.cost + 1e-6)
                assertTrue(astar.route.settledStates <= dijkstra.route.settledStates)
            }
        }
    }
}
