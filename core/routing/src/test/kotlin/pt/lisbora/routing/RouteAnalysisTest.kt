package pt.lisbora.routing

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode

class RouteAnalysisTest {

    /** Routes from the first to the last node of a straight street built by [build]. */
    private fun straightRoute(setup: TestGraphBuilder.() -> Pair<Double, Double>): Route {
        val g = TestGraphBuilder()
        val (from, to) = g.setup()
        val result = RoutingEngine(g.build()).route(g.at(from, 0.0), g.at(to, 0.0))
        check(result is RouteResult.Success) { "expected a route, got $result" }
        return result.route
    }

    @Test
    fun `merges nearby stretches and drops the ones too short to matter`() {
        val route = straightRoute {
            val xs = listOf(0.0, 100.0, 200.0, 210.0, 300.0, 340.0, 500.0, 700.0, 720.0, 800.0)
            val n = xs.map { node(it, 0.0) }
            edge(n[0], n[1])
            edge(n[1], n[2], tram = true)
            edge(n[2], n[3])
            edge(n[3], n[4], tram = true)
            edge(n[4], n[5], surface = SurfaceKind.COBBLESTONE) // 40 m: too short
            edge(n[5], n[6])
            edge(n[6], n[7], road = RoadKind.PRIMARY, stress = 4)
            edge(n[7], n[8], forward = TravelMode.PUSH, road = RoadKind.FOOTWAY)
            edge(n[8], n[9], road = RoadKind.PRIMARY, stress = 4, infra = EdgeInfra.SEGREGATED_TRACK)
            0.0 to 800.0
        }

        val hazards = RouteAnalysis.hazards(route)
        assertEquals(listOf(HazardKind.TRAM_TRACKS, HazardKind.BUSY_ROAD, HazardKind.WALK), hazards.map { it.kind })
        val (tram, busy, walk) = hazards
        assertEquals(100.0, tram.startM, 1.0)
        assertEquals(300.0, tram.endM, 1.0)
        assertEquals(200.0, busy.lengthM, 1.0)
        assertEquals(20.0, walk.lengthM, 1.0)
        assertEquals(700.0, walk.point.distanceTo(route.points.first()), 1.0)
        assertNull(tram.gradePercent)
    }

    @Test
    fun `reports sustained climbs and descents with their steepest grade`() {
        val route = straightRoute {
            val n = listOf(0.0 to 30.0, 100.0 to 30.0, 200.0 to 18.0, 300.0 to 18.0, 400.0 to 27.0, 500.0 to 28.0)
                .map { (x, ele) -> node(x, 0.0, ele) }
            n.zipWithNext { a, b -> edge(a, b) }
            0.0 to 500.0
        }

        val hazards = RouteAnalysis.hazards(route)
        assertEquals(listOf(HazardKind.STEEP_DESCENT, HazardKind.STEEP_CLIMB), hazards.map { it.kind })
        assertEquals(12.0, hazards[0].gradePercent!!, 0.2)
        assertEquals(100.0, hazards[0].startM, 1.0)
        assertEquals(9.0, hazards[1].gradePercent!!, 0.2)
        assertTrue(hazards.all { it.lengthM >= RouteAnalysis.STEEP_WINDOW_M })
    }

    @Test
    fun `stress breakdown counts only ridden metres`() {
        val route = straightRoute {
            val n = listOf(0.0, 100.0, 300.0, 350.0).map { node(it, 0.0) }
            edge(n[0], n[1], stress = 1)
            edge(n[1], n[2], stress = 3)
            edge(n[2], n[3], forward = TravelMode.PUSH, stress = 2)
            0.0 to 350.0
        }
        val lengths = RouteAnalysis.lengthByStress(route)
        assertArrayEquals(doubleArrayOf(100.0, 0.0, 200.0, 0.0), lengths, 1.0)
    }

    @Test
    fun `points and grades along the route`() {
        val route = straightRoute {
            val n = listOf(0.0 to 0.0, 200.0 to 10.0, 400.0 to 10.0).map { (x, ele) -> node(x, 0.0, ele) }
            n.zipWithNext { a, b -> edge(a, b) }
            0.0 to 400.0
        }
        assertEquals(150.0, RouteAnalysis.pointAt(route, 150.0).distanceTo(route.points.first()), 0.5)
        assertEquals(route.points.last(), RouteAnalysis.pointAt(route, 1e6))

        val grades = RouteAnalysis.grades(route)
        assertEquals(route.points.size, grades.size)
        assertEquals(0.05, grades.first(), 1e-3)
        assertEquals(0.0, grades.last(), 1e-3)
    }
}
