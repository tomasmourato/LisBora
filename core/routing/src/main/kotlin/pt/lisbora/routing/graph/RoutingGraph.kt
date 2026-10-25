package pt.lisbora.routing.graph

import pt.lisbora.routing.geo.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A loaded routing graph: the stored [GraphData] plus the derived indexes the router needs
 * (adjacency, per-segment lengths and grades, bearings, turn restriction lookup).
 *
 * Directed edges are addressed by an *edge key*: `edge * 2` travels base→adj, `edge * 2 + 1` adj→base.
 */
class RoutingGraph(val data: GraphData) {

    val nodeCount: Int get() = data.nodeCount
    val edgeCount: Int get() = data.edgeCount

    // Adjacency: the edge keys leaving each node, CSR style.
    private val adjacencyStart = IntArray(nodeCount + 1)
    private val adjacencyKeys = IntArray(edgeCount * 2)

    // Segments between consecutive shape points; edge e owns [segmentStart[e], segmentStart[e + 1]).
    private val segmentStart = IntArray(edgeCount + 1)
    private val segmentLength: FloatArray
    private val segmentDz: FloatArray
    private val segmentGrade: FloatArray

    private val startBearing = FloatArray(edgeCount)
    private val endBearing = FloatArray(edgeCount)
    private val climbForward = FloatArray(edgeCount)
    private val climbBackward = FloatArray(edgeCount)
    private val nodeStress = ByteArray(nodeCount)
    private val restrictionKeys: LongArray
    private val restrictionVia: IntArray

    init {
        for (e in 0 until edgeCount) {
            adjacencyStart[data.edgeBase[e] + 1]++
            adjacencyStart[data.edgeAdj[e] + 1]++
        }
        for (n in 0 until nodeCount) adjacencyStart[n + 1] += adjacencyStart[n]
        val fill = adjacencyStart.copyOf(nodeCount)
        for (e in 0 until edgeCount) {
            adjacencyKeys[fill[data.edgeBase[e]]++] = e * 2
            adjacencyKeys[fill[data.edgeAdj[e]]++] = e * 2 + 1
        }

        for (e in 0 until edgeCount) {
            segmentStart[e + 1] = segmentStart[e] + pointCount(e) - 1
        }
        val segments = segmentStart[edgeCount]
        segmentLength = FloatArray(segments)
        segmentDz = FloatArray(segments)
        segmentGrade = FloatArray(segments)
        for (e in 0 until edgeCount) initEdgeGeometry(e)

        for (e in 0 until edgeCount) {
            val flags = data.flags(e)
            if (flags.road.isMotorRoad()) {
                val stress = flags.stress.toByte()
                val base = data.edgeBase[e]
                val adj = data.edgeAdj[e]
                if (stress > nodeStress[base]) nodeStress[base] = stress
                if (stress > nodeStress[adj]) nodeStress[adj] = stress
            }
        }

        val order = (0 until data.restrictionCount).sortedBy { restrictionKey(data.restrictionFrom[it], data.restrictionTo[it]) }
        restrictionKeys = LongArray(order.size) { restrictionKey(data.restrictionFrom[order[it]], data.restrictionTo[order[it]]) }
        restrictionVia = IntArray(order.size) { data.restrictionVia[order[it]] }
    }

    fun flags(edge: Int): EdgeFlags = data.flags(edge)

    fun length(edge: Int): Double = data.edgeLength[edge].toDouble()

    fun name(edge: Int): String? = data.edgeName[edge].let { if (it < 0) null else data.names[it] }

    fun nodePoint(node: Int): GeoPoint = GeoPoint(data.nodeLat[node] / GraphData.E7, data.nodeLon[node] / GraphData.E7)

    fun nodeElevation(node: Int): Double = data.nodeEle[node] / 10.0

    /** Highest traffic stress among the motor roads meeting at [node] (0 when none). */
    fun nodeStress(node: Int): Int = nodeStress[node].toInt()

    /** Number of shape points of [edge], including both end nodes. */
    fun pointCount(edge: Int): Int = data.geometryStart[edge + 1] - data.geometryStart[edge] + 2

    /** Latitude E7 of shape point [i] of [edge], in base→adj order (0 = base node). */
    fun pointLatE7(edge: Int, i: Int): Int = when (i) {
        0 -> data.nodeLat[data.edgeBase[edge]]
        pointCount(edge) - 1 -> data.nodeLat[data.edgeAdj[edge]]
        else -> data.pillarLat[data.geometryStart[edge] + i - 1]
    }

    fun pointLonE7(edge: Int, i: Int): Int = when (i) {
        0 -> data.nodeLon[data.edgeBase[edge]]
        pointCount(edge) - 1 -> data.nodeLon[data.edgeAdj[edge]]
        else -> data.pillarLon[data.geometryStart[edge] + i - 1]
    }

    /** Elevation in metres of shape point [i] of [edge]. */
    fun pointElevation(edge: Int, i: Int): Double = when (i) {
        0 -> data.nodeEle[data.edgeBase[edge]]
        pointCount(edge) - 1 -> data.nodeEle[data.edgeAdj[edge]]
        else -> data.pillarEle[data.geometryStart[edge] + i - 1]
    } / 10.0

    fun point(edge: Int, i: Int): GeoPoint = GeoPoint(pointLatE7(edge, i) / GraphData.E7, pointLonE7(edge, i) / GraphData.E7)

    // --- edge keys ---------------------------------------------------------------------------

    fun edgeOf(key: Int): Int = key ushr 1
    fun isReverse(key: Int): Boolean = key and 1 == 1
    fun key(edge: Int, reverse: Boolean): Int = edge * 2 + if (reverse) 1 else 0
    fun reverseKey(key: Int): Int = key xor 1

    /** Node where the directed edge [key] starts. */
    fun tailNode(key: Int): Int = if (isReverse(key)) data.edgeAdj[edgeOf(key)] else data.edgeBase[edgeOf(key)]

    /** Node where the directed edge [key] ends. */
    fun headNode(key: Int): Int = if (isReverse(key)) data.edgeBase[edgeOf(key)] else data.edgeAdj[edgeOf(key)]

    fun mode(key: Int): TravelMode = flags(edgeOf(key)).mode(isReverse(key))

    /** Calls [action] with each directed edge key leaving [node] (in any mode, including [TravelMode.NONE]). */
    inline fun forEachOutgoing(node: Int, action: (key: Int) -> Unit) {
        for (i in outgoingStart(node) until outgoingStart(node + 1)) action(outgoingKey(i))
    }

    @PublishedApi internal fun outgoingStart(node: Int): Int = adjacencyStart[node]
    @PublishedApi internal fun outgoingKey(index: Int): Int = adjacencyKeys[index]

    fun degree(node: Int): Int = adjacencyStart[node + 1] - adjacencyStart[node]

    /** Initial bearing of the directed edge [key], degrees clockwise from north. */
    fun startBearing(key: Int): Double {
        val e = edgeOf(key)
        return if (isReverse(key)) normalizeBearing(endBearing[e] + 180.0) else startBearing[e].toDouble()
    }

    /** Final bearing of the directed edge [key], degrees clockwise from north. */
    fun endBearing(key: Int): Double {
        val e = edgeOf(key)
        return if (isReverse(key)) normalizeBearing(startBearing[e] + 180.0) else endBearing[e].toDouble()
    }

    /** Total climb in metres when travelling the directed edge [key]. */
    fun climb(key: Int): Double = (if (isReverse(key)) climbBackward else climbForward)[edgeOf(key)].toDouble()

    // --- segments ----------------------------------------------------------------------------

    fun segmentCount(edge: Int): Int = segmentStart[edge + 1] - segmentStart[edge]

    /** Horizontal length of segment [i] of [edge] (between shape points i and i+1). */
    fun segmentLength(edge: Int, i: Int): Double = segmentLength[segmentStart[edge] + i].toDouble()

    /** Elevation change base→adj along segment [i] of [edge], in metres. */
    fun segmentDz(edge: Int, i: Int): Double = segmentDz[segmentStart[edge] + i].toDouble()

    /**
     * Grade (rise / run) base→adj of segment [i], averaged over at least [MIN_GRADE_WINDOW_M] of the edge
     * so that short segments do not produce absurd slopes. Edges shorter than the window spread their climb over it.
     */
    fun segmentGrade(edge: Int, i: Int): Double = segmentGrade[segmentStart[edge] + i].toDouble()

    // --- turn restrictions -------------------------------------------------------------------

    /** True when OSM forbids turning from [fromEdge] via [viaNode] onto [toEdge]. */
    fun isTurnRestricted(fromEdge: Int, viaNode: Int, toEdge: Int): Boolean {
        if (restrictionKeys.isEmpty()) return false
        val key = restrictionKey(fromEdge, toEdge)
        var i = restrictionKeys.binarySearch(key)
        if (i < 0) return false
        while (i > 0 && restrictionKeys[i - 1] == key) i--
        while (i < restrictionKeys.size && restrictionKeys[i] == key) {
            if (restrictionVia[i] == viaNode) return true
            i++
        }
        return false
    }

    private fun restrictionKey(from: Int, to: Int): Long = (from.toLong() shl 32) or to.toLong()

    private fun initEdgeGeometry(edge: Int) {
        val points = pointCount(edge)
        val first = segmentStart[edge]
        val along = DoubleArray(points)
        var climbFwd = 0.0
        var climbBwd = 0.0
        for (i in 0 until points - 1) {
            val length = planarDistance(pointLatE7(edge, i), pointLonE7(edge, i), pointLatE7(edge, i + 1), pointLonE7(edge, i + 1))
            val dz = pointElevation(edge, i + 1) - pointElevation(edge, i)
            segmentLength[first + i] = length.toFloat()
            segmentDz[first + i] = dz.toFloat()
            along[i + 1] = along[i] + length
            if (dz > 0) climbFwd += dz else climbBwd -= dz
        }
        climbForward[edge] = climbFwd.toFloat()
        climbBackward[edge] = climbBwd.toFloat()

        // Grade of each segment over a window of at least MIN_GRADE_WINDOW_M centred on it.
        val total = along[points - 1]
        for (i in 0 until points - 1) {
            val grade = if (total <= MIN_GRADE_WINDOW_M) {
                // Spread the climb of a short edge over the whole window: a 3 m link with 0.9 m of DEM noise
                // between a pavement and the road is not a 30% ramp.
                (pointElevation(edge, points - 1) - pointElevation(edge, 0)) / MIN_GRADE_WINDOW_M
            } else {
                val middle = (along[i] + along[i + 1]) / 2
                val half = max(MIN_GRADE_WINDOW_M, along[i + 1] - along[i]) / 2
                var from = (middle - half).coerceAtLeast(0.0)
                var to = (middle + half).coerceAtMost(total)
                if (to - from < MIN_GRADE_WINDOW_M) {
                    if (from == 0.0) to = MIN_GRADE_WINDOW_M else from = total - MIN_GRADE_WINDOW_M
                }
                (elevationAt(edge, along, to) - elevationAt(edge, along, from)) / (to - from)
            }
            segmentGrade[first + i] = grade.toFloat()
        }

        startBearing[edge] = bearing(pointLatE7(edge, 0), pointLonE7(edge, 0), pointLatE7(edge, 1), pointLonE7(edge, 1)).toFloat()
        endBearing[edge] = bearing(
            pointLatE7(edge, points - 2), pointLonE7(edge, points - 2),
            pointLatE7(edge, points - 1), pointLonE7(edge, points - 1),
        ).toFloat()
    }

    private fun elevationAt(edge: Int, along: DoubleArray, distance: Double): Double {
        var i = 0
        while (i < along.size - 2 && along[i + 1] < distance) i++
        val span = along[i + 1] - along[i]
        val t = if (span > 0) ((distance - along[i]) / span).coerceIn(0.0, 1.0) else 0.0
        return pointElevation(edge, i) + t * (pointElevation(edge, i + 1) - pointElevation(edge, i))
    }

    companion object {
        /** Minimum run over which segment grades are measured. */
        const val MIN_GRADE_WINDOW_M = 12.0

        private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M

        /** Equirectangular distance in metres between two E7 points; accurate to <0.1% at city scale. */
        fun planarDistance(lat1: Int, lon1: Int, lat2: Int, lon2: Int): Double {
            val meanLat = Math.toRadians((lat1.toDouble() + lat2) / 2 / GraphData.E7)
            val dy = (lat2 - lat1) / GraphData.E7 * METRES_PER_DEGREE
            val dx = (lon2 - lon1) / GraphData.E7 * METRES_PER_DEGREE * cos(meanLat)
            return sqrt(dx * dx + dy * dy)
        }

        /** Bearing in degrees clockwise from north between two E7 points (planar approximation). */
        fun bearing(lat1: Int, lon1: Int, lat2: Int, lon2: Int): Double {
            val meanLat = Math.toRadians((lat1.toDouble() + lat2) / 2 / GraphData.E7)
            val dy = (lat2 - lat1).toDouble()
            val dx = (lon2 - lon1) * cos(meanLat)
            if (dx == 0.0 && dy == 0.0) return 0.0
            return normalizeBearing(Math.toDegrees(atan2(dx, dy)))
        }

        fun normalizeBearing(degrees: Double): Double = ((degrees % 360) + 360) % 360

        /** Signed turn angle in (-180, 180]: positive turns right (clockwise), negative turns left. */
        fun turnAngle(inBearing: Double, outBearing: Double): Double {
            var delta = (outBearing - inBearing) % 360
            if (delta > 180) delta -= 360
            if (delta <= -180) delta += 360
            return delta
        }

        fun isSharpTurn(angle: Double): Boolean = abs(angle) > 150
    }
}

/** Roads shared with motor traffic, whose stress matters at junctions. */
fun RoadKind.isMotorRoad(): Boolean = when (this) {
    RoadKind.MOTORWAY, RoadKind.TRUNK, RoadKind.PRIMARY, RoadKind.SECONDARY, RoadKind.TERTIARY,
    RoadKind.UNCLASSIFIED, RoadKind.RESIDENTIAL, RoadKind.SERVICE, RoadKind.OTHER -> true
    else -> false
}
