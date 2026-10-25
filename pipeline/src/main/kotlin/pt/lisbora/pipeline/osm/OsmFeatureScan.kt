package pt.lisbora.pipeline.osm

import com.carrotsearch.hppc.LongHashSet
import com.carrotsearch.hppc.LongObjectHashMap
import com.graphhopper.reader.ReaderElement
import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import com.graphhopper.reader.osm.OSMInputFile
import com.graphhopper.reader.osm.SkipOptions
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import java.io.PrintStream
import java.nio.file.Path

/** OSM features the GraphHopper bike import does not keep but LisBora needs, limited to the region. */
data class OsmFeatures(
    /** Tram track centrelines (`railway=tram`). */
    val tramLines: List<List<GeoPoint>>,
    /** Roads whose own way carries tram rails (`embedded_rails=tram` or `railway=tram` on a highway). */
    val tramRoadWayIds: Set<Long>,
    /** Roads with a cycle lane or track mapped as a tag of the road (`cycleway*=lane|track`). */
    val cycleLaneWayIds: Set<Long>,
    /** Traffic lights (`highway=traffic_signals`, `crossing=traffic_signals`). */
    val trafficSignals: List<GeoPoint>,
)

/**
 * Two passes over the OSM extract: ways first (to learn which nodes the tram lines need),
 * then nodes (coordinates and traffic lights). Nodes outside [bounds] are ignored.
 */
class OsmFeatureScan(private val osmFile: Path, private val bounds: GeoBounds, private val out: PrintStream) {

    fun scan(): OsmFeatures {
        val tramWays = mutableListOf<LongArray>()
        val tramRoads = HashSet<Long>()
        val cycleLanes = HashSet<Long>()
        read(SkipOptions(true, false, true)) { element ->
            val way = element as? ReaderWay ?: return@read
            if (isTramTrack(way)) tramWays += LongArray(way.nodes.size()) { way.nodes.get(it) }
            if (way.hasTag("highway")) {
                if (hasTramRails(way)) tramRoads += way.id
                if (hasCycleLane(way)) cycleLanes += way.id
            }
        }

        val wanted = LongHashSet()
        tramWays.forEach { ids -> ids.forEach { wanted.add(it) } }
        val coordinates = LongObjectHashMap<GeoPoint>()
        val signals = mutableListOf<GeoPoint>()
        read(SkipOptions(false, true, true)) { element ->
            val node = element as? ReaderNode ?: return@read
            if (node.lat !in bounds.south..bounds.north || node.lon !in bounds.west..bounds.east) return@read
            val point = GeoPoint(node.lat, node.lon)
            if (wanted.contains(node.id)) coordinates.put(node.id, point)
            if (node.hasTag("highway", "traffic_signals") || node.hasTag("crossing", "traffic_signals")) signals += point
        }

        // Keep the in-region runs of each tram way (a way may leave the region).
        val tramLines = tramWays.flatMap { ids ->
            val runs = mutableListOf<MutableList<GeoPoint>>()
            var current = mutableListOf<GeoPoint>()
            ids.forEach { id ->
                val point = coordinates.get(id)
                if (point != null) current += point
                else if (current.isNotEmpty()) { runs += current; current = mutableListOf() }
            }
            if (current.isNotEmpty()) runs += current
            runs.filter { it.size >= 2 }
        }
        out.println("OSM: ${tramLines.size} troços de carris, ${tramRoads.size} vias com carris, " +
            "${cycleLanes.size} vias com ciclofaixa, ${signals.size} semáforos")
        return OsmFeatures(tramLines, tramRoads, cycleLanes, signals)
    }

    private fun read(skip: SkipOptions, consumer: (ReaderElement) -> Unit) {
        OSMInputFile(osmFile.toFile()).setWorkerThreads(WORKER_THREADS).setSkipOptions(skip).open().use { input ->
            while (true) consumer(input.next ?: break)
        }
    }

    companion object {
        private const val WORKER_THREADS = 2
        private val CYCLE_LANE_KEYS = listOf("cycleway", "cycleway:left", "cycleway:right", "cycleway:both")
        private val CYCLE_LANE_VALUES = setOf("lane", "track", "opposite_lane", "opposite_track")
        private val INACTIVE = listOf("abandoned", "disused", "construction", "proposed", "razed")

        fun isTramTrack(way: ReaderWay): Boolean =
            way.hasTag("railway", "tram") && INACTIVE.none { way.hasTag(it) || way.hasTag("$it:railway") }

        fun hasTramRails(way: ReaderWay): Boolean =
            way.hasTag("railway", "tram") || way.getTag("embedded_rails", "").split(";").any { it.trim() == "tram" }

        fun hasCycleLane(way: ReaderWay): Boolean =
            !way.hasTag("highway", "cycleway") && CYCLE_LANE_KEYS.any { way.getTag(it, "") in CYCLE_LANE_VALUES }
    }
}
