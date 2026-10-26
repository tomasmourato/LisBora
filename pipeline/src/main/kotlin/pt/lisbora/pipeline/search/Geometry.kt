package pt.lisbora.pipeline.search

import pt.lisbora.routing.geo.GeoPoint
import kotlin.math.abs
import kotlin.math.cos

/** Small planar helpers at city scale (equirectangular around the first point). */
object Geometry {
    private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M

    /** Area of a ring in m² (shoelace formula); 0 for fewer than three points. */
    fun areaM2(ring: List<GeoPoint>): Double {
        if (ring.size < 3) return 0.0
        val cosLat = cos(Math.toRadians(ring.first().lat))
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.lon * cosLat * b.lat - b.lon * cosLat * a.lat
        }
        return abs(sum) / 2 * METRES_PER_DEGREE * METRES_PER_DEGREE
    }

    /** Length of a polyline in metres. */
    fun lengthM(points: List<GeoPoint>): Double = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()
}

/** Joins the member ways of a boundary relation (node id lists) into closed rings. */
object RingAssembler {

    /** Closed rings, each a node id list whose first and last ids are equal. Open leftovers are dropped. */
    fun assemble(ways: List<LongArray>): List<LongArray> {
        val remaining = ways.filter { it.size >= 2 }.toMutableList()
        val rings = mutableListOf<LongArray>()
        while (remaining.isNotEmpty()) {
            val ring = remaining.removeAt(0).toMutableList()
            while (ring.first() != ring.last()) {
                val tail = ring.last()
                val next = remaining.indexOfFirst { it.first() == tail || it.last() == tail }
                if (next < 0) break
                val way = remaining.removeAt(next)
                val ordered = if (way.first() == tail) way.asList() else way.reversed()
                ring += ordered.drop(1)
            }
            if (ring.size >= 4 && ring.first() == ring.last()) rings += ring.toLongArray()
        }
        return rings
    }
}
