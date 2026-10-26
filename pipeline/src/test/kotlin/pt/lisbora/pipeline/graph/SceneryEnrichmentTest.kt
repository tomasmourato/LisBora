package pt.lisbora.pipeline.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.pipeline.osm.OsmScenery
import pt.lisbora.routing.graph.RoadKind

class SceneryEnrichmentTest {

    @Test
    fun `the riverside promenade is by the river, the avenue behind the docks is not`() {
        val f = RegionFixture()
        val promenade = f.edge(f.node(0.0, 30.0), f.node(400.0, 30.0))
        val avenue = f.edge(f.node(0.0, 150.0), f.node(400.0, 150.0))
        // Only one end near the water: most of the edge is not.
        val leaving = f.edge(f.node(0.0, 30.0), f.node(0.0, 400.0))
        val ferry = f.edge(f.node(0.0, -10.0), f.node(400.0, -500.0), road = RoadKind.FERRY)
        val shore = listOf(f.point(-100.0, 0.0), f.point(500.0, 0.0))

        val stats = SceneryEnrichment(f.graph, f.projection).apply(OsmScenery(listOf(shore), emptyList(), emptyList()))
        assertTrue(f.graph.edges[promenade].riverside)
        assertFalse(f.graph.edges[avenue].riverside)
        assertFalse(f.graph.edges[leaving].riverside)
        assertFalse(f.graph.edges[ferry].riverside)
        assertEquals(0.4, stats.riversideKm, 0.01)
    }

    @Test
    fun `paths through a park are green and pass the viewpoint in it`() {
        val f = RegionFixture()
        val path = f.edge(f.node(20.0, 50.0), f.node(180.0, 50.0), road = RoadKind.PATH)
        val outside = f.edge(f.node(300.0, 50.0), f.node(500.0, 50.0))
        val park = listOf(f.point(0.0, 0.0), f.point(200.0, 0.0), f.point(200.0, 100.0), f.point(0.0, 100.0), f.point(0.0, 0.0))
        val viewpoint = f.point(100.0, 70.0)

        val stats = SceneryEnrichment(f.graph, f.projection).apply(OsmScenery(emptyList(), listOf(park), listOf(viewpoint)))
        assertTrue(f.graph.edges[path].green)
        assertTrue(f.graph.edges[path].viewpoint)
        assertFalse(f.graph.edges[outside].green || f.graph.edges[outside].viewpoint)
        assertEquals(1, stats.viewpointEdges)
    }

    @Test
    fun `streets mostly inside the municipality of Lisbon are marked, the ones beyond it are not`() {
        val f = RegionFixture()
        val inside = f.edge(f.node(100.0, 100.0), f.node(400.0, 100.0))
        val crossing = f.edge(f.node(800.0, 100.0), f.node(1300.0, 100.0))
        val outside = f.edge(f.node(1100.0, 300.0), f.node(1400.0, 300.0))
        val lisbon = listOf(f.point(0.0, 0.0), f.point(1000.0, 0.0), f.point(1000.0, 500.0), f.point(0.0, 500.0), f.point(0.0, 0.0))

        val stats = SceneryEnrichment(f.graph, f.projection).apply(OsmScenery(emptyList(), emptyList(), emptyList(), listOf(lisbon)))
        assertTrue(f.graph.edges[inside].lisbon)
        // 200 m of 500 inside: not enough.
        assertFalse(f.graph.edges[crossing].lisbon)
        assertFalse(f.graph.edges[outside].lisbon)
        assertEquals(0.3, stats.lisbonKm, 0.01)
    }

    @Test
    fun `copies of an edge get its flags`() {
        val f = RegionFixture()
        val a = f.node(0.0, 30.0)
        val b = f.node(300.0, 30.0)
        val original = f.edge(a, b)
        val copy = f.edge(a, b)
        f.graph.edges[copy].copyOf = original
        val shore = listOf(f.point(-100.0, 0.0), f.point(400.0, 0.0))

        val stats = SceneryEnrichment(f.graph, f.projection).apply(OsmScenery(listOf(shore), emptyList(), emptyList()))
        assertTrue(f.graph.edges[copy].riverside)
        // Copies are not counted twice.
        assertEquals(0.3, stats.riversideKm, 0.01)
    }
}
