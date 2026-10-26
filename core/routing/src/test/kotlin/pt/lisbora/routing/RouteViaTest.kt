package pt.lisbora.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.weighting.RouteBias
import pt.lisbora.routing.weighting.RoutingPreferences

class RouteViaTest {

    private val defaults = RoutingPreferences()

    /**
     * Two parallel streets 1 km long, 100 m apart, joined at both ends:
     * south (y = 0) from [sw] to [se] and north (y = 100) from [nw] to [ne].
     */
    private inner class Ladder {
        val g = TestGraphBuilder()
        val sw = g.node(0.0, 0.0)
        val se = g.node(1000.0, 0.0)
        val nw = g.node(0.0, 100.0)
        val ne = g.node(1000.0, 100.0)
        val south = g.edge(sw, se, name = "Sul")
        val north = g.edge(nw, ne, name = "Norte")
        val west = g.edge(sw, nw, name = "Oeste")
        val east = g.edge(se, ne, name = "Este")
        val engine = RoutingEngine(g.build())

        fun snap(x: Double, y: Double) = engine.snap(g.at(x, y), defaults)!!
    }

    private fun RouteResult.route(): Route = (this as RouteResult.Success).route

    @Test
    fun `passes through every stop in order`() {
        val l = Ladder()
        val route = l.engine.routeVia(listOf(l.snap(300.0, 0.0), l.snap(900.0, 100.0), l.snap(100.0, 100.0)), defaults).route()
        // South eastwards, up the east side, then north westwards.
        assertEquals(listOf(l.south, l.east, l.north, l.north), route.legs.map { it.edge })
        assertEquals(700.0 + 100 + 100 + 800, route.distanceM, 1.0)
        assertEquals(route.legs.sumOf { it.lengthM }, route.distanceM, 1e-9)
        assertEquals(route.distances.last(), route.distanceM, 1.0)
    }

    @Test
    fun `goes on past a stop instead of turning back, unless asked to`() {
        val l = Ladder()
        val stops = listOf(l.snap(100.0, 0.0), l.snap(500.0, 0.0), l.snap(100.0, 0.0))
        val around = l.engine.routeVia(stops, defaults).route()
        // On to the east end, round the block and back along the north street.
        assertTrue(l.north in around.legs.map { it.edge })
        assertEquals(2200.0, around.distanceM, 1.0)

        val back = l.engine.routeVia(stops, defaults, passThrough = false).route()
        assertFalse(l.north in back.legs.map { it.edge })
        assertEquals(800.0, back.distanceM, 1.0)
    }

    @Test
    fun `streets already taken cost more, so the way back is different`() {
        val l = Ladder()
        val stops = listOf(l.snap(0.0, 0.0), l.snap(1000.0, 0.0), l.snap(0.0, 0.0))
        val same = l.engine.routeVia(stops, defaults, passThrough = false).route()
        assertEquals(listOf(l.south, l.south), same.legs.map { it.edge })

        val loop = l.engine.routeVia(stops, defaults, reusePenalty = 2.5f, passThrough = false).route()
        assertEquals(listOf(l.south, l.east, l.north, l.west), loop.legs.map { it.edge })
        assertEquals(2200.0, loop.distanceM, 1.0)
    }

    @Test
    fun `route bias favours riverside streets`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(1000.0, 0.0)
        val c = g.node(500.0, -300.0)
        val street = g.edge(a, b)
        val riverside = listOf(g.edge(a, c, riverside = true), g.edge(c, b, riverside = true))
        val engine = RoutingEngine(g.build())

        val plain = engine.route(g.at(0.0, 0.0), g.at(1000.0, 0.0), defaults).route()
        assertEquals(listOf(street), plain.legs.map { it.edge })
        val river = RoutingPreferences(bias = RouteBias(riverside = 0.5))
        val scenic = engine.route(g.at(0.0, 0.0), g.at(1000.0, 0.0), river).route()
        assertEquals(riverside, scenic.legs.map { it.edge })
    }

    @Test
    fun `GIRA routes never leave Lisbon, unless the graph does not know where Lisbon ends`() {
        fun graph(flagged: Boolean): Pair<TestGraphBuilder, List<Int>> {
            val g = TestGraphBuilder()
            val a = g.node(0.0, 0.0)
            val b = g.node(1000.0, 0.0)
            val c = g.node(500.0, 400.0)
            // The short way crosses into the next municipality; the long one stays in Lisbon.
            g.edge(a, b, lisbon = false)
            val inside = listOf(g.edge(a, c, lisbon = flagged), g.edge(c, b, lisbon = flagged))
            return g to inside
        }
        val gira = RoutingPreferences(stayInLisbon = true)
        val (g, inside) = graph(flagged = true)
        val engine = RoutingEngine(g.build())
        assertEquals(inside, engine.route(g.at(0.0, 0.0), g.at(1000.0, 0.0), gira).route().legs.map { it.edge })
        assertEquals(1, engine.route(g.at(0.0, 0.0), g.at(1000.0, 0.0), defaults).route().legs.size)
        // A start nearer the outside street snaps to the nearest street inside.
        assertTrue(engine.snap(g.at(60.0, 20.0), gira)!!.edge in inside)
        assertTrue(engine.snap(g.at(60.0, 20.0), defaults)!!.edge !in inside)

        val (old, _) = graph(flagged = false)
        assertEquals(1, RoutingEngine(old.build()).route(old.at(0.0, 0.0), old.at(1000.0, 0.0), gira).route().legs.size)
    }

    @Test
    fun `climb bias seeks or avoids hills`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(1000.0, 0.0)
        val top = g.node(500.0, 200.0, ele = 40.0)
        val flat = g.edge(a, b)
        val hill = listOf(g.edge(a, top), g.edge(top, b))
        val engine = RoutingEngine(g.build())

        assertEquals(listOf(flat), engine.route(g.at(0.0, 0.0), g.at(1000.0, 0.0), defaults).route().legs.map { it.edge })
        val climber = RoutingPreferences(bias = RouteBias(climb = 0.3))
        assertEquals(hill, engine.route(g.at(0.0, 0.0), g.at(1000.0, 0.0), climber).route().legs.map { it.edge })
    }
}
