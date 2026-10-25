package pt.lisbora.pipeline.graph

import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode

/** Builds small [RegionGraph]s on a local metric grid (x metres east, y metres north of the region centre). */
class RegionFixture(val bounds: GeoBounds = GeoBounds(-9.20, 38.70, -9.10, 38.76)) {
    val projection = LocalProjection(bounds)
    val graph = RegionGraph(mutableListOf(), mutableListOf(), mutableListOf())

    fun point(x: Double, y: Double) = GeoPoint(projection.lat(y), projection.lon(x))

    fun node(x: Double, y: Double): Int {
        graph.nodes += RegionNode(projection.lat(y), projection.lon(x))
        return graph.nodes.size - 1
    }

    @Suppress("LongParameterList")
    fun edge(
        base: Int,
        adj: Int,
        road: RoadKind = RoadKind.RESIDENTIAL,
        forward: TravelMode = TravelMode.RIDE,
        backward: TravelMode = TravelMode.RIDE,
        bikeForward: Boolean = forward != TravelMode.NONE,
        bikeBackward: Boolean = backward != TravelMode.NONE,
        getOff: Boolean = false,
        bridge: Boolean = false,
        via: List<Pair<Double, Double>> = emptyList(),
        wayId: Long = graph.edges.size.toLong() + 1,
        maxSpeedKmh: Int = 0,
        lanes: Int = 0,
    ): Int {
        val b = graph.nodes[base]
        val a = graph.nodes[adj]
        val points = mutableListOf(ShapePoint(b.lat, b.lon))
        via.forEach { (x, y) -> points += ShapePoint(projection.lat(y), projection.lon(x)) }
        points += ShapePoint(a.lat, a.lon)
        graph.edges += RegionEdge(
            base = base, adj = adj, points = points, wayId = wayId, name = null,
            forward = forward, backward = backward, bikeForward = bikeForward, bikeBackward = bikeBackward,
            getOffForward = getOff, getOffBackward = getOff, road = road, surface = SurfaceKind.PAVED,
            maxSpeedKmh = maxSpeedKmh, lanes = lanes, roundabout = false, bridge = bridge, tunnel = false,
        )
        return graph.edges.size - 1
    }
}
