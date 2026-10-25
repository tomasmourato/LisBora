package pt.lisbora.routing.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pt.lisbora.routing.TestGraphBuilder

class SpatialIndexTest {

    private val builder = TestGraphBuilder()
    private val a = builder.node(0.0, 0.0)
    private val b = builder.node(1000.0, 0.0)
    private val c = builder.node(1000.0, 30.0)
    private val road = builder.edge(a, b)
    private val track = builder.edge(a, c, infra = EdgeInfra.SEGREGATED_TRACK, pillars = listOf(Triple(0.0, 30.0, 0.0)))
    private val graph = builder.build()
    private val index = SpatialIndex(graph)

    @Test
    fun `snaps to the nearest edge with the distance along it`() {
        val snap = index.snap(builder.at(400.0, 5.0))!!
        assertEquals(road, snap.edge)
        assertEquals(400.0, snap.along, 1.0)
        assertEquals(5.0, snap.distance, 0.5)

        val onTrack = index.snap(builder.at(400.0, 26.0))!!
        assertEquals(track, onTrack.edge)
        // 30 m up the first pillar segment, then 400 m east.
        assertEquals(430.0, onTrack.along, 1.0)
    }

    @Test
    fun `respects the filter and the maximum distance`() {
        val filtered = index.snap(builder.at(400.0, 5.0)) { it != road }!!
        assertEquals(track, filtered.edge)
        assertNull(index.snap(builder.at(400.0, 500.0), maxDistanceM = 100.0))
    }

    @Test
    fun `clamps to the edge ends`() {
        val snap = index.snap(builder.at(1100.0, -10.0))!!
        assertEquals(road, snap.edge)
        assertEquals(graph.length(road), snap.along, 0.5)
    }
}
