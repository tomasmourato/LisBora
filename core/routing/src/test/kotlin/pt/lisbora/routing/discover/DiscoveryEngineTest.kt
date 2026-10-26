package pt.lisbora.routing.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.TestGraphBuilder
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.weighting.RoutingPreferences
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

class DiscoveryEngineTest {

    /**
     * A flat 3 × 3 km grid of streets every 200 m whose southern street runs along the river, three
     * museums, and an island east of the grid reached only by ferry, with a famous museum on it.
     * With [lisbonUpToX], only the streets west of that line are in Lisbon.
     */
    private class City(lisbonUpToX: Double? = null) {
        val g = TestGraphBuilder()
        private val n = 16
        private val spacing = 200.0
        private val nodes = Array(n) { row -> IntArray(n) { col -> g.node(col * spacing, row * spacing) } }

        init {
            fun inLisbon(vararg cols: Int) = lisbonUpToX != null && cols.all { it * spacing <= lisbonUpToX }
            for (row in 0 until n) for (col in 0 until n) {
                if (col + 1 < n) g.edge(nodes[row][col], nodes[row][col + 1], riverside = row == 0, lisbon = inLisbon(col, col + 1))
                if (row + 1 < n) g.edge(nodes[row][col], nodes[row + 1][col], lisbon = inLisbon(col))
            }
            val pier = g.node(3400.0, 1400.0)
            val islandEnd = g.node(3600.0, 1400.0)
            g.edge(nodes[7][15], pier, road = RoadKind.FERRY)
            g.edge(pier, islandEnd)
        }

        val museums = listOf(
            Poi(PoiKind.MUSEUM, "Museu Norte", g.at(600.0, 2400.0), importance = 60),
            Poi(PoiKind.MUSEUM, "Museu Este", g.at(2400.0, 2400.0), importance = 60),
            Poi(PoiKind.MUSEUM, "Museu Sul", g.at(2400.0, 600.0), importance = 60),
        )
        val islandMuseum = Poi(PoiKind.MUSEUM, "Museu da Ilha", g.at(3500.0, 1400.0), importance = 100)
        val engine = DiscoveryEngine(RoutingEngine(g.build()), museums + islandMuseum)
        /** On a street (between two junctions), so routes start and end right there. */
        val centre = g.at(1500.0, 1400.0)
    }

    private val city = City()

    private fun DiscoveryResult.routes(): List<DiscoveredRoute> {
        check(this is DiscoveryResult.Success) { "expected routes, got $this" }
        return routes
    }

    private fun loop(minutes: Int, purpose: DiscoveryPurpose = DiscoveryPurpose.LEISURE, seed: Long = 0, start: pt.lisbora.routing.geo.GeoPoint = city.centre) =
        DiscoveryRequest(start = start, durationS = minutes * 60.0, purpose = purpose, seed = seed)

    @Test
    fun `a loop fits the time asked for and comes back to the start`() {
        val routes = city.engine.discover(loop(20)).routes()
        assertTrue(routes.size in 2..3, "routes: ${routes.size}")
        routes.forEach { r ->
            assertTrue(r.route.points.first().distanceTo(city.centre) < 50)
            assertTrue(r.route.points.last().distanceTo(city.centre) < 50)
            assertTrue(abs(r.route.durationS / 1200 - 1) <= DiscoveryEngine.MAX_SELECTED_MISFIT, "duration ${r.route.durationS}")
        }
        assertTrue(abs(routes.first().route.durationS / 1200 - 1) <= DiscoveryEngine.FIT_TOLERANCE)
        for (i in routes.indices) for (j in i + 1 until routes.size) {
            assertTrue(RoutingEngine.overlap(routes[i].route, routes[j].route) <= 0.7)
        }
    }

    @Test
    fun `the same request gives the same routes, in parallel too`() {
        val first = city.engine.discover(loop(25, seed = 7)).routes().map { it.stops }
        val pool = Executors.newFixedThreadPool(3)
        try {
            val runner = object : TaskRunner {
                override fun <T> runAll(tasks: List<() -> T>): List<T> = pool.invokeAll(tasks.map { Callable(it) }).map { it.get() }
            }
            assertEquals(first, city.engine.discover(loop(25, seed = 7), runner).routes().map { it.stops })
        } finally {
            pool.shutdown()
        }
        val others = (8L..12L).map { seed -> city.engine.discover(loop(25, seed = seed)).routes().map { it.stops } }
        assertTrue(others.any { it != first }, "another seed should give other routes")
    }

    @Test
    fun `the river purpose rides along the river`() {
        val start = city.g.at(1500.0, 100.0)
        val best = city.engine.discover(loop(20, DiscoveryPurpose.RIVER, start = start)).routes().first()
        assertTrue(best.highlights.riversideM / best.route.distanceM >= 0.4, "riverside ${best.highlights.riversideM} of ${best.route.distanceM}")
    }

    @Test
    fun `culture goes through museums, never one only the ferry reaches`() {
        val routes = city.engine.discover(loop(30, DiscoveryPurpose.CULTURE, start = city.g.at(2800.0, 1400.0))).routes()
        assertTrue(routes.first().highlights.count(PoiKind.MUSEUM) >= 1)
        routes.forEach { r ->
            assertFalse(city.islandMuseum in r.anchors || city.islandMuseum in r.highlights.pois)
            assertFalse(r.route.legs.any { it.flags.isFerry })
        }
    }

    @Test
    fun `A to B without time to spare is just the direct route`() {
        val request = DiscoveryRequest(
            start = city.g.at(0.0, 0.0), end = city.g.at(3000.0, 3000.0), durationS = 19 * 60.0, purpose = DiscoveryPurpose.LEISURE,
        )
        val result = city.engine.discover(request) as DiscoveryResult.Success
        assertTrue(result.directOnly)
        assertEquals(1, result.routes.size)
    }

    @Test
    fun `A to B with time to spare takes a longer way there`() {
        // Across the middle of the city, so a detour has room on both sides (one of them by the river).
        // The direct route is 1.8 km straight along one street.
        val from = city.g.at(600.0, 1400.0)
        val to = city.g.at(2400.0, 1400.0)
        val request = DiscoveryRequest(start = from, end = to, durationS = 20 * 60.0, purpose = DiscoveryPurpose.LEISURE)
        val result = city.engine.discover(request).also { check(it is DiscoveryResult.Success) { "expected routes, got $it" } } as DiscoveryResult.Success
        assertFalse(result.directOnly)
        val best = result.routes.first()
        assertTrue(abs(best.route.durationS / request.durationS - 1) <= DiscoveryEngine.MAX_SELECTED_MISFIT, "duration ${best.route.durationS}")
        assertTrue(best.route.points.last().distanceTo(to) < 50)
        assertTrue(best.route.distanceM > 2 * 1800, "distance ${best.route.distanceM}")
    }

    @Test
    fun `a virtual stop off the network moves in until it reaches a street`() {
        // From a corner to the opposite one: every detour sticks out of the city, so its stops are pulled in.
        val request = DiscoveryRequest(
            start = city.g.at(0.0, 0.0), end = city.g.at(3000.0, 3000.0), durationS = 35 * 60.0, purpose = DiscoveryPurpose.LEISURE,
        )
        val routes = city.engine.discover(request).routes()
        routes.forEach { r ->
            assertTrue(r.route.points.last().distanceTo(city.g.at(3000.0, 3000.0)) < 50)
            // The stops kept are the ones used: the route is computed again the same from them.
            val again = city.engine.rebuild(r.stops, DiscoveryPurpose.LEISURE, emptySet(), RoutingPreferences(), r.turnsBack)!!
            assertEquals(r.route.distanceM, again.route.distanceM, 1.0)
        }
    }

    @Test
    fun `GIRA rides stay in Lisbon and only visit places there`() {
        // Lisbon ends at x = 2000 m: the eastern museums lie in the next municipality.
        val city = City(lisbonUpToX = 2000.0)
        val request = DiscoveryRequest(
            start = city.g.at(1000.0, 1400.0), durationS = 30 * 60.0, purpose = DiscoveryPurpose.CULTURE,
            preferences = RoutingPreferences(stayInLisbon = true),
        )
        val routes = city.engine.discover(request).routes()
        assertTrue(routes.isNotEmpty())
        routes.forEach { r ->
            assertTrue(r.route.legs.all { it.flags.lisbon }, "a leg leaves Lisbon")
            assertTrue(r.anchors.none { it.name == "Museu Este" || it.name == "Museu Sul" })
        }
        // Without GIRA the same ride may go east.
        val free = city.engine.discover(request.copy(preferences = RoutingPreferences())).routes()
        assertTrue(free.any { r -> r.route.legs.any { !it.flags.lisbon } })
    }

    @Test
    fun `a saved route is computed again the same from its stops`() {
        val best = city.engine.discover(loop(25, DiscoveryPurpose.CULTURE)).routes().first()
        val again = city.engine.rebuild(best.stops, DiscoveryPurpose.CULTURE, emptySet(), RoutingPreferences(), best.turnsBack)!!
        assertEquals(best.route.distanceM, again.route.distanceM, 1.0)
        assertEquals(best.anchors, again.anchors)
    }

    @Test
    fun `a start away from every street is refused`() {
        val result = city.engine.discover(loop(20, start = city.g.at(9000.0, 9000.0)))
        assertEquals(DiscoveryResult.Failure(DiscoveryResult.Reason.START_NOT_ON_NETWORK), result)
    }
}
