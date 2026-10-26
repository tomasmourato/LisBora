package pt.lisbora.pipeline.graph

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.index.strtree.STRtree
import pt.lisbora.pipeline.osm.OsmScenery
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.RoadKind
import kotlin.math.hypot
import kotlin.math.max

/** Counts for the QA report. */
data class SceneryStats(val riversideKm: Double, val greenKm: Double, val viewpointEdges: Int, val lisbonKm: Double = 0.0)

/**
 * Marks the edges Descobrir looks for: along the river or a dock (most of the edge within [RIVERSIDE_M]
 * of the shore), through parks and woods (most of it inside one), and passing a viewpoint (within [VIEWPOINT_M]).
 * Also marks the edges inside the municipality of Lisbon (most of the edge inside it), where GIRA bikes may go.
 */
class SceneryEnrichment(private val graph: RegionGraph, private val projection: LocalProjection) {

    private val factory = GeometryFactory()

    fun apply(scenery: OsmScenery): SceneryStats {
        val shore = STRtree().apply {
            scenery.waterLines.forEach { line ->
                line.zipWithNext { a, b ->
                    val s = doubleArrayOf(projection.x(a.lon), projection.y(a.lat), projection.x(b.lon), projection.y(b.lat))
                    insert(Envelope(s[0], s[2], s[1], s[3]).also { it.expandBy(RIVERSIDE_M) }, s)
                }
            }
        }
        val green = polygons(scenery.greenAreas)
        val municipality = polygons(scenery.municipality)
        val views = STRtree().apply {
            scenery.viewpoints.forEach { v ->
                val x = projection.x(v.lon)
                val y = projection.y(v.lat)
                insert(Envelope(x, x, y, y).also { it.expandBy(VIEWPOINT_M) }, doubleArrayOf(x, y))
            }
        }

        graph.edges.forEach { e ->
            if (e.copyOf >= 0 || e.road == RoadKind.FERRY) return@forEach
            val samples = samples(e)
            e.riverside = samples.count { (x, y) -> nearShore(shore, x, y) } >= samples.size * MIN_SHARE
            e.green = samples.count { (x, y) -> inside(green, x, y) } >= samples.size * MIN_SHARE
            e.viewpoint = samples.any { (x, y) -> near(views, x, y) }
            e.lisbon = samples.count { (x, y) -> inside(municipality, x, y) } >= samples.size * MIN_SHARE
        }
        graph.edges.forEach { e ->
            if (e.copyOf < 0) return@forEach
            val original = graph.edges[e.copyOf]
            e.riverside = original.riverside
            e.green = original.green
            e.viewpoint = original.viewpoint
            e.lisbon = original.lisbon
        }
        val originals = graph.edges.filter { it.copyOf < 0 }
        return SceneryStats(
            riversideKm = originals.filter { it.riverside }.sumOf { it.length } / 1000,
            greenKm = originals.filter { it.green }.sumOf { it.length } / 1000,
            viewpointEdges = originals.count { it.viewpoint },
            lisbonKm = originals.filter { it.lisbon }.sumOf { it.length } / 1000,
        )
    }

    private fun polygons(rings: List<List<GeoPoint>>): STRtree = STRtree().apply {
        rings.forEach { ring ->
            val coordinates = ring.map { Coordinate(projection.x(it.lon), projection.y(it.lat)) }.toMutableList()
            if (coordinates.first() != coordinates.last()) coordinates += coordinates.first()
            val polygon = runCatching { factory.createPolygon(coordinates.toTypedArray()) }.getOrNull() ?: return@forEach
            insert(polygon.envelopeInternal, PreparedGeometryFactory.prepare(polygon))
        }
    }

    /** Points every [SAMPLE_M] along the edge, both ends included, in projected metres. */
    private fun samples(e: RegionEdge): List<Pair<Double, Double>> {
        val xs = e.points.map { projection.x(it.lon) }
        val ys = e.points.map { projection.y(it.lat) }
        val result = mutableListOf(xs[0] to ys[0])
        var carried = 0.0
        for (i in 0 until xs.size - 1) {
            val length = hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
            var at = SAMPLE_M - carried
            while (at < length) {
                val t = at / length
                result += (xs[i] + t * (xs[i + 1] - xs[i])) to (ys[i] + t * (ys[i + 1] - ys[i]))
                at += SAMPLE_M
            }
            carried = max(0.0, length - (at - SAMPLE_M))
        }
        result += xs.last() to ys.last()
        return result
    }

    private fun nearShore(index: STRtree, x: Double, y: Double): Boolean =
        index.query(Envelope(x, x, y, y)).any { s -> distanceToSegment(x, y, s as DoubleArray) <= RIVERSIDE_M }

    private fun inside(index: STRtree, x: Double, y: Double): Boolean {
        val point = factory.createPoint(Coordinate(x, y))
        return index.query(Envelope(x, x, y, y)).any { (it as PreparedGeometry).contains(point) }
    }

    private fun near(index: STRtree, x: Double, y: Double): Boolean =
        index.query(Envelope(x, x, y, y)).any { p -> (p as DoubleArray).let { hypot(it[0] - x, it[1] - y) <= VIEWPOINT_M } }

    companion object {
        /** Within sight of the water: the riverside promenade, not the avenue behind the docks. */
        const val RIVERSIDE_M = 70.0
        const val VIEWPOINT_M = 40.0
        const val SAMPLE_M = 10.0

        /** Share of an edge's samples that must qualify. */
        const val MIN_SHARE = 0.5

        fun distanceToSegment(x: Double, y: Double, s: DoubleArray): Double {
            val dx = s[2] - s[0]
            val dy = s[3] - s[1]
            val length2 = dx * dx + dy * dy
            val t = if (length2 > 0) (((x - s[0]) * dx + (y - s[1]) * dy) / length2).coerceIn(0.0, 1.0) else 0.0
            return hypot(x - (s[0] + t * dx), y - (s[1] + t * dy))
        }
    }
}
