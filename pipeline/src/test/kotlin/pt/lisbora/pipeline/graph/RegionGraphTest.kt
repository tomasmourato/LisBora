package pt.lisbora.pipeline.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.TravelMode

class RegionGraphTest {

    @Test
    fun `keeps the largest strongly connected component`() {
        val f = RegionFixture()
        val a = f.node(0.0, 0.0)
        val b = f.node(100.0, 0.0)
        val c = f.node(100.0, 100.0)
        val trap = f.node(200.0, 0.0) // reachable through a one-way street, but no way back
        val island = f.node(500.0, 500.0)
        val islandB = f.node(600.0, 500.0)
        f.edge(a, b); f.edge(b, c); f.edge(c, a)
        f.edge(b, trap, backward = TravelMode.NONE)
        f.edge(island, islandB)
        f.graph.restrictions += Restriction(0, b, 1)

        val dropped = f.graph.keepLargestComponent()
        assertEquals(3, dropped)
        assertEquals(3, f.graph.nodes.size)
        assertEquals(3, f.graph.edges.size)
        assertEquals(listOf(Restriction(0, 1, 1)), f.graph.restrictions)
    }

    @Test
    fun `steps do not hold a component together`() {
        val f = RegionFixture()
        val a = f.node(0.0, 0.0)
        val b = f.node(100.0, 0.0)
        val c = f.node(100.0, 50.0)
        val d = f.node(150.0, 50.0)
        val e = f.node(200.0, 50.0)
        f.edge(a, b)
        f.edge(b, c, road = RoadKind.STEPS, forward = TravelMode.PUSH, backward = TravelMode.PUSH)
        f.edge(c, d); f.edge(d, e)

        f.graph.keepLargestComponent()
        // c-d-e (3 nodes) beats a-b (2 nodes); the steps edge goes with b.
        assertEquals(3, f.graph.nodes.size)
        assertEquals(2, f.graph.edges.size)
    }

    @Test
    fun `travel modes follow access, get-off and one-way rules`() {
        assertEquals(TravelMode.RIDE, RegionExtractor.travelMode(access = true, getOff = false, reverseAccess = false, roundabout = false))
        assertEquals(TravelMode.PUSH, RegionExtractor.travelMode(access = true, getOff = true, reverseAccess = true, roundabout = false))
        assertEquals(TravelMode.PUSH, RegionExtractor.travelMode(access = false, getOff = true, reverseAccess = true, roundabout = false))
        assertEquals(TravelMode.NONE, RegionExtractor.travelMode(access = false, getOff = true, reverseAccess = true, roundabout = true))
        assertEquals(TravelMode.NONE, RegionExtractor.travelMode(access = false, getOff = false, reverseAccess = false, roundabout = false))
    }
}
