package pt.lisbora.routing.graph

import pt.lisbora.routing.geo.GeoBounds

/**
 * The raw arrays of a routing graph, exactly as stored in a `graph.lbg` file.
 *
 * Coordinates are WGS84 degrees × 1e7 ("E7"); elevations are decimetres. Edges are undirected
 * records from [edgeBase] to [edgeAdj] whose [edgeFlags] say how each direction can be travelled.
 * The intermediate shape points ("pillars") of edge `e` are `pillar*[geometryStart[e] until geometryStart[e + 1]]`,
 * in base→adj order and excluding both end nodes. A turn restriction forbids going from edge
 * [restrictionFrom] through node [restrictionVia] onto edge [restrictionTo].
 */
class GraphData(
    val dataVersion: String,
    val bounds: GeoBounds,
    val nodeLat: IntArray,
    val nodeLon: IntArray,
    val nodeEle: ShortArray,
    val edgeBase: IntArray,
    val edgeAdj: IntArray,
    /** Horizontal length in metres. */
    val edgeLength: FloatArray,
    val edgeFlags: LongArray,
    /** Index into [names], or -1. */
    val edgeName: IntArray,
    val geometryStart: IntArray,
    val pillarLat: IntArray,
    val pillarLon: IntArray,
    val pillarEle: ShortArray,
    val names: List<String>,
    val restrictionFrom: IntArray,
    val restrictionVia: IntArray,
    val restrictionTo: IntArray,
) {
    val nodeCount: Int get() = nodeLat.size
    val edgeCount: Int get() = edgeBase.size
    val pillarCount: Int get() = pillarLat.size
    val restrictionCount: Int get() = restrictionFrom.size

    init {
        val n = nodeLat.size
        require(nodeLon.size == n && nodeEle.size == n) { "node arrays differ in size" }
        val e = edgeBase.size
        require(edgeAdj.size == e && edgeLength.size == e && edgeFlags.size == e && edgeName.size == e) {
            "edge arrays differ in size"
        }
        require(geometryStart.size == e + 1) { "geometryStart must have edgeCount + 1 entries" }
        val p = pillarLat.size
        require(pillarLon.size == p && pillarEle.size == p) { "pillar arrays differ in size" }
        require(geometryStart[0] == 0 && geometryStart[e] == p) { "geometryStart must span all pillars" }
        for (i in 0 until e) {
            require(edgeBase[i] in 0 until n && edgeAdj[i] in 0 until n) { "edge $i references a missing node" }
            require(geometryStart[i] <= geometryStart[i + 1]) { "geometryStart must not decrease (edge $i)" }
            require(edgeName[i] in -1 until names.size) { "edge $i references a missing name" }
        }
        val r = restrictionFrom.size
        require(restrictionVia.size == r && restrictionTo.size == r) { "restriction arrays differ in size" }
        for (i in 0 until r) {
            require(restrictionFrom[i] in 0 until e && restrictionTo[i] in 0 until e && restrictionVia[i] in 0 until n) {
                "restriction $i references a missing edge or node"
            }
        }
    }

    fun flags(edge: Int): EdgeFlags = EdgeFlags(edgeFlags[edge])

    companion object {
        const val E7 = 1e7

        fun toE7(degrees: Double): Int = Math.round(degrees * E7).toInt()

        fun toDecimetres(metres: Double): Short =
            Math.round(metres * 10).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt().toShort()
    }
}
