package pt.lisbora.routing.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.TestGraphBuilder
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.io.Gpx
import pt.lisbora.routing.io.TrackPoint
import pt.lisbora.routing.weighting.RoutingPreferences

class NavigationTest {

    private fun RouteResult.route(): Route = (this as RouteResult.Success).route

    private fun TestGraphBuilder.route(fromX: Double, fromY: Double, toX: Double, toY: Double): Route =
        RoutingEngine(build()).route(at(fromX, fromY), at(toX, toY), RoutingPreferences()).route()

    // --- instructions ---------------------------------------------------------------------------

    @Test
    fun `a left turn onto another street, then arriving`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(300.0, 0.0)
        val c = g.node(300.0, 300.0)
        g.edge(a, b, name = "Rua do Ouro")
        g.edge(b, c, name = "Rua da Conceição")
        val instructions = RouteInstructions.build(g.route(0.0, 0.0, 300.0, 300.0))

        assertEquals(listOf(Maneuver.DEPART, Maneuver.LEFT, Maneuver.ARRIVE), instructions.map { it.maneuver })
        assertEquals("Rua do Ouro", instructions[0].street)
        assertEquals("Rua da Conceição", instructions[1].street)
        assertEquals(300.0, instructions[1].atM, 1.0)
        assertEquals(300.0, instructions[0].untilNextM, 1.0)
    }

    @Test
    fun `entering and leaving a cycle track is said, a short gap in it is not`() {
        val g = TestGraphBuilder()
        val n = (0..6).map { g.node(it * 100.0, 0.0) }
        g.edge(n[0], n[1], name = "Avenida")
        g.edge(n[1], n[2], name = "Avenida", infra = EdgeInfra.SEGREGATED_TRACK)
        // 10 m without the track (the CML line misses a crossing), then the track again.
        val gap = g.node(210.0, 0.0)
        g.edge(n[2], gap, name = "Avenida")
        g.edge(gap, n[3], name = "Avenida", infra = EdgeInfra.SEGREGATED_TRACK)
        g.edge(n[3], n[4], name = "Avenida", infra = EdgeInfra.SEGREGATED_TRACK)
        g.edge(n[4], n[5], name = "Avenida")
        val instructions = RouteInstructions.build(g.route(0.0, 0.0, 500.0, 0.0))

        val changes = instructions.filter { it.cycleway != CyclewayChange.NONE }
        assertEquals(listOf(CyclewayChange.ENTER to 100.0, CyclewayChange.LEAVE to 400.0), changes.map { it.cycleway to Math.round(it.atM).toDouble() })
        assertTrue(changes.all { it.maneuver == Maneuver.CONTINUE })
    }

    @Test
    fun `getting off to walk and back on, and a new street name straight ahead`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(200.0, 0.0)
        val c = g.node(260.0, 0.0)
        val d = g.node(500.0, 0.0)
        g.edge(a, b, name = "Rua Augusta")
        g.edge(b, c, name = "Praça do Comércio", forward = TravelMode.PUSH, backward = TravelMode.PUSH)
        g.edge(c, d, name = "Avenida Ribeira das Naus")
        val instructions = RouteInstructions.build(g.route(0.0, 0.0, 500.0, 0.0))

        assertEquals(WalkChange.START, instructions.single { it.atM in 199.0..201.0 }.walk)
        val back = instructions.single { it.atM in 259.0..261.0 }
        assertEquals(WalkChange.END, back.walk)
        assertEquals("Avenida Ribeira das Naus", back.street)
    }

    @Test
    fun `a roundabout names the street it is left by`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val r1 = g.node(100.0, 0.0)
        val r2 = g.node(120.0, 20.0)
        val r3 = g.node(140.0, 0.0)
        val e = g.node(300.0, 0.0)
        g.edge(a, r1, name = "Avenida da República")
        g.edge(r1, r2, name = "Rotunda", roundabout = true)
        g.edge(r2, r3, name = "Rotunda", roundabout = true)
        g.edge(r3, e, name = "Avenida Fontes Pereira de Melo")
        val instructions = RouteInstructions.build(g.route(0.0, 0.0, 300.0, 0.0))

        val roundabout = instructions.single { it.maneuver == Maneuver.ROUNDABOUT }
        assertEquals("Avenida Fontes Pereira de Melo", roundabout.street)
        assertEquals(3, instructions.size)
    }

    @Test
    fun `setting off just before a corner is part of the departure`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(300.0, 0.0)
        val c = g.node(300.0, 300.0)
        g.edge(a, b, name = "Rua do Ouro")
        g.edge(b, c, name = "Rua da Conceição")
        val instructions = RouteInstructions.build(g.route(295.0, 0.0, 300.0, 300.0))
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), instructions.map { it.maneuver })
        assertEquals("Rua da Conceição", instructions[0].street)
    }

    @Test
    fun `a jog across a junction is no turn`() {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(200.0, 0.0)
        val c = g.node(200.0, 8.0)
        val d = g.node(400.0, 8.0)
        g.edge(a, b, name = "Rua")
        g.edge(b, c, name = "Rua")
        g.edge(c, d, name = "Rua")
        val instructions = RouteInstructions.build(g.route(0.0, 0.0, 400.0, 8.0))
        assertEquals(listOf(Maneuver.DEPART, Maneuver.ARRIVE), instructions.map { it.maneuver })
    }

    // --- tracking ---------------------------------------------------------------------------------

    /** Out and back along one 1 km street (a U-turn at a dead end). */
    private fun outAndBack(): Pair<TestGraphBuilder, Route> {
        val g = TestGraphBuilder()
        val a = g.node(0.0, 0.0)
        val b = g.node(1000.0, 0.0)
        g.edge(a, b, name = "Avenida Brasília")
        val engine = RoutingEngine(g.build())
        val prefs = RoutingPreferences()
        val stops = listOf(engine.snap(g.at(0.0, 0.0), prefs)!!, engine.snap(g.at(1000.0, 0.0), prefs)!!, engine.snap(g.at(0.0, 0.0), prefs)!!)
        return g to engine.routeVia(stops, prefs, passThrough = false).route()
    }

    @Test
    fun `progress follows the route in order where it passes twice`() {
        val (g, route) = outAndBack()
        assertEquals(2000.0, route.distanceM, 1.0)
        val tracker = RouteTracker(route)
        var t = 0L
        fun fix(x: Double, bearing: Double? = null) = tracker.update(g.at(x, 3.0), t.also { t += 1000 }, bearing = bearing)

        assertEquals(0.0, fix(0.0).alongM, 1.0)
        assertEquals(300.0, fix(300.0, 90.0).alongM, 1.0)
        assertEquals(700.0, fix(700.0, 90.0).alongM, 1.0)
        // On the way back the same places are 2000 − x along.
        fix(990.0, 90.0)
        val back = fix(700.0, 270.0)
        assertEquals(1300.0, back.alongM, 1.0)
        assertEquals(700.0, back.remainingM, 1.0)
        assertTrue(back.remainingS < route.durationS / 2)
        assertTrue(fix(5.0, 270.0).arrived)
    }

    @Test
    fun `off the route for five seconds, the rider needs a new route`() {
        val (g, route) = outAndBack()
        val tracker = RouteTracker(route)
        tracker.update(g.at(100.0, 0.0), 0)
        // 80 m away: not yet off route, then off route after 5 s.
        assertFalse(tracker.update(g.at(150.0, 80.0), 1000).offRoute)
        assertFalse(tracker.update(g.at(160.0, 80.0), 4000).offRoute)
        val away = tracker.update(g.at(170.0, 80.0), 6000)
        assertTrue(away.offRoute)
        assertEquals(100.0, away.alongM, 1.0)
        // Back on the route, all is well again.
        assertFalse(tracker.update(g.at(200.0, 5.0), 7000).offRoute)
    }

    @Test
    fun `a rider still walking to the start is not placed somewhere down the route`() {
        val (g, route) = outAndBack()
        val tracker = RouteTracker(route)
        val walking = tracker.update(g.at(500.0, 150.0), 0)
        assertEquals(0.0, walking.alongM, 0.0)
        assertEquals(route.distanceM, walking.remainingM, 1.0)
    }

    // --- splicing -----------------------------------------------------------------------------------

    @Test
    fun `a detour joined to the rest of the route is one route`() {
        val (_, route) = outAndBack()
        val rest = RouteSplice.slice(route, 1300.0)
        assertEquals(700.0, rest.distanceM, 1.0)
        assertEquals(rest.legs.sumOf { it.lengthM }, rest.distanceM, 1.0)
        assertEquals(0.0, rest.legs.first().startM, 1e-6)
        // Travelled back (adj→base), from 700 m to 0 m along the edge.
        assertTrue(rest.legs.single().reverse)
        assertEquals(0.0, rest.legs.single().from, 1.0)
        assertEquals(700.0, rest.legs.single().to, 1.0)

        val first = RouteSplice.slice(route, 0.0, 1300.0)
        val whole = RouteSplice.join(first, rest)
        assertEquals(route.distanceM, whole.distanceM, 1.0)
        assertEquals(route.durationS, whole.durationS, 1.0)
        assertEquals(route.legs.size + 1, whole.legs.size)
        whole.legs.forEach { leg -> assertEquals(whole.distances[leg.firstPoint], leg.startM, 1.0) }
    }

    // --- GPX --------------------------------------------------------------------------------------

    @Test
    fun `a ride exported to GPX reads back`() {
        val (g, route) = outAndBack()
        val track = listOf(TrackPoint(g.at(0.0, 0.0), 1_761_490_800_000, 12.5), TrackPoint(g.at(100.0, 0.0), 1_761_490_820_000))
        val gpx = Gpx.write("Volta <rio> & mar", track, route)
        assertTrue("Volta &lt;rio&gt; &amp; mar" in gpx)
        assertTrue("<time>2025-10-26T15:00:00Z</time>" in gpx)
        val points = Gpx.parse(gpx)
        assertEquals(2, points.size)
        assertEquals(g.at(100.0, 0.0).lon, points[1].lon, 1e-6)
        // Without a track, the route points are read; other apps write attributes in any order.
        assertEquals(route.points.size, Gpx.parse(Gpx.write("Rota", route = route)).size)
        assertEquals(1, Gpx.parse("""<gpx><wpt lon="-9.1" lat='38.7'/></gpx>""").size)
        assertNull(Gpx.parse("<gpx/>").firstOrNull())
    }

    // --- following a GPX ----------------------------------------------------------------------

    @Test
    fun `a track is followed through points every 250 m`() {
        val g = TestGraphBuilder()
        val track = (0..100).map { g.at(it * 10.0, 0.0) }
        val stops = TrackMatcher.stops(track)
        assertEquals(listOf(0.0, 250.0, 500.0, 750.0, 1000.0), stops.map { Math.round(it.distanceTo(track[0])).toDouble() })
        // A last point just past a stop replaces it.
        assertEquals(5, TrackMatcher.stops((0..105).map { g.at(it * 10.0, 0.0) }).size)
    }

    @Test
    fun `a GPX keeps to the streets it was ridden on, not the shortest way, and may turn back`() {
        val g = TestGraphBuilder()
        val sw = g.node(0.0, 0.0)
        val se = g.node(1000.0, 0.0)
        val nw = g.node(0.0, 300.0)
        val ne = g.node(1000.0, 300.0)
        val south = g.edge(sw, se, name = "Sul")
        val north = g.edge(nw, ne, name = "Norte")
        val west = g.edge(sw, nw, name = "Oeste")
        val east = g.edge(se, ne, name = "Este")
        val engine = RoutingEngine(g.build())
        fun line(vararg corners: Pair<Double, Double>) = corners.toList().zipWithNext().flatMap { (a, b) ->
            (0 until 20).map { i -> g.at(a.first + (b.first - a.first) * i / 20, a.second + (b.second - a.second) * i / 20) }
        } + corners.last().let { g.at(it.first, it.second) }

        // Round by the north street, with GPS noise of a few metres.
        val around = line(0.0 to 0.0, 0.0 to 300.0, 1000.0 to 300.0, 1000.0 to 0.0).mapIndexed { i, p ->
            if (i % 2 == 0) p else GeoPoint(p.lat + 0.00003, p.lon)
        }
        val route = (TrackMatcher.match(engine, around, RoutingPreferences()) as RouteResult.Success).route
        assertEquals(listOf(west, north, east), route.legs.map { it.edge }.distinct())
        assertEquals(1600.0, route.distanceM, 2.0)

        // Out along the south street and back.
        val outAndBack = line(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        val back = (TrackMatcher.match(engine, outAndBack, RoutingPreferences()) as RouteResult.Success).route
        assertEquals(listOf(south), back.legs.map { it.edge }.distinct())
        assertEquals(2000.0, back.distanceM, 2.0)

        assertTrue(TrackMatcher.match(engine, listOf(g.at(0.0, 0.0)), RoutingPreferences()) is RouteResult.Failure)
        assertEquals(RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK), TrackMatcher.match(engine, listOf(g.at(5000.0, 5000.0), g.at(0.0, 0.0)), RoutingPreferences()))
    }

    @Test
    fun `a GPX file's name is read, whatever the writer`() {
        assertEquals("Volta <rio>", Gpx.name(Gpx.write("Volta <rio>")))
        assertEquals("Passeio de domingo", Gpx.name("""<gpx><trk><name><![CDATA[ Passeio de domingo ]]></name></trk></gpx>"""))
        assertNull(Gpx.name("<gpx><trk><trkseg/></trk></gpx>"))
    }
}
