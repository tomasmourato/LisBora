package pt.lisbora.pipeline.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.model.CycleInfraClass

class CmlConflationTest {

    private fun line(f: RegionFixture, cls: CycleInfraClass, vararg xy: Pair<Double, Double>, contraflow: Boolean = false) =
        CmlLine("cml-${cls.id}", cls, "Teste", contraflow, xy.map { (x, y) -> f.point(x, y) })

    @Test
    fun `a separate track gets the class, the parallel road does not`() {
        val f = RegionFixture()
        val road = f.edge(f.node(0.0, 0.0), f.node(200.0, 0.0))
        val track = f.edge(f.node(0.0, 7.0), f.node(200.0, 7.0), road = RoadKind.CYCLEWAY)
        val crossing = f.edge(f.node(100.0, -100.0), f.node(100.0, 100.0))
        // The CML line is drawn between the two, slightly closer to the road.
        val result = CmlConflation(f.graph, f.projection).run(listOf(line(f, CycleInfraClass.SEGREGATED_TRACK, 0.0 to 3.0, 200.0 to 3.0)))

        assertEquals(CycleInfraClass.SEGREGATED_TRACK, f.graph.edges[track].cmlClass)
        assertNull(f.graph.edges[road].cmlClass)
        assertNull(f.graph.edges[crossing].cmlClass)
        assertEquals(1.0, result.matchedShare, 0.01)
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun `a painted lane goes to the road, not the pavement beside it`() {
        val f = RegionFixture()
        val road = f.edge(f.node(0.0, 0.0), f.node(150.0, 0.0), road = RoadKind.SECONDARY)
        val pavement = f.edge(f.node(0.0, 5.0), f.node(150.0, 5.0), road = RoadKind.FOOTWAY, getOff = true)
        CmlConflation(f.graph, f.projection).run(listOf(line(f, CycleInfraClass.PAINTED_LANE, 0.0 to 3.5, 150.0 to 3.5)))

        assertEquals(CycleInfraClass.PAINTED_LANE, f.graph.edges[road].cmlClass)
        assertNull(f.graph.edges[pavement].cmlClass)
    }

    @Test
    fun `far or crossing lines stay unmatched and are reported`() {
        val f = RegionFixture()
        f.edge(f.node(0.0, 0.0), f.node(300.0, 0.0))
        val result = CmlConflation(f.graph, f.projection).run(
            listOf(
                line(f, CycleInfraClass.SHARED_STREET_30, 0.0 to 50.0, 300.0 to 50.0),
                line(f, CycleInfraClass.SHARED_STREET_30, 150.0 to -40.0, 150.0 to 40.0),
            ),
        )
        assertEquals(0.0, result.matchedShare, 1e-9)
        assertEquals(2, result.unmatched.size)
    }

    @Test
    fun `an edge needs most of its length covered`() {
        val f = RegionFixture()
        val long = f.edge(f.node(0.0, 0.0), f.node(400.0, 0.0))
        CmlConflation(f.graph, f.projection).run(listOf(line(f, CycleInfraClass.SHARED_STREET_30, 0.0 to 0.0, 100.0 to 0.0)))
        assertNull(f.graph.edges[long].cmlClass)
    }

    @Test
    fun `contraflow lanes on one-way streets are reported, not fixed`() {
        val f = RegionFixture()
        val oneway = f.edge(f.node(0.0, 0.0), f.node(100.0, 0.0), backward = TravelMode.PUSH, bikeBackward = false)
        val result = CmlConflation(f.graph, f.projection).run(
            listOf(line(f, CycleInfraClass.PAINTED_LANE, 100.0 to 1.0, 0.0 to 1.0, contraflow = true)),
        )
        assertEquals(1, result.contraflowConflicts.size)
        assertEquals(TravelMode.PUSH, f.graph.edges[oneway].backward)
    }

    @Test
    fun `covered length merges overlapping intervals and clips to the edge`() {
        val intervals = listOf(doubleArrayOf(-5.0, 5.0), doubleArrayOf(3.0, 12.0), doubleArrayOf(20.0, 30.0), doubleArrayOf(95.0, 110.0))
        assertEquals(12.0 + 10.0 + 5.0, CmlConflation.coveredLength(intervals, 100.0), 1e-9)
    }
}
