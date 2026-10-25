package pt.lisbora.pipeline.graph

import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.GraphData
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.model.CycleInfraClass

/** A shape point with an elevation filled in from the DEM. */
class ShapePoint(val lat: Double, val lon: Double, var ele: Double = 0.0)

class RegionNode(val lat: Double, val lon: Double) {
    var ele: Double = 0.0
}

/** One edge of the working graph: what GraphHopper parsed, plus what the pipeline adds. */
class RegionEdge(
    var base: Int,
    var adj: Int,
    /** Shape points base→adj, including both end nodes. */
    var points: MutableList<ShapePoint>,
    val wayId: Long,
    val name: String?,
    var forward: TravelMode,
    var backward: TravelMode,
    /** GraphHopper bike access per direction (true even where the bike must be pushed). */
    val bikeForward: Boolean,
    val bikeBackward: Boolean,
    /** GraphHopper `get_off_bike` per direction. */
    val getOffForward: Boolean,
    val getOffBackward: Boolean,
    val road: RoadKind,
    val surface: SurfaceKind,
    val maxSpeedKmh: Int,
    val lanes: Int,
    val roundabout: Boolean,
    val bridge: Boolean,
    val tunnel: Boolean,
    /** Index of the edge this one duplicates (GraphHopper copies edges for via-way turn restrictions), or -1. */
    var copyOf: Int = -1,
) {
    var infra: EdgeInfra = EdgeInfra.NONE
    var cmlClass: CycleInfraClass? = null
    var tram: Boolean = false
    var signals: Boolean = false
    var stress: Int = 1

    val length: Double
        get() = points.zipWithNext { a, b ->
            RoutingGraph.planarDistance(GraphData.toE7(a.lat), GraphData.toE7(a.lon), GraphData.toE7(b.lat), GraphData.toE7(b.lon))
        }.sum()

    fun mode(reverse: Boolean): TravelMode = if (reverse) backward else forward
}

data class Restriction(val from: Int, val via: Int, val to: Int)

/** The region's bike network while the pipeline enriches it. */
class RegionGraph(
    val nodes: MutableList<RegionNode>,
    val edges: MutableList<RegionEdge>,
    val restrictions: MutableList<Restriction>,
) {
    /**
     * Keeps only the largest strongly connected component (arcs = directions that can be ridden or pushed,
     * steps excluded), so every kept position can reach every other. Steps between kept nodes stay.
     * Returns the number of nodes dropped.
     */
    fun keepLargestComponent(): Int {
        val outgoing = outgoingEdges()
        val component = StronglyConnectedComponents(nodes.size) { node, visit ->
            outgoing[node].forEach { (edge, reverse) ->
                val e = edges[edge]
                if (e.road != RoadKind.STEPS && e.mode(reverse) != TravelMode.NONE) visit(if (reverse) e.base else e.adj)
            }
        }.largest()
        return retainNodes(component)
    }

    /** For each node, the (edge, reverse) pairs leaving it. */
    fun outgoingEdges(): List<List<Pair<Int, Boolean>>> {
        val lists = List(nodes.size) { mutableListOf<Pair<Int, Boolean>>() }
        edges.forEachIndexed { i, e ->
            lists[e.base] += i to false
            lists[e.adj] += i to true
        }
        return lists
    }

    /** Drops nodes not in [keep] with their edges and restrictions, renumbering everything. */
    fun retainNodes(keep: BooleanArray): Int {
        val nodeIndex = IntArray(nodes.size) { -1 }
        val keptNodes = mutableListOf<RegionNode>()
        nodes.forEachIndexed { i, node -> if (keep[i]) { nodeIndex[i] = keptNodes.size; keptNodes += node } }
        val edgeIndex = IntArray(edges.size) { -1 }
        val keptEdges = mutableListOf<RegionEdge>()
        edges.forEachIndexed { i, e ->
            if (keep[e.base] && keep[e.adj]) {
                edgeIndex[i] = keptEdges.size
                e.base = nodeIndex[e.base]
                e.adj = nodeIndex[e.adj]
                keptEdges += e
            }
        }
        keptEdges.forEach { e -> if (e.copyOf >= 0) e.copyOf = edgeIndex[e.copyOf] }
        val keptRestrictions = restrictions.mapNotNull { r ->
            val from = edgeIndex[r.from]
            val to = edgeIndex[r.to]
            val via = nodeIndex[r.via]
            if (from < 0 || to < 0 || via < 0) null else Restriction(from, via, to)
        }
        val dropped = nodes.size - keptNodes.size
        nodes.clear(); nodes += keptNodes
        edges.clear(); edges += keptEdges
        restrictions.clear(); restrictions += keptRestrictions
        return dropped
    }
}

/** Iterative Tarjan's algorithm over a graph given by a neighbour callback. */
class StronglyConnectedComponents(
    private val nodeCount: Int,
    private val neighbours: (node: Int, visit: (Int) -> Unit) -> Unit,
) {
    /** Membership mask of the largest strongly connected component. */
    fun largest(): BooleanArray {
        val component = components()
        val sizes = IntArray(nodeCount)
        component.forEach { sizes[it]++ }
        val biggest = sizes.indices.maxByOrNull { sizes[it] } ?: return BooleanArray(nodeCount)
        return BooleanArray(nodeCount) { component[it] == biggest }
    }

    /** Component id of every node. */
    fun components(): IntArray {
        val index = IntArray(nodeCount) { -1 }
        val low = IntArray(nodeCount)
        val onStack = BooleanArray(nodeCount)
        val component = IntArray(nodeCount) { -1 }
        val stack = ArrayDeque<Int>()
        val adjacency = Array(nodeCount) { node -> mutableListOf<Int>().also { list -> neighbours(node) { list += it } }.toIntArray() }
        var counter = 0
        var components = 0
        val callStack = ArrayDeque<IntArray>() // (node, next neighbour position)
        for (root in 0 until nodeCount) {
            if (index[root] >= 0) continue
            callStack.addLast(intArrayOf(root, 0))
            index[root] = counter; low[root] = counter; counter++
            stack.addLast(root); onStack[root] = true
            while (callStack.isNotEmpty()) {
                val frame = callStack.last()
                val node = frame[0]
                val next = adjacency[node]
                if (frame[1] < next.size) {
                    val w = next[frame[1]++]
                    if (index[w] < 0) {
                        index[w] = counter; low[w] = counter; counter++
                        stack.addLast(w); onStack[w] = true
                        callStack.addLast(intArrayOf(w, 0))
                    } else if (onStack[w]) {
                        low[node] = minOf(low[node], index[w])
                    }
                } else {
                    callStack.removeLast()
                    callStack.lastOrNull()?.let { parent -> low[parent[0]] = minOf(low[parent[0]], low[node]) }
                    if (low[node] == index[node]) {
                        while (true) {
                            val w = stack.removeLast()
                            onStack[w] = false
                            component[w] = components
                            if (w == node) break
                        }
                        components++
                    }
                }
            }
        }
        return component
    }
}
