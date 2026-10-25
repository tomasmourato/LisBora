package pt.lisbora.routing.graph

import pt.lisbora.routing.geo.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A position on an edge: the point of [edge] closest to a query point. */
data class Snap(
    val edge: Int,
    /** Distance from the edge's base node along its geometry, in metres. */
    val along: Double,
    val point: GeoPoint,
    /** Straight-line distance from the query point, in metres. */
    val distance: Double,
)

/**
 * Uniform grid over the graph bounds listing the edges whose segments touch each cell,
 * used to snap positions onto the network.
 */
class SpatialIndex(private val graph: RoutingGraph, cellSizeM: Double = DEFAULT_CELL_SIZE_M) {

    private val bounds = graph.data.bounds
    private val cosLat = cos(Math.toRadians(bounds.center.lat))
    private val cellLat = cellSizeM / METRES_PER_DEGREE
    private val cellLon = cellSizeM / (METRES_PER_DEGREE * cosLat)
    private val columns = max(1, ((bounds.east - bounds.west) / cellLon).toInt() + 1)
    private val rows = max(1, ((bounds.north - bounds.south) / cellLat).toInt() + 1)
    private val cellStart = IntArray(columns * rows + 1)
    private val cellEdges: IntArray

    init {
        // Two passes over the same cell visits: count, then fill. lastSeen dedups an edge within a cell.
        val lastSeen = IntArray(columns * rows) { -1 }
        forEachEdgeCell(lastSeen) { cell, _ -> cellStart[cell + 1]++ }
        for (c in 0 until columns * rows) cellStart[c + 1] += cellStart[c]
        cellEdges = IntArray(cellStart[columns * rows])
        val fill = cellStart.copyOf(columns * rows)
        lastSeen.fill(-1)
        forEachEdgeCell(lastSeen) { cell, edge -> cellEdges[fill[cell]++] = edge }
    }

    /**
     * Closest point on an edge accepted by [filter], within [maxDistanceM] of [query], or null.
     */
    fun snap(query: GeoPoint, maxDistanceM: Double = DEFAULT_MAX_SNAP_M, filter: (edge: Int) -> Boolean = { true }): Snap? {
        val qx = x(query.lon)
        val qy = y(query.lat)
        val col = column(query.lon)
        val row = row(query.lat)
        val maxRing = (maxDistanceM / min(cellLat, cellLon * cosLat) / METRES_PER_DEGREE).toInt() + 1
        var best: Snap? = null
        var bestDistance = maxDistanceM
        val checked = HashSet<Int>()
        for (ring in 0..maxRing) {
            // Every cell in this ring is at least (ring - 1) cells away from the query point.
            if (best != null && (ring - 1) * min(cellLat, cellLon * cosLat) * METRES_PER_DEGREE > bestDistance) break
            for (r in row - ring..row + ring) {
                for (c in col - ring..col + ring) {
                    if (max(abs(r - row), abs(c - col)) != ring) continue
                    if (r !in 0 until rows || c !in 0 until columns) continue
                    val cell = r * columns + c
                    for (i in cellStart[cell] until cellStart[cell + 1]) {
                        val edge = cellEdges[i]
                        if (!checked.add(edge) || !filter(edge)) continue
                        val candidate = closestOnEdge(edge, qx, qy) ?: continue
                        if (candidate.distance <= bestDistance) {
                            bestDistance = candidate.distance
                            best = candidate
                        }
                    }
                }
            }
        }
        return best
    }

    private fun closestOnEdge(edge: Int, qx: Double, qy: Double): Snap? {
        var bestDistanceSq = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestX = 0.0
        var bestY = 0.0
        var along = 0.0
        for (i in 0 until graph.pointCount(edge) - 1) {
            val ax = x(graph.pointLonE7(edge, i) / GraphData.E7)
            val ay = y(graph.pointLatE7(edge, i) / GraphData.E7)
            val bx = x(graph.pointLonE7(edge, i + 1) / GraphData.E7)
            val by = y(graph.pointLatE7(edge, i + 1) / GraphData.E7)
            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq == 0.0) 0.0 else (((qx - ax) * dx + (qy - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val distanceSq = (qx - px) * (qx - px) + (qy - py) * (qy - py)
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq
                bestAlong = along + t * graph.segmentLength(edge, i)
                bestX = px
                bestY = py
            }
            along += graph.segmentLength(edge, i)
        }
        if (bestDistanceSq == Double.MAX_VALUE) return null
        return Snap(edge, bestAlong, GeoPoint(lat(bestY), lon(bestX)), sqrt(bestDistanceSq))
    }

    private inline fun forEachEdgeCell(lastSeen: IntArray, action: (cell: Int, edge: Int) -> Unit) {
        for (edge in 0 until graph.edgeCount) {
            for (i in 0 until graph.pointCount(edge) - 1) {
                val lat1 = graph.pointLatE7(edge, i) / GraphData.E7
                val lon1 = graph.pointLonE7(edge, i) / GraphData.E7
                val lat2 = graph.pointLatE7(edge, i + 1) / GraphData.E7
                val lon2 = graph.pointLonE7(edge, i + 1) / GraphData.E7
                for (r in row(min(lat1, lat2))..row(max(lat1, lat2))) {
                    for (c in column(min(lon1, lon2))..column(max(lon1, lon2))) {
                        val cell = r * columns + c
                        if (lastSeen[cell] == edge) continue
                        lastSeen[cell] = edge
                        action(cell, edge)
                    }
                }
            }
        }
    }

    private fun column(lon: Double) = floor((lon - bounds.west) / cellLon).toInt().coerceIn(0, columns - 1)
    private fun row(lat: Double) = floor((lat - bounds.south) / cellLat).toInt().coerceIn(0, rows - 1)

    // Local planar metres relative to the south-west corner.
    private fun x(lon: Double) = (lon - bounds.west) * METRES_PER_DEGREE * cosLat
    private fun y(lat: Double) = (lat - bounds.south) * METRES_PER_DEGREE
    private fun lon(x: Double) = x / (METRES_PER_DEGREE * cosLat) + bounds.west
    private fun lat(y: Double) = y / METRES_PER_DEGREE + bounds.south

    companion object {
        const val DEFAULT_CELL_SIZE_M = 100.0
        const val DEFAULT_MAX_SNAP_M = 250.0
        private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M
    }
}
