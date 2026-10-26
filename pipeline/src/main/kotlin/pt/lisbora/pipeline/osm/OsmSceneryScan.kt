package pt.lisbora.pipeline.osm

import com.carrotsearch.hppc.LongLongHashMap
import com.carrotsearch.hppc.LongObjectHashMap
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderRelation
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMInputFile
import com.graphhopper.reader.osm.SkipOptions
import pt.lisbora.pipeline.search.Geometry
import pt.lisbora.pipeline.search.RingAssembler
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphData
import java.io.PrintStream
import java.nio.file.Path

/** What makes a street scenic, from OSM, limited to the region. */
data class OsmScenery(
    /** Shorelines: the coastline (the Tagus estuary is "sea" in OSM) and the outlines of docks and lakes. */
    val waterLines: List<List<GeoPoint>>,
    /** Parks, gardens and woods, as closed outer rings. */
    val greenAreas: List<List<GeoPoint>>,
    /** `tourism=viewpoint` ("miradouros"). */
    val viewpoints: List<GeoPoint>,
    /** Outer rings of the municipality of Lisbon (GIRA bikes may not leave it). */
    val municipality: List<List<GeoPoint>> = emptyList(),
)

/**
 * Three passes over the OSM extract: nodes near the region (coordinates and viewpoints), relations
 * (water and green multipolygons, the municipality of Lisbon) and ways (coastline, closed areas and the
 * members of those relations).
 */
class OsmSceneryScan(private val osmFile: Path, private val bounds: GeoBounds, private val out: PrintStream) {

    private val near = GeoBounds(bounds.west - MARGIN_DEG, bounds.south - MARGIN_DEG, bounds.east + MARGIN_DEG, bounds.north + MARGIN_DEG)
    private val coordinates = LongLongHashMap()

    private class AreaRelation(val water: Boolean, val greenMinArea: Double?, val outerWays: LongArray, val municipality: Boolean = false)

    fun scan(): OsmScenery {
        val viewpoints = mutableListOf<GeoPoint>()
        read(SkipOptions(false, true, true)) { element ->
            val node = element as? ReaderNode ?: return@read
            if (node.lat !in near.south..near.north || node.lon !in near.west..near.east) return@read
            coordinates.put(node.id, (GraphData.toE7(node.lat).toLong() shl 32) or (GraphData.toE7(node.lon).toLong() and 0xffffffffL))
            if (node.hasTag("tourism", "viewpoint") && GeoPoint(node.lat, node.lon) in bounds) viewpoints += GeoPoint(node.lat, node.lon)
        }

        val relations = mutableListOf<AreaRelation>()
        read(SkipOptions(true, true, false)) { element ->
            val relation = element as? ReaderRelation ?: return@read
            val municipality = isLisbonMunicipality(relation)
            if (!municipality && !relation.hasTag("type", "multipolygon")) return@read
            val water = !municipality && isWater(relation)
            val green = if (municipality) null else greenMinArea(relation)
            if (!municipality && !water && green == null) return@read
            val outer = relation.members
                .filter { it.type == ReaderElement.Type.WAY && (it.role == "outer" || it.role.isEmpty()) }
                .map { it.ref }.toLongArray()
            if (outer.isNotEmpty()) relations += AreaRelation(water, green, outer, municipality)
        }
        val members = LongObjectHashMap<LongArray>()
        relations.forEach { r -> r.outerWays.forEach { members.put(it, LongArray(0)) } }

        val waterLines = mutableListOf<List<GeoPoint>>()
        val greenAreas = mutableListOf<List<GeoPoint>>()
        val municipality = mutableListOf<List<GeoPoint>>()
        read(SkipOptions(true, false, true)) { element ->
            val way = element as? ReaderWay ?: return@read
            val ids = LongArray(way.nodes.size()) { way.nodes.get(it) }
            if (members.containsKey(way.id)) members.put(way.id, ids)
            if (!way.hasTags() || ids.none { coordinates.containsKey(it) }) return@read
            val closed = ids.size >= 4 && ids.first() == ids.last()
            if (way.hasTag("natural", "coastline")) waterLines += runs(ids)
            if (closed && isWater(way)) ring(ids)?.takeIf(::isWideWater)?.let { waterLines += it }
            val minArea = greenMinArea(way)
            if (minArea != null && closed) ring(ids)?.takeIf { Geometry.areaM2(it) >= minArea }?.let { greenAreas += it }
        }

        relations.forEach { r ->
            val ways = r.outerWays.asList().mapNotNull { id -> members.get(id)?.takeIf { it.isNotEmpty() } }
            if (r.municipality) municipality += rings(ways)
            if (r.water) rings(ways).filter(::isWideWater).forEach { waterLines += it }
            if (r.greenMinArea != null) {
                val rings = rings(ways)
                if (rings.sumOf { Geometry.areaM2(it) } >= r.greenMinArea) greenAreas += rings
            }
        }
        out.println(
            "OSM: ${waterLines.size} linhas de margem, ${greenAreas.size} áreas verdes, ${viewpoints.size} miradouros, " +
                "concelho de Lisboa em ${municipality.size} anel(éis)",
        )
        return OsmScenery(waterLines, greenAreas, viewpoints, municipality)
    }

    /** Complete rings assembled from member ways whose nodes are all known. */
    private fun rings(ways: List<LongArray>): List<List<GeoPoint>> =
        RingAssembler.assemble(ways).mapNotNull { ring(it) }

    /** The ring of [ids] when every node is known, else null. */
    private fun ring(ids: LongArray): List<GeoPoint>? {
        val points = ids.map { point(it) ?: return null }
        return points.takeIf { it.size >= 4 }
    }

    /** The known stretches of a way (a way may leave the stored area). */
    private fun runs(ids: LongArray): List<List<GeoPoint>> {
        val runs = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        ids.forEach { id ->
            val p = point(id)
            if (p != null) current += p
            else if (current.isNotEmpty()) { runs += current; current = mutableListOf() }
        }
        if (current.isNotEmpty()) runs += current
        return runs.filter { it.size >= 2 }
    }

    private fun point(id: Long): GeoPoint? {
        if (!coordinates.containsKey(id)) return null
        val packed = coordinates.get(id)
        return GeoPoint((packed shr 32).toInt() / GraphData.E7, packed.toInt() / GraphData.E7)
    }

    private fun read(skip: SkipOptions, consumer: (ReaderElement) -> Unit) {
        OSMInputFile(osmFile.toFile()).setWorkerThreads(WORKER_THREADS).setSkipOptions(skip).open().use { input ->
            while (true) consumer(input.next ?: break)
        }
    }

    companion object {
        private const val WORKER_THREADS = 2
        private const val MARGIN_DEG = 0.01

        /** Ponds and fountains are not "the river": water areas count from this size... */
        const val MIN_WATER_AREA_M2 = 40_000.0

        /** ...and when wide (area over perimeter, about half the width): docks and lakes, not streams. */
        const val MIN_WATER_AREA_PER_PERIMETER_M = 25.0

        private val PRIVATE = setOf("private", "no")

        /** The boundary relation of the municipality of Lisbon (`admin_level=7`). */
        fun isLisbonMunicipality(e: ReaderElement): Boolean =
            e.hasTag("boundary", "administrative") && e.hasTag("admin_level", "7") && e.getTag("name") == LISBON

        private const val LISBON = "Lisboa"

        private fun isWater(e: ReaderElement): Boolean =
            e.hasTag("natural", "water") || e.hasTag("waterway", "riverbank") || e.hasTag("natural", "bay") ||
                e.hasTag("natural", "strait") || e.hasTag("leisure", "marina")

        private fun isWideWater(ring: List<GeoPoint>): Boolean {
            val area = Geometry.areaM2(ring)
            return area >= MIN_WATER_AREA_M2 && area / Geometry.lengthM(ring) >= MIN_WATER_AREA_PER_PERIMETER_M
        }

        /** Smallest area (m²) for a green feature to count, or null when it is not green. */
        fun greenMinArea(e: ReaderElement): Double? {
            if (e.getTag("access") in PRIVATE) return null
            val leisure = e.getTag("leisure")
            val landuse = e.getTag("landuse")
            val natural = e.getTag("natural")
            return when {
                leisure in setOf("park", "garden", "nature_reserve") -> 2_000.0
                landuse in setOf("forest", "recreation_ground", "village_green") || natural == "wood" -> 5_000.0
                landuse in setOf("grass", "meadow") || natural in setOf("scrub", "grassland", "heath") -> 20_000.0
                else -> null
            }
        }
    }
}
