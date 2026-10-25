package pt.lisbora.pipeline.graph

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.isMotorRoad
import pt.lisbora.routing.model.CycleInfraClass
import kotlin.math.max
import kotlin.math.min

/** One CML cycle-network line, as normalised by `fetch-cml`. */
data class CmlLine(
    val id: String,
    val cmlClass: CycleInfraClass,
    val name: String?,
    val contraflow: Boolean,
    val points: List<GeoPoint>,
) {
    companion object {
        /** Reads the features of `ciclovias.geojson` (LineString or MultiLineString). */
        fun fromFeatureCollection(collection: JsonObject): List<CmlLine> =
            collection["features"]!!.jsonArray.flatMap { feature ->
                val properties = feature.jsonObject["properties"]!!.jsonObject
                val geometry = feature.jsonObject["geometry"]!!.jsonObject
                val cmlClass = CycleInfraClass.fromId(properties["class"]!!.jsonPrimitive.content) ?: return@flatMap emptyList()
                val id = properties["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = properties["name"]?.jsonPrimitive?.contentOrNull
                val contraflow = properties["contraflow"]?.jsonPrimitive?.booleanOrNull ?: false
                val parts = when (geometry["type"]!!.jsonPrimitive.content) {
                    "LineString" -> listOf(geometry["coordinates"]!!.jsonArray)
                    "MultiLineString" -> geometry["coordinates"]!!.jsonArray.map { it.jsonArray }
                    else -> emptyList()
                }
                parts.map { coordinates -> CmlLine(id, cmlClass, name, contraflow, coordinates.map(::toPoint)) }
                    .filter { it.points.size >= 2 }
            }

        private fun toPoint(element: JsonElement): GeoPoint {
            val pair = element.jsonArray
            return GeoPoint(lat = pair[1].jsonPrimitive.double, lon = pair[0].jsonPrimitive.double)
        }
    }
}

/** A stretch of CML line that matched no edge. */
data class UnmatchedRun(val id: String, val cmlClass: CycleInfraClass, val name: String?, val points: List<GeoPoint>)

/** A CML contraflow lane on an OSM street that is still one-way for bikes. */
data class ContraflowConflict(val cmlId: String, val name: String?, val wayId: Long, val point: GeoPoint)

data class ConflationResult(
    /** CML length per class, and how much of it landed on edges that got that class. */
    val totalKmByClass: Map<CycleInfraClass, Double>,
    val matchedKmByClass: Map<CycleInfraClass, Double>,
    val edgesByClass: Map<CycleInfraClass, Int>,
    val unmatched: List<UnmatchedRun>,
    val contraflowConflicts: List<ContraflowConflict>,
) {
    val totalKm: Double get() = totalKmByClass.values.sum()
    val matchedKm: Double get() = matchedKmByClass.values.sum()
    val matchedShare: Double get() = if (totalKm > 0) matchedKm / totalKm else 0.0
}

/**
 * Edge-level conflation of the CML network onto the OSM graph.
 *
 * Each CML line is sampled every [spacingM]; each sample goes to the single best edge within [toleranceM]
 * whose direction differs by less than [maxBearingDeg] — "best" being the closest, with a bonus for the kind of
 * way that class usually maps to (cycleways for tracks, roads for painted lanes), so the road beside a separate
 * track is not marked. An edge takes the CML class covering at least [minCoverage] of its length.
 */
class CmlConflation(
    private val graph: RegionGraph,
    private val projection: LocalProjection,
    private val toleranceM: Double = 12.0,
    private val maxBearingDeg: Double = 30.0,
    private val spacingM: Double = 5.0,
    private val minCoverage: Double = 0.6,
) {
    /** A point on a CML line with the local line bearing and the length it stands for. */
    private class Sample(val x: Double, val y: Double, val point: GeoPoint, val bearing: Double, val weight: Double) {
        var edge: Int = -1
    }

    fun run(lines: List<CmlLine>): ConflationResult {
        val index = SegmentIndex(graph, projection) { true }
        val intervals = HashMap<Int, MutableMap<CycleInfraClass, MutableList<DoubleArray>>>()
        val conflicts = LinkedHashMap<Pair<String, Long>, ContraflowConflict>()
        val samplesByLine = lines.map { line -> line to sample(line) }

        samplesByLine.forEach { (line, samples) ->
            samples.forEach { s ->
                var best: EdgeSegment? = null
                var bestT = 0.0
                var bestScore = Double.MAX_VALUE
                index.near(s.x, s.y, toleranceM).forEach { segment ->
                    val (distance, t) = segment.project(s.x, s.y)
                    if (distance > toleranceM) return@forEach
                    if (EdgeSegment.bearingDifference(s.bearing, segment.bearing) > maxBearingDeg) return@forEach
                    val score = distance - bonus(line.cmlClass, graph.edges[segment.edge].road)
                    if (score < bestScore) { bestScore = score; best = segment; bestT = t }
                }
                val segment = best ?: return@forEach
                s.edge = segment.edge
                val center = segment.alongStart + bestT * segment.length
                intervals.getOrPut(segment.edge) { HashMap() }.getOrPut(line.cmlClass) { mutableListOf() } +=
                    doubleArrayOf(center - spacingM / 2, center + spacingM / 2)
                val edge = graph.edges[segment.edge]
                if (line.contraflow && (!edge.bikeForward || !edge.bikeBackward)) {
                    conflicts.putIfAbsent(line.id to edge.wayId, ContraflowConflict(line.id, line.name, edge.wayId, s.point))
                }
            }
        }

        val edgesByClass = HashMap<CycleInfraClass, Int>()
        intervals.forEach { (edgeIndex, byClass) ->
            val edge = graph.edges[edgeIndex]
            val length = edge.points.zipWithNext { p, q ->
                ElevationAssigner.distance(p.lat, p.lon, q.lat, q.lon)
            }.sum()
            val (cmlClass, covered) = byClass.mapValues { (_, list) -> coveredLength(list, length) }.maxBy { it.value }
            if (length > 0 && covered / length >= minCoverage) {
                edge.cmlClass = cmlClass
                edgesByClass.merge(cmlClass, 1, Int::plus)
            }
        }

        val total = HashMap<CycleInfraClass, Double>()
        val matched = HashMap<CycleInfraClass, Double>()
        val unmatched = mutableListOf<UnmatchedRun>()
        samplesByLine.forEach { (line, samples) ->
            var run = mutableListOf<GeoPoint>()
            samples.forEach { s ->
                total.merge(line.cmlClass, s.weight / 1000, Double::plus)
                val ok = s.edge >= 0 && graph.edges[s.edge].cmlClass == line.cmlClass
                if (ok) {
                    matched.merge(line.cmlClass, s.weight / 1000, Double::plus)
                    if (run.size >= 2) unmatched += UnmatchedRun(line.id, line.cmlClass, line.name, run)
                    run = mutableListOf()
                } else {
                    run += s.point
                }
            }
            if (run.size >= 2) unmatched += UnmatchedRun(line.id, line.cmlClass, line.name, run)
        }
        return ConflationResult(total, matched, edgesByClass, unmatched, conflicts.values.toList())
    }

    /** Points every [spacingM] along the line (at the middle of each step), with the local bearing. */
    private fun sample(line: CmlLine): List<Sample> {
        val xs = line.points.map { projection.x(it.lon) }
        val ys = line.points.map { projection.y(it.lat) }
        val segments = (0 until xs.size - 1).map { i -> EdgeSegment(-1, xs[i], ys[i], xs[i + 1], ys[i + 1], 0.0) }
        val total = segments.sumOf { it.length }
        if (total == 0.0) return emptyList()
        val count = max(1, Math.round(total / spacingM).toInt())
        val step = total / count
        val result = ArrayList<Sample>(count)
        var segment = 0
        var segmentStart = 0.0
        for (k in 0 until count) {
            val distance = (k + 0.5) * step
            while (segment < segments.size - 1 && segmentStart + segments[segment].length < distance) {
                segmentStart += segments[segment].length
                segment++
            }
            val s = segments[segment]
            val t = if (s.length > 0) ((distance - segmentStart) / s.length).coerceIn(0.0, 1.0) else 0.0
            val x = s.ax + t * (s.bx - s.ax)
            val y = s.ay + t * (s.by - s.ay)
            val point = GeoPoint(projection.lat(y), projection.lon(x))
            result += Sample(x, y, point, s.bearing, step)
        }
        return result
    }

    /** Metres of a stronger match a kind of way gets for a CML class (subtracted from the distance). */
    private fun bonus(cmlClass: CycleInfraClass, road: RoadKind): Double = when (cmlClass) {
        CycleInfraClass.SEGREGATED_TRACK -> when (road) {
            RoadKind.CYCLEWAY -> 4.0
            RoadKind.PATH, RoadKind.FOOTWAY, RoadKind.PEDESTRIAN -> 1.0
            else -> 0.0
        }
        CycleInfraClass.SHARED_WITH_PEDESTRIANS -> when (road) {
            RoadKind.CYCLEWAY, RoadKind.PATH, RoadKind.FOOTWAY, RoadKind.PEDESTRIAN, RoadKind.TRACK -> 3.0
            else -> 0.0
        }
        CycleInfraClass.TRAIL -> when (road) {
            RoadKind.TRACK, RoadKind.PATH, RoadKind.CYCLEWAY, RoadKind.BRIDLEWAY -> 3.0
            else -> 0.0
        }
        CycleInfraClass.PAINTED_LANE, CycleInfraClass.SHARED_STREET_30 -> when {
            road.isMotorRoad() || road == RoadKind.LIVING_STREET -> 3.0
            road == RoadKind.FOOTWAY || road == RoadKind.STEPS -> -3.0
            else -> 0.0
        }
    }

    companion object {
        /** Total length covered by possibly overlapping [intervals], clipped to [0, length]. */
        fun coveredLength(intervals: List<DoubleArray>, length: Double): Double {
            val sorted = intervals.map { doubleArrayOf(max(0.0, it[0]), min(length, it[1])) }
                .filter { it[1] > it[0] }
                .sortedBy { it[0] }
            var covered = 0.0
            var start = Double.NaN
            var end = Double.NaN
            sorted.forEach { (a, b) ->
                if (start.isNaN() || a > end) {
                    if (!start.isNaN()) covered += end - start
                    start = a
                    end = b
                } else {
                    end = max(end, b)
                }
            }
            if (!start.isNaN()) covered += end - start
            return covered
        }
    }
}
