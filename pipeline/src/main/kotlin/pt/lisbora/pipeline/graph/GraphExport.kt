package pt.lisbora.pipeline.graph

import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.GraphData

/** Converts the enriched [RegionGraph] into the arrays stored in `graph.lbg`. */
object GraphExport {

    fun toGraphData(graph: RegionGraph, dataVersion: String, bounds: GeoBounds): GraphData {
        val names = graph.edges.mapNotNull { it.name }.distinct().sorted()
        val nameIndex = names.withIndex().associate { (i, name) -> name to i }
        val geometryStart = IntArray(graph.edges.size + 1)
        graph.edges.forEachIndexed { i, e -> geometryStart[i + 1] = geometryStart[i] + e.points.size - 2 }
        val pillars = graph.edges.flatMap { it.points.subList(1, it.points.size - 1) }

        return GraphData(
            dataVersion = dataVersion,
            bounds = bounds,
            nodeLat = IntArray(graph.nodes.size) { GraphData.toE7(graph.nodes[it].lat) },
            nodeLon = IntArray(graph.nodes.size) { GraphData.toE7(graph.nodes[it].lon) },
            nodeEle = ShortArray(graph.nodes.size) { GraphData.toDecimetres(graph.nodes[it].ele) },
            edgeBase = IntArray(graph.edges.size) { graph.edges[it].base },
            edgeAdj = IntArray(graph.edges.size) { graph.edges[it].adj },
            edgeLength = FloatArray(graph.edges.size) { graph.edges[it].length.toFloat() },
            edgeFlags = LongArray(graph.edges.size) { flags(graph.edges[it]).bits },
            edgeName = IntArray(graph.edges.size) { i -> graph.edges[i].name?.let { nameIndex.getValue(it) } ?: -1 },
            geometryStart = geometryStart,
            pillarLat = IntArray(pillars.size) { GraphData.toE7(pillars[it].lat) },
            pillarLon = IntArray(pillars.size) { GraphData.toE7(pillars[it].lon) },
            pillarEle = ShortArray(pillars.size) { GraphData.toDecimetres(pillars[it].ele) },
            names = names,
            restrictionFrom = IntArray(graph.restrictions.size) { graph.restrictions[it].from },
            restrictionVia = IntArray(graph.restrictions.size) { graph.restrictions[it].via },
            restrictionTo = IntArray(graph.restrictions.size) { graph.restrictions[it].to },
        )
    }

    fun flags(e: RegionEdge): EdgeFlags = EdgeFlags.of(
        forward = e.forward,
        backward = e.backward,
        infra = e.infra,
        road = e.road,
        surface = e.surface,
        tram = e.tram,
        bridge = e.bridge,
        tunnel = e.tunnel,
        roundabout = e.roundabout,
        trafficSignals = e.signals,
        stress = e.stress,
        maxSpeedKmh = e.maxSpeedKmh,
        lanes = e.lanes,
    )
}
