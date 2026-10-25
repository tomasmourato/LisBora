package pt.lisbora.routing.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.TestGraphBuilder

class RoutingGraphTest {

    @Test
    fun `adjacency lists every edge in both directions`() {
        val builder = TestGraphBuilder()
        val a = builder.node(0.0, 0.0)
        val b = builder.node(100.0, 0.0)
        val c = builder.node(100.0, 100.0)
        val ab = builder.edge(a, b)
        val bc = builder.edge(b, c)
        val graph = builder.build()

        val fromB = mutableListOf<Int>().also { list -> graph.forEachOutgoing(b) { list += it } }
        assertEquals(setOf(graph.key(ab, true), graph.key(bc, false)), fromB.toSet())
        assertEquals(a, graph.headNode(graph.key(ab, true)))
        assertEquals(b, graph.tailNode(graph.key(ab, true)))
        assertEquals(2, graph.degree(b))
    }

    @Test
    fun `segments carry length, climb and windowed grade`() {
        val builder = TestGraphBuilder()
        val a = builder.node(0.0, 0.0, ele = 0.0)
        val b = builder.node(100.0, 0.0, ele = 10.0)
        // Pillar halfway at 2 m: 2 m up over 50 m, then 8 m up over 50 m.
        val edge = builder.edge(a, b, pillars = listOf(Triple(50.0, 0.0, 2.0)))
        val graph = builder.build()

        assertEquals(2, graph.segmentCount(edge))
        assertEquals(50.0, graph.segmentLength(edge, 0), 0.1)
        assertEquals(100.0, graph.length(edge), 0.1)
        assertEquals(0.04, graph.segmentGrade(edge, 0), 0.002)
        assertEquals(0.16, graph.segmentGrade(edge, 1), 0.002)
        assertEquals(10.0, graph.climb(graph.key(edge, false)), 0.01)
        assertEquals(0.0, graph.climb(graph.key(edge, true)), 0.01)
    }

    @Test
    fun `short edges spread their climb over the grade window`() {
        val builder = TestGraphBuilder()
        val a = builder.node(0.0, 0.0, ele = 0.0)
        val b = builder.node(4.0, 0.0, ele = 0.6)
        // A 1 m blip over 2 m would be a 50% grade; the edge is shorter than the window, so 0.6 m over 12 m.
        val edge = builder.edge(a, b, pillars = listOf(Triple(2.0, 0.0, 1.0)))
        val graph = builder.build()
        assertEquals(0.05, graph.segmentGrade(edge, 0), 0.005)
        assertEquals(0.05, graph.segmentGrade(edge, 1), 0.005)
    }

    @Test
    fun `bearings and turn angles`() {
        val builder = TestGraphBuilder()
        val a = builder.node(0.0, 0.0)
        val b = builder.node(0.0, 100.0)
        val c = builder.node(100.0, 100.0)
        val ab = builder.edge(a, b)
        val bc = builder.edge(b, c)
        val graph = builder.build()

        assertEquals(0.0, graph.startBearing(graph.key(ab, false)), 0.5)
        assertEquals(180.0, graph.startBearing(graph.key(ab, true)), 0.5)
        assertEquals(90.0, graph.startBearing(graph.key(bc, false)), 0.5)
        // North then east is a right turn.
        assertEquals(90.0, RoutingGraph.turnAngle(graph.endBearing(graph.key(ab, false)), graph.startBearing(graph.key(bc, false))), 0.5)
        assertEquals(-90.0, RoutingGraph.turnAngle(90.0, 0.0), 1e-9)
        assertEquals(180.0, RoutingGraph.turnAngle(0.0, 180.0), 1e-9)
    }

    @Test
    fun `turn restrictions are looked up by from, via and to`() {
        val builder = TestGraphBuilder()
        val a = builder.node(0.0, 0.0)
        val b = builder.node(100.0, 0.0)
        val c = builder.node(200.0, 0.0)
        val d = builder.node(100.0, 100.0)
        val ab = builder.edge(a, b)
        val bc = builder.edge(b, c)
        val bd = builder.edge(b, d)
        builder.restrict(ab, b, bd)
        val graph = builder.build()

        assertTrue(graph.isTurnRestricted(ab, b, bd))
        assertFalse(graph.isTurnRestricted(ab, b, bc))
        assertFalse(graph.isTurnRestricted(bd, b, ab))
        assertFalse(graph.isTurnRestricted(ab, a, bd))
    }

    @Test
    fun `node stress is the busiest motor road at the junction`() {
        val builder = TestGraphBuilder()
        val a = builder.node(0.0, 0.0)
        val b = builder.node(100.0, 0.0)
        val c = builder.node(200.0, 0.0)
        builder.edge(a, b, road = RoadKind.PRIMARY, stress = 4)
        builder.edge(b, c, road = RoadKind.CYCLEWAY, stress = 1)
        val graph = builder.build()
        assertEquals(4, graph.nodeStress(b))
        assertEquals(0, graph.nodeStress(c))
    }
}
