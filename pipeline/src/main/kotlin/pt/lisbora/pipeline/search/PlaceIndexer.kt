package pt.lisbora.pipeline.search

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.index.strtree.STRtree
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.routing.search.SearchIndexBuilder
import pt.lisbora.routing.search.SearchText
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How many places of each kind went into the index. */
data class IndexStats(val streets: Int, val pois: Int, val addresses: Int, val localities: Int, val adminAreas: Int, val contexts: Int)

/**
 * Turns what [OsmPlaceScan] read into search entries: streets merged per name and neighbourhood,
 * points of interest, addresses ("Rua Augusta, 24"), localities and parishes, each with a context
 * ("Arroios, Lisboa") from the administrative boundaries.
 */
class PlaceIndexer(private val bounds: GeoBounds) {

    fun index(places: OsmPlaces, builder: SearchIndexBuilder = SearchIndexBuilder()): Pair<SearchIndexBuilder, IndexStats> {
        val admin = AdminLookup(places.adminAreas)

        val clusters = StreetClusters.cluster(places.streets)
        clusters.filter { it.point in bounds }.forEach { c ->
            builder.add(c.name, PlaceCategory.STREET, c.point, admin.context(c.point), c.importance)
        }

        places.pois.forEach { p -> builder.add(p.name, p.category, p.point, admin.context(p.point), p.importance, p.altNames) }

        val addresses = places.addresses.distinctBy { Triple(it.street, it.number, it.point) }
        addresses.forEach { a -> builder.add("${a.street}, ${a.number}", PlaceCategory.ADDRESS, a.point, admin.context(a.point)) }

        places.places.forEach { p ->
            val (category, importance) = PlaceTags.PLACES.getValue(p.place)
            builder.add(p.name, category, p.point, admin.context(p.point, exclude = p.name), importance)
        }

        // Parishes and municipalities, unless a place node with the same name already lies inside.
        var adminEntries = 0
        admin.areas.forEach { area ->
            val name = area.shortName
            val key = SearchText.normalize(name)
            val hasPlace = places.places.any { SearchText.normalize(it.name) == key && area.contains(it.point) }
            if (hasPlace) return@forEach
            val point = area.labelPoint() ?: return@forEach
            if (point !in bounds) return@forEach
            val (category, importance) = if (area.level == 7) PlaceCategory.LOCALITY to 90 else PlaceCategory.NEIGHBOURHOOD to 60
            builder.add(name, category, point, admin.context(point, exclude = name), importance)
            adminEntries++
        }
        val stats = IndexStats(
            streets = clusters.count { it.point in bounds },
            pois = places.pois.size,
            addresses = addresses.size,
            localities = places.places.size,
            adminAreas = adminEntries,
            contexts = admin.areas.size,
        )
        return builder to stats
    }
}

/** Streets merged by name: ways of the same name that touch or run within [JOIN_M] of each other. */
object StreetClusters {

    data class Cluster(val name: String, val lengthM: Double, val point: GeoPoint, val importance: Int)

    const val JOIN_M = 120.0

    fun cluster(streets: List<StreetWay>): List<Cluster> =
        streets.groupBy { SearchText.normalize(it.name) }.values.flatMap { group -> clusterGroup(group) }

    private fun clusterGroup(ways: List<StreetWay>): List<Cluster> {
        val parent = IntArray(ways.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in ways.indices) for (j in i + 1 until ways.size) {
            if (find(i) != find(j) && near(ways[i], ways[j])) parent[find(i)] = find(j)
        }
        return ways.indices.groupBy(::find).values.map { members -> summarise(members.map { ways[it] }) }
    }

    private fun near(a: StreetWay, b: StreetWay): Boolean {
        if (a.nodeIds.any { id -> b.nodeIds.contains(id) }) return true
        val reach = JOIN_M / METRES_PER_DEGREE
        val boxA = box(a.points)
        val boxB = box(b.points)
        if (boxA[0] - reach > boxB[2] || boxB[0] - reach > boxA[2] || boxA[1] - reach * 1.3 > boxB[3] || boxB[1] - reach * 1.3 > boxA[3]) {
            return false
        }
        return a.points.any { p -> b.points.any { q -> planar(p, q) <= JOIN_M } }
    }

    /** [south, west, north, east] */
    private fun box(points: List<GeoPoint>) = doubleArrayOf(
        points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon },
    )

    private fun summarise(ways: List<StreetWay>): Cluster {
        var length = 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        ways.forEach { w ->
            w.points.zipWithNext { a, b ->
                val l = planar(a, b)
                length += l
                sumLat += (a.lat + b.lat) / 2 * l
                sumLon += (a.lon + b.lon) / 2 * l
            }
        }
        val all = ways.flatMap { it.points }
        // The way point closest to the length-weighted centre: always on the street itself.
        val point = if (length > 0) {
            val centre = GeoPoint(sumLat / length, sumLon / length)
            all.minBy { planar(it, centre) }
        } else all.first()
        val name = ways.groupingBy { it.name }.eachCount().maxBy { it.value }.key
        val bonus = ways.maxOf { PlaceTags.streetClassBonus(it.highway) }
        val importance = (15 * log2(1 + length / 100) + bonus).roundToInt().coerceIn(0, 100)
        return Cluster(name, length, point, importance)
    }

    private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M

    private fun planar(a: GeoPoint, b: GeoPoint): Double {
        val dy = (b.lat - a.lat) * METRES_PER_DEGREE
        val dx = (b.lon - a.lon) * METRES_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) / 2))
        return sqrt(dx * dx + dy * dy)
    }
}

/** Point-in-polygon lookups over parishes (level 8) and municipalities (level 7). */
class AdminLookup(adminAreas: List<AdminArea>) {

    inner class Area(val name: String, val level: Int, private val rings: List<List<GeoPoint>>) {
        private val geometries: List<PreparedGeometry> = rings.map { ring ->
            PreparedGeometryFactory.prepare(factory.createPolygon(ring.map { Coordinate(it.lon, it.lat) }.toTypedArray()))
        }
        val envelope: Envelope = Envelope().also { e -> rings.flatten().forEach { e.expandToInclude(it.lon, it.lat) } }
        val areaM2: Double = rings.sumOf { Geometry.areaM2(it) }

        /** The name without the "União das Freguesias de" prefix of merged parishes. */
        val shortName: String = UNION_PREFIXES.fold(name) { n, prefix -> if (n.startsWith(prefix, ignoreCase = true)) n.substring(prefix.length) else n }

        fun contains(point: GeoPoint): Boolean {
            val p = factory.createPoint(Coordinate(point.lon, point.lat))
            return geometries.any { it.contains(p) }
        }

        /** A point guaranteed to be inside the largest ring. */
        fun labelPoint(): GeoPoint? {
            val largest = rings.maxByOrNull { Geometry.areaM2(it) } ?: return null
            val polygon = factory.createPolygon(largest.map { Coordinate(it.lon, it.lat) }.toTypedArray())
            val interior = polygon.interiorPoint.coordinate ?: return null
            return GeoPoint(interior.y, interior.x)
        }
    }

    private val factory = GeometryFactory()
    val areas: List<Area> = adminAreas.mapNotNull { a ->
        runCatching { Area(a.name, a.level, a.rings) }.getOrNull()
    }
    private val tree = STRtree().also { tree -> areas.forEach { tree.insert(it.envelope, it) } }

    /** "Parish, Municipality" for [point] (either may be missing), leaving out a name equal to [exclude]. */
    fun context(point: GeoPoint, exclude: String? = null): String? {
        @Suppress("UNCHECKED_CAST")
        val hits = (tree.query(Envelope(Coordinate(point.lon, point.lat))) as List<Area>).filter { it.contains(point) }
        val parish = hits.filter { it.level == 8 }.minByOrNull { it.areaM2 }?.shortName
        val municipality = hits.filter { it.level == 7 }.minByOrNull { it.areaM2 }?.name
        return listOfNotNull(parish, municipality)
            .filterNot { exclude != null && SearchText.normalize(it) == SearchText.normalize(exclude) }
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }
    }

    companion object {
        private val UNION_PREFIXES = listOf("União das Freguesias de ", "União de Freguesias de ", "União das Freguesias do ", "União das Freguesias da ")
    }
}
