package pt.lisbora.pipeline.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.pipeline.osm.OsmFeatures
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.model.CycleInfraClass

class EdgeEnrichmentTest {

    private val noFeatures = OsmFeatures(emptyList(), emptySet(), emptySet(), emptyList())

    @Test
    fun `CML paths can be ridden where OSM only asked to get off`() {
        val f = RegionFixture()
        val footway = f.edge(f.node(0.0, 0.0), f.node(100.0, 0.0), road = RoadKind.FOOTWAY, forward = TravelMode.PUSH, backward = TravelMode.PUSH, getOff = true)
        val plainFootway = f.edge(f.node(0.0, 50.0), f.node(100.0, 50.0), road = RoadKind.FOOTWAY, forward = TravelMode.PUSH, backward = TravelMode.PUSH, getOff = true)
        f.graph.edges[footway].cmlClass = CycleInfraClass.SHARED_WITH_PEDESTRIANS

        val stats = EdgeEnrichment(f.graph, f.projection).apply(noFeatures)
        assertEquals(TravelMode.RIDE, f.graph.edges[footway].forward)
        assertEquals(TravelMode.RIDE, f.graph.edges[footway].backward)
        assertEquals(EdgeInfra.SHARED_WITH_PEDESTRIANS, f.graph.edges[footway].infra)
        assertEquals(TravelMode.PUSH, f.graph.edges[plainFootway].forward)
        assertEquals(2, stats.upgradedToRide)
    }

    @Test
    fun `one-way rules are not relaxed by the CML`() {
        val f = RegionFixture()
        val oneway = f.edge(f.node(0.0, 0.0), f.node(100.0, 0.0), backward = TravelMode.PUSH, bikeBackward = false)
        f.graph.edges[oneway].cmlClass = CycleInfraClass.PAINTED_LANE
        EdgeEnrichment(f.graph, f.projection).apply(noFeatures)
        assertEquals(TravelMode.PUSH, f.graph.edges[oneway].backward)
    }

    @Test
    fun `tram lines mark the road they run on, not the cycle track beside it`() {
        val f = RegionFixture()
        val road = f.edge(f.node(0.0, 0.0), f.node(200.0, 0.0), road = RoadKind.TERTIARY)
        val track = f.edge(f.node(0.0, 6.0), f.node(200.0, 6.0), road = RoadKind.CYCLEWAY)
        val tram = listOf(listOf(f.point(0.0, 1.0), f.point(200.0, 1.0)))
        val stats = EdgeEnrichment(f.graph, f.projection).apply(OsmFeatures(tram, emptySet(), emptySet(), emptyList()))

        assertTrue(f.graph.edges[road].tram)
        assertFalse(f.graph.edges[track].tram)
        assertEquals(1, stats.tramEdges)
        assertEquals(3, f.graph.edges[road].stress) // tertiary 2 + tram
    }

    @Test
    fun `traffic lights mark the nearest edge`() {
        val f = RegionFixture()
        val near = f.edge(f.node(0.0, 0.0), f.node(100.0, 0.0))
        val far = f.edge(f.node(0.0, 40.0), f.node(100.0, 40.0))
        EdgeEnrichment(f.graph, f.projection).apply(OsmFeatures(emptyList(), emptySet(), emptySet(), listOf(f.point(50.0, 3.0))))
        assertTrue(f.graph.edges[near].signals)
        assertFalse(f.graph.edges[far].signals)
    }

    @Test
    fun `stress grows with road class, speed and lanes`() {
        val f = RegionFixture()
        val n = { f.node(0.0, 0.0) to f.node(10.0, 0.0) }
        fun stressOf(road: RoadKind, speed: Int = 0, lanes: Int = 0, infra: EdgeInfra = EdgeInfra.NONE): Int {
            val (a, b) = n()
            val e = f.graph.edges[f.edge(a, b, road = road, maxSpeedKmh = speed, lanes = lanes)]
            e.infra = infra
            return EdgeEnrichment.stress(e)
        }
        assertEquals(1, stressOf(RoadKind.CYCLEWAY))
        assertEquals(1, stressOf(RoadKind.RESIDENTIAL, speed = 30))
        assertEquals(2, stressOf(RoadKind.RESIDENTIAL))
        assertEquals(3, stressOf(RoadKind.SECONDARY))
        assertEquals(4, stressOf(RoadKind.SECONDARY, lanes = 4))
        assertEquals(4, stressOf(RoadKind.PRIMARY))
        assertEquals(3, stressOf(RoadKind.PRIMARY, infra = EdgeInfra.PAINTED_LANE))
        assertEquals(1, stressOf(RoadKind.PRIMARY, infra = EdgeInfra.SEGREGATED_TRACK))
        assertEquals(2, stressOf(RoadKind.SECONDARY, infra = EdgeInfra.SHARED_STREET_30))
    }
}
