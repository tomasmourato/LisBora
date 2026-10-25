package pt.lisbora.pipeline.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.pipeline.dem.ElevationModel
import kotlin.math.exp
import kotlin.math.pow

class ElevationAssignerTest {

    @Test
    fun `a hump in a long straight street becomes a shape point`() {
        val f = RegionFixture()
        val a = f.node(0.0, 0.0)
        val b = f.node(400.0, 0.0)
        val street = f.edge(a, b)
        // 10 m hill centred at x = 200 m, 60 m wide.
        val dem = ElevationModel { lat, lon ->
            val x = f.projection.x(lon)
            10 * exp(-((x - 200) / 60).pow(2))
        }
        val stats = ElevationAssigner(dem).assign(f.graph)

        val points = f.graph.edges[street].points
        assertTrue(points.size > 2, "expected extra shape points")
        assertTrue(stats.addedPoints > 0)
        val top = points.maxBy { it.ele }
        assertEquals(200.0, f.projection.x(top.lon), 20.0)
        assertTrue(top.ele in 7.0..10.0, "smoothed top ${top.ele}")
    }

    @Test
    fun `a roadside pit does not drag the street down`() {
        val f = RegionFixture()
        val street = f.edge(f.node(0.0, 0.0), f.node(300.0, 0.0))
        // Flat street at 50 m; the DEM has a 12 m deep, 24 m long hole beside it (e.g. an excavation).
        val dem = ElevationModel { _, lon -> if (f.projection.x(lon) in 138.0..162.0) 38.0 else 50.0 }
        ElevationAssigner(dem).assign(f.graph)

        f.graph.edges[street].points.forEach { assertEquals(50.0, it.ele, 1.0) }
    }

    @Test
    fun `an even slope needs no extra points`() {
        val f = RegionFixture()
        val street = f.edge(f.node(0.0, 0.0), f.node(400.0, 0.0))
        val dem = ElevationModel { _, lon -> f.projection.x(lon) * 0.05 }
        ElevationAssigner(dem).assign(f.graph)

        val points = f.graph.edges[street].points
        assertEquals(2, points.size)
        assertEquals(20.0, points.last().ele - points.first().ele, 0.5)
    }

    @Test
    fun `bridges ignore the valley below and interpolate between their ends`() {
        val f = RegionFixture()
        val west = f.node(0.0, 0.0)
        val middle = f.node(100.0, 0.0)
        val east = f.node(200.0, 0.0)
        val approachWest = f.node(-100.0, 0.0)
        val approachEast = f.node(300.0, 0.0)
        f.edge(approachWest, west)
        val span1 = f.edge(west, middle, bridge = true, via = listOf(50.0 to 0.0))
        f.edge(middle, east, bridge = true)
        f.edge(east, approachEast)
        // Plateaus at 50 m on both sides, a 0 m valley in between.
        val dem = ElevationModel { _, lon -> if (f.projection.x(lon) in 10.0..190.0) 0.0 else 50.0 }
        val stats = ElevationAssigner(dem).assign(f.graph)

        assertEquals(50.0, f.graph.nodes[middle].ele, 1.0)
        assertEquals(50.0, f.graph.edges[span1].points[1].ele, 1.0)
        assertEquals(1, stats.interpolatedNodes)
    }

    @Test
    fun `copies of an edge share its elevations`() {
        val f = RegionFixture()
        val a = f.node(0.0, 0.0)
        val b = f.node(300.0, 0.0)
        val original = f.edge(a, b)
        val copy = f.edge(a, b)
        f.graph.edges[copy].copyOf = original
        ElevationAssigner(ElevationModel { _, lon -> f.projection.x(lon) * 0.1 }).assign(f.graph)
        assertTrue(f.graph.edges[copy].points === f.graph.edges[original].points)
    }
}
