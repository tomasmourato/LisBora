package pt.lisbora.routing

import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.GraphData
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode
import kotlin.math.cos

/**
 * Builds small graphs for tests on a local metric grid: `node(x, y)` places a node x metres east
 * and y metres north of [origin].
 */
class TestGraphBuilder(private val origin: GeoPoint = GeoPoint(38.70, -9.15)) {

    private class Node(val lat: Int, val lon: Int, val ele: Double)
    private class Edge(val base: Int, val adj: Int, val flags: EdgeFlags, val name: String?, val pillars: List<Node>)

    private val nodes = mutableListOf<Node>()
    private val edges = mutableListOf<Edge>()
    private val restrictions = mutableListOf<Triple<Int, Int, Int>>()

    fun node(x: Double, y: Double, ele: Double = 0.0): Int {
        nodes += point(x, y, ele)
        return nodes.size - 1
    }

    fun point(nodeIndex: Int): GeoPoint = nodes[nodeIndex].let { GeoPoint(it.lat / GraphData.E7, it.lon / GraphData.E7) }

    fun at(x: Double, y: Double): GeoPoint = point(x, y, 0.0).let { GeoPoint(it.lat / GraphData.E7, it.lon / GraphData.E7) }

    @Suppress("LongParameterList")
    fun edge(
        base: Int,
        adj: Int,
        forward: TravelMode = TravelMode.RIDE,
        backward: TravelMode = TravelMode.RIDE,
        infra: EdgeInfra = EdgeInfra.NONE,
        road: RoadKind = RoadKind.RESIDENTIAL,
        surface: SurfaceKind = SurfaceKind.PAVED,
        stress: Int = 1,
        tram: Boolean = false,
        signals: Boolean = false,
        name: String? = null,
        pillars: List<Triple<Double, Double, Double>> = emptyList(),
        riverside: Boolean = false,
        green: Boolean = false,
        viewpoint: Boolean = false,
        lisbon: Boolean = false,
    ): Int {
        val flags = EdgeFlags.of(
            forward = forward, backward = backward, infra = infra, road = road, surface = surface,
            tram = tram, trafficSignals = signals, stress = stress, riverside = riverside, green = green, viewpoint = viewpoint,
            lisbon = lisbon,
        )
        edges += Edge(base, adj, flags, name, pillars.map { (x, y, ele) -> point(x, y, ele) })
        return edges.size - 1
    }

    fun restrict(from: Int, via: Int, to: Int) {
        restrictions += Triple(from, via, to)
    }

    fun buildData(): GraphData {
        val names = edges.mapNotNull { it.name }.distinct()
        val geometryStart = IntArray(edges.size + 1)
        edges.forEachIndexed { i, e -> geometryStart[i + 1] = geometryStart[i] + e.pillars.size }
        val pillars = edges.flatMap { it.pillars }
        val lengths = FloatArray(edges.size) { i ->
            val e = edges[i]
            val points = listOf(nodes[e.base]) + e.pillars + listOf(nodes[e.adj])
            points.zipWithNext { a, b -> RoutingGraph.planarDistance(a.lat, a.lon, b.lat, b.lon) }.sum().toFloat()
        }
        return GraphData(
            dataVersion = "test",
            bounds = GeoBounds(origin.lon - 0.05, origin.lat - 0.05, origin.lon + 0.05, origin.lat + 0.05),
            nodeLat = IntArray(nodes.size) { nodes[it].lat },
            nodeLon = IntArray(nodes.size) { nodes[it].lon },
            nodeEle = ShortArray(nodes.size) { GraphData.toDecimetres(nodes[it].ele) },
            edgeBase = IntArray(edges.size) { edges[it].base },
            edgeAdj = IntArray(edges.size) { edges[it].adj },
            edgeLength = lengths,
            edgeFlags = LongArray(edges.size) { edges[it].flags.bits },
            edgeName = IntArray(edges.size) { i -> edges[i].name?.let(names::indexOf) ?: -1 },
            geometryStart = geometryStart,
            pillarLat = IntArray(pillars.size) { pillars[it].lat },
            pillarLon = IntArray(pillars.size) { pillars[it].lon },
            pillarEle = ShortArray(pillars.size) { GraphData.toDecimetres(pillars[it].ele) },
            names = names,
            restrictionFrom = IntArray(restrictions.size) { restrictions[it].first },
            restrictionVia = IntArray(restrictions.size) { restrictions[it].second },
            restrictionTo = IntArray(restrictions.size) { restrictions[it].third },
        )
    }

    fun build(): RoutingGraph = RoutingGraph(buildData())

    private fun point(x: Double, y: Double, ele: Double): Node {
        val lat = origin.lat + y / METRES_PER_DEGREE
        val lon = origin.lon + x / (METRES_PER_DEGREE * cos(Math.toRadians(origin.lat)))
        return Node(GraphData.toE7(lat), GraphData.toE7(lon), ele)
    }

    private companion object {
        const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M
    }
}
