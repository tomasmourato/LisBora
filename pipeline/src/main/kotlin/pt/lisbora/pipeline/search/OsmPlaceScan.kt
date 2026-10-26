package pt.lisbora.pipeline.search

import com.carrotsearch.hppc.LongHashSet
import com.carrotsearch.hppc.LongLongHashMap
import com.carrotsearch.hppc.LongObjectHashMap
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderRelation
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMInputFile
import com.graphhopper.reader.osm.SkipOptions
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphData
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.routing.search.SearchText
import java.io.PrintStream
import java.nio.file.Path

/** One OSM way of a named street, inside the region. */
class StreetWay(val name: String, val highway: String, val nodeIds: LongArray, val points: List<GeoPoint>)

data class OsmPoi(
    val name: String,
    val category: PlaceCategory,
    val point: GeoPoint,
    val importance: Int,
    val altNames: List<String> = emptyList(),
)

data class OsmAddress(val street: String, val number: String, val point: GeoPoint)

/** A `place=*` node (neighbourhood, town…). */
data class OsmPlaceNode(val name: String, val place: String, val point: GeoPoint)

/** An administrative boundary (7 = município, 8 = freguesia) as closed outer rings. */
data class AdminArea(val name: String, val level: Int, val rings: List<List<GeoPoint>>)

data class OsmPlaces(
    val streets: List<StreetWay>,
    val pois: List<OsmPoi>,
    val addresses: List<OsmAddress>,
    val places: List<OsmPlaceNode>,
    val adminAreas: List<AdminArea>,
    /** Timestamp of the extract (PBF header), when known. */
    val dataDate: String?,
)

/**
 * Reads everything searchable inside [bounds] from the OSM extract, in four passes:
 * nodes (coordinates near the region, plus point features), relations (boundaries and multipolygons),
 * ways (streets, areas, member ways) and nodes again (the far-away parts of the boundaries that touch the region).
 */
class OsmPlaceScan(
    private val osmFile: Path,
    private val bounds: GeoBounds,
    private val out: PrintStream,
    /** Prints the tags of every element whose name contains this text (for checking how a place is mapped). */
    private val showName: String? = null,
) {
    private val showKey = showName?.let(SearchText::normalize)

    private val near = GeoBounds(bounds.west - MARGIN_DEG, bounds.south - MARGIN_DEG, bounds.east + MARGIN_DEG, bounds.north + MARGIN_DEG)

    /** Packed E7 coordinates by node id (see [pack]). */
    private val coordinates = LongLongHashMap()

    private class RelationInfo(
        val name: String,
        val adminLevel: Int?,
        val category: PlaceCategory?,
        val tags: ReaderElement,
        val outerWays: LongArray,
    )

    fun scan(): OsmPlaces {
        val pois = mutableListOf<OsmPoi>()
        val addresses = mutableListOf<OsmAddress>()
        val places = mutableListOf<OsmPlaceNode>()
        var dataDate: String? = null

        // 1. Nodes: coordinates near the region, and named point features inside it.
        read(SkipOptions(false, true, true)) { element ->
            if (element.type == ReaderElement.Type.FILEHEADER) dataDate = element.getTag("timestamp")
            val node = element as? ReaderNode ?: return@read
            val point = node.lat to node.lon
            if (!near.containsRaw(point)) return@read
            coordinates.put(node.id, pack(node.lat, node.lon))
            if (!node.hasTags() || !bounds.containsRaw(point)) return@read
            val geo = GeoPoint(node.lat, node.lon)
            collectFeature(node, geo, 0.0, pois, addresses)
            val name = PlaceTags.name(node)
            val place = node.getTag("place")
            if (name != null && place != null && place in PlaceTags.PLACES) places += OsmPlaceNode(name, place, geo)
        }
        out.println("OSM: ${coordinates.size()} nós perto da região")

        // 2. Relations: administrative boundaries and named multipolygon areas (parks, campuses…).
        val relations = mutableListOf<RelationInfo>()
        read(SkipOptions(true, true, false)) { element ->
            val relation = element as? ReaderRelation ?: return@read
            val name = PlaceTags.name(relation) ?: return@read
            val outer = relation.members
                .filter { it.type == ReaderElement.Type.WAY && (it.role == "outer" || it.role.isEmpty()) }
                .map { it.ref }.toLongArray()
            if (outer.isEmpty()) return@read
            val level = relation.getTag("admin_level")?.toIntOrNull()
            if (relation.hasTag("boundary", "administrative") && level in ADMIN_LEVELS) {
                relations += RelationInfo(name, level, null, relation, outer)
            } else if (relation.hasTag("type", "multipolygon")) {
                PlaceTags.category(relation)?.let { relations += RelationInfo(name, null, it, relation, outer) }
            }
        }
        val memberWays = LongHashSet()
        relations.forEach { r -> r.outerWays.forEach { memberWays.add(it) } }

        // 3. Ways: streets, areas and addresses inside the region; node lists of relation members.
        val streets = mutableListOf<StreetWay>()
        val memberNodes = LongObjectHashMap<LongArray>()
        read(SkipOptions(true, false, true)) { element ->
            val way = element as? ReaderWay ?: return@read
            val ids = LongArray(way.nodes.size()) { way.nodes.get(it) }
            if (memberWays.contains(way.id)) memberNodes.put(way.id, ids)
            if (!way.hasTags()) return@read
            val points = ids.asList().mapNotNull(::point)
            if (points.isEmpty() || points.none { it in bounds }) return@read
            if (PlaceTags.isSearchableStreet(way)) {
                streets += StreetWay(PlaceTags.name(way)!!, way.getTag("highway"), ids, points)
                return@read
            }
            val closed = ids.size >= 4 && ids.first() == ids.last()
            if (points.size * 2 < ids.size) return@read // mostly outside the stored area
            val centre = centroid(points)
            if (centre in bounds) collectFeature(way, centre, if (closed) Geometry.areaM2(points) else 0.0, pois, addresses)
        }

        // 4. Nodes again: the rest of every boundary that touches the region.
        val touching = relations.filter { r -> r.outerWays.any { w -> memberNodes.get(w)?.any { coordinates.containsKey(it) } == true } }
        val missing = LongHashSet()
        touching.forEach { r -> r.outerWays.forEach { w -> memberNodes.get(w)?.forEach { if (!coordinates.containsKey(it)) missing.add(it) } } }
        if (!missing.isEmpty) {
            read(SkipOptions(false, true, true)) { element ->
                val node = element as? ReaderNode ?: return@read
                if (missing.contains(node.id)) coordinates.put(node.id, pack(node.lat, node.lon))
            }
        }

        val adminAreas = mutableListOf<AdminArea>()
        touching.forEach { r ->
            val ways = r.outerWays.asList().mapNotNull { memberNodes.get(it) }
            val rings = RingAssembler.assemble(ways).map { ring -> ring.asList().mapNotNull(::point) }.filter { it.size >= 4 }
            if (rings.isEmpty()) return@forEach
            if (r.adminLevel != null) {
                adminAreas += AdminArea(r.name, r.adminLevel, rings)
            } else if (r.category != null) {
                val outer = rings.maxBy { Geometry.areaM2(it) }
                val centre = centroid(outer)
                if (centre in bounds) {
                    val importance = PlaceTags.importance(r.tags, r.category, rings.sumOf { Geometry.areaM2(it) })
                    pois += OsmPoi(r.name, r.category, centre, importance, PlaceTags.altNames(r.tags))
                }
            }
        }
        out.println(
            "OSM: ${streets.size} troços de ruas, ${pois.size} pontos de interesse, ${addresses.size} moradas, " +
                "${places.size} localidades, ${adminAreas.count { it.level == 7 }} concelhos e ${adminAreas.count { it.level == 8 }} freguesias",
        )
        return OsmPlaces(streets, pois, addresses, places, adminAreas, dataDate)
    }

    private fun collectFeature(e: ReaderElement, point: GeoPoint, areaM2: Double, pois: MutableList<OsmPoi>, addresses: MutableList<OsmAddress>) {
        val name = PlaceTags.name(e)
        if (name != null && !e.hasTag("place")) {
            PlaceTags.category(e)?.let { pois += OsmPoi(name, it, point, PlaceTags.importance(e, it, areaM2), PlaceTags.altNames(e)) }
        }
        val street = e.getTag("addr:street")?.trim()
        val number = e.getTag("addr:housenumber")?.trim()
        if (!street.isNullOrEmpty() && !number.isNullOrEmpty()) addresses += OsmAddress(street, number, point)
    }

    private fun point(id: Long): GeoPoint? {
        if (!coordinates.containsKey(id)) return null
        val packed = coordinates.get(id)
        return GeoPoint((packed shr 32).toInt() / GraphData.E7, packed.toInt() / GraphData.E7)
    }

    private fun read(skip: SkipOptions, consumer: (ReaderElement) -> Unit) {
        OSMInputFile(osmFile.toFile()).setWorkerThreads(WORKER_THREADS).setSkipOptions(skip).open().use { input ->
            while (true) {
                val element = input.next ?: break
                if (showKey != null) show(element)
                consumer(element)
            }
        }
    }

    private fun show(element: ReaderElement) {
        val name = PlaceTags.name(element) ?: return
        if (!SearchText.normalize(name).contains(showKey!!)) return
        if (element is ReaderNode && !bounds.containsRaw(element.lat to element.lon)) return
        out.println("  ${element.type} ${element.id}: ${element.tags}")
    }

    private fun GeoBounds.containsRaw(p: Pair<Double, Double>) = p.first in south..north && p.second in west..east

    companion object {
        private const val WORKER_THREADS = 2
        private const val MARGIN_DEG = 0.01
        private val ADMIN_LEVELS = setOf(7, 8)

        private fun pack(lat: Double, lon: Double): Long =
            (GraphData.toE7(lat).toLong() shl 32) or (GraphData.toE7(lon).toLong() and 0xffffffffL)

        /** Mean of the distinct points (a closed ring repeats its first point). */
        fun centroid(points: List<GeoPoint>): GeoPoint {
            val distinct = if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points
            return GeoPoint(distinct.sumOf { it.lat } / distinct.size, distinct.sumOf { it.lon } / distinct.size)
        }
    }
}
