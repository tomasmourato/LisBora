package pt.lisbora.pipeline.graph

import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.index.strtree.STRtree
import pt.lisbora.routing.geo.GeoBounds
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Local planar projection (metres east/north of the region centre), accurate enough at city scale. */
class LocalProjection(bounds: GeoBounds) {
    private val lat0 = bounds.center.lat
    private val lon0 = bounds.center.lon
    private val cosLat = cos(Math.toRadians(lat0))

    fun x(lon: Double) = (lon - lon0) * METRES_PER_DEGREE * cosLat
    fun y(lat: Double) = (lat - lat0) * METRES_PER_DEGREE
    fun lon(x: Double) = x / (METRES_PER_DEGREE * cosLat) + lon0
    fun lat(y: Double) = y / METRES_PER_DEGREE + lat0

    companion object {
        const val METRES_PER_DEGREE = Math.PI / 180 * 6_371_008.8
    }
}

/** One straight piece of an edge, in projected metres. */
class EdgeSegment(
    val edge: Int,
    val ax: Double,
    val ay: Double,
    val bx: Double,
    val by: Double,
    /** Distance from the edge's base node to (ax, ay). */
    val alongStart: Double,
) {
    val length = hypot(bx - ax, by - ay)

    /** Undirected bearing in [0, 180). */
    val bearing = undirectedBearing(ax, ay, bx, by)

    /** Distance from (x, y) to this segment and the fraction along it of the closest point. */
    fun project(x: Double, y: Double): Pair<Double, Double> {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(x - (ax + t * dx), y - (ay + t * dy)) to t
    }

    companion object {
        fun undirectedBearing(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val degrees = Math.toDegrees(atan2(bx - ax, by - ay))
            return ((degrees % 180) + 180) % 180
        }

        /** Smallest angle between two undirected bearings, in [0, 90]. */
        fun bearingDifference(a: Double, b: Double): Double {
            val d = abs(a - b) % 180
            return if (d > 90) 180 - d else d
        }
    }
}

/** STR-tree of the segments of the selected edges, for nearest-segment queries. */
class SegmentIndex(graph: RegionGraph, private val projection: LocalProjection, include: (RegionEdge) -> Boolean) {
    private val tree = STRtree()

    init {
        graph.edges.forEachIndexed { i, e ->
            if (e.copyOf >= 0 || !include(e)) return@forEachIndexed
            var along = 0.0
            e.points.zipWithNext { p, q ->
                val segment = EdgeSegment(i, projection.x(p.lon), projection.y(p.lat), projection.x(q.lon), projection.y(q.lat), along)
                along += segment.length
                tree.insert(Envelope(segment.ax, segment.bx, segment.ay, segment.by), segment)
            }
        }
        tree.build()
    }

    @Suppress("UNCHECKED_CAST")
    fun near(x: Double, y: Double, radius: Double): List<EdgeSegment> =
        tree.query(Envelope(x - radius, x + radius, y - radius, y + radius)) as List<EdgeSegment>
}
