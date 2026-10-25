package pt.lisbora.pipeline.graph

import pt.lisbora.pipeline.dem.ElevationModel
import pt.lisbora.routing.graph.RoadKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** What [ElevationAssigner] did, for the QA report. */
data class ElevationStats(val sampledEdges: Int, val addedPoints: Int, val interpolatedEdges: Int, val interpolatedNodes: Int)

/**
 * Gives every node and shape point an elevation:
 * - nodes: median of the DEM over a 3×3 grid (± [NODE_SPREAD_M]), robust to a pit or ramp beside the junction;
 * - ordinary edges: sampled every [spacingM], cleaned with a running median over [medianWindowM] (removes short
 *   pits and bumps such as an excavation or a garage ramp at the roadside, while keeping real slopes), smoothed
 *   with a moving average over [smoothingWindowM], and only the extra samples that change the profile by more
 *   than [toleranceM] are kept as new shape points;
 * - bridges, tunnels and ferries: the DEM measures the ground or water underneath, so their nodes are
 *   interpolated from the surrounding streets and their shape points linearly between the ends.
 */
class ElevationAssigner(
    private val dem: ElevationModel,
    private val spacingM: Double = 8.0,
    private val medianWindowM: Double = 48.0,
    private val smoothingWindowM: Double = 24.0,
    private val toleranceM: Double = 0.5,
) {

    fun assign(graph: RegionGraph): ElevationStats {
        val elevated = BooleanArray(graph.nodes.size) // touches at least one ordinary (ground-level) edge
        graph.edges.forEach { e ->
            if (!e.isElevatedStructure()) { elevated[e.base] = true; elevated[e.adj] = true }
        }
        graph.nodes.forEachIndexed { i, node -> if (elevated[i]) node.ele = nodeElevation(node.lat, node.lon) }
        val interpolatedNodes = interpolateStructureNodes(graph, elevated)

        var sampled = 0
        var added = 0
        var interpolated = 0
        graph.edges.forEach { e ->
            if (e.copyOf >= 0) return@forEach
            e.points.first().ele = graph.nodes[e.base].ele
            e.points.last().ele = graph.nodes[e.adj].ele
            if (e.isElevatedStructure()) {
                interpolateLinearly(e.points)
                interpolated++
            } else {
                added += sampleProfile(e)
                sampled++
            }
        }
        // Copies share the original's geometry.
        graph.edges.forEach { e -> if (e.copyOf >= 0) e.points = graph.edges[e.copyOf].points }
        return ElevationStats(sampled, added, interpolated, interpolatedNodes)
    }

    private fun nodeElevation(lat: Double, lon: Double): Double {
        val dLat = NODE_SPREAD_M / METRES_PER_DEGREE
        val dLon = NODE_SPREAD_M / (METRES_PER_DEGREE * cos(Math.toRadians(lat)))
        val samples = DoubleArray(9) { i -> dem.elevation(lat + (i / 3 - 1) * dLat, lon + (i % 3 - 1) * dLon) }
        return samples.sorted()[4]
    }

    /**
     * Nodes only reached by bridges, tunnels or ferries get the inverse-distance-weighted elevation of the
     * ground-level nodes bounding their structure (found by walking the connected structure edges).
     */
    private fun interpolateStructureNodes(graph: RegionGraph, elevated: BooleanArray): Int {
        val outgoing = graph.outgoingEdges()
        val done = BooleanArray(graph.nodes.size)
        var count = 0
        for (start in graph.nodes.indices) {
            if (elevated[start] || done[start]) continue
            // Flood the structure: interior nodes plus the ground-level nodes at its ends.
            val interior = mutableListOf<Int>()
            val anchors = mutableSetOf<Int>()
            val queue = ArrayDeque(listOf(start))
            done[start] = true
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                interior += node
                outgoing[node].forEach { (edge, reverse) ->
                    val e = graph.edges[edge]
                    val next = if (reverse) e.base else e.adj
                    if (elevated[next]) anchors += next
                    else if (!done[next]) { done[next] = true; queue += next }
                }
            }
            interior.forEach { node ->
                val n = graph.nodes[node]
                var weights = 0.0
                var sum = 0.0
                anchors.forEach { a ->
                    val anchor = graph.nodes[a]
                    val w = 1 / max(1.0, distance(n.lat, n.lon, anchor.lat, anchor.lon))
                    weights += w
                    sum += w * anchor.ele
                }
                n.ele = if (weights > 0) sum / weights else 0.0
                count++
            }
        }
        return count
    }

    /** Resamples, smooths and simplifies one ground-level edge; returns how many points were added. */
    private fun sampleProfile(edge: RegionEdge): Int {
        val original = edge.points
        // Densify: (point, isOriginal) with the original shape points always kept.
        val dense = mutableListOf<Pair<ShapePoint, Boolean>>()
        original.forEachIndexed { i, p ->
            dense += p to true
            if (i < original.size - 1) {
                val q = original[i + 1]
                val steps = ceil(distance(p.lat, p.lon, q.lat, q.lon) / spacingM).toInt()
                for (s in 1 until steps) {
                    val t = s.toDouble() / steps
                    dense += ShapePoint(p.lat + t * (q.lat - p.lat), p.lon + t * (q.lon - p.lon)) to false
                }
            }
        }
        val along = DoubleArray(dense.size)
        for (i in 1 until dense.size) {
            val (a, _) = dense[i - 1]
            val (b, _) = dense[i]
            along[i] = along[i - 1] + distance(a.lat, a.lon, b.lat, b.lon)
        }
        val raw = DoubleArray(dense.size) { i ->
            when (i) {
                0 -> original.first().ele
                dense.size - 1 -> original.last().ele
                else -> dense[i].first.let { dem.elevation(it.lat, it.lon) }
            }
        }
        // Running median, then moving average; the end nodes stay fixed so edges meet at the same height.
        val cleaned = DoubleArray(dense.size) { i ->
            if (i == 0 || i == dense.size - 1) return@DoubleArray raw[i]
            dense.indices.filter { abs(along[it] - along[i]) <= medianWindowM / 2 }.map { raw[it] }.sorted().let { it[it.size / 2] }
        }
        val smooth = DoubleArray(dense.size) { i ->
            if (i == 0 || i == dense.size - 1) return@DoubleArray cleaned[i]
            var sum = 0.0
            var n = 0
            for (j in dense.indices) {
                if (abs(along[j] - along[i]) <= smoothingWindowM / 2) { sum += cleaned[j]; n++ }
            }
            sum / n
        }
        dense.forEachIndexed { i, (p, _) -> p.ele = smooth[i] }

        // Keep original points, plus extra samples that matter (Douglas-Peucker on the elevation profile).
        val keep = BooleanArray(dense.size) { dense[it].second }
        simplify(along, smooth, keep, 0, dense.size - 1)
        edge.points = dense.filterIndexed { i, _ -> keep[i] }.map { it.first }.toMutableList()
        return edge.points.size - original.size
    }

    private fun simplify(along: DoubleArray, ele: DoubleArray, keep: BooleanArray, from: Int, to: Int) {
        if (to - from < 2) return
        var worst = -1
        var worstError = toleranceM
        for (i in from + 1 until to) {
            if (keep[i]) {
                // Original points split the profile; simplify each part on its own.
                simplify(along, ele, keep, from, i)
                simplify(along, ele, keep, i, to)
                return
            }
            val t = (along[i] - along[from]) / (along[to] - along[from]).coerceAtLeast(1e-9)
            val error = abs(ele[i] - (ele[from] + t * (ele[to] - ele[from])))
            if (error > worstError) { worstError = error; worst = i }
        }
        if (worst >= 0) {
            keep[worst] = true
            simplify(along, ele, keep, from, worst)
            simplify(along, ele, keep, worst, to)
        }
    }

    private fun interpolateLinearly(points: List<ShapePoint>) {
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) along[i] = along[i - 1] + distance(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        val total = along.last()
        val start = points.first().ele
        val end = points.last().ele
        for (i in 1 until points.size - 1) points[i].ele = if (total > 0) start + (end - start) * along[i] / total else start
    }

    companion object {
        private const val NODE_SPREAD_M = 4.0
        private const val METRES_PER_DEGREE = Math.PI / 180 * 6_371_008.8

        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * METRES_PER_DEGREE
            val dx = (lon2 - lon1) * METRES_PER_DEGREE * cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(dx, dy)
        }
    }
}

/** Bridges, tunnels and ferries, whose elevation the ground DEM cannot measure. */
fun RegionEdge.isElevatedStructure(): Boolean = bridge || tunnel || road == RoadKind.FERRY
