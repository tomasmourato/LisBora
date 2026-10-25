package pt.lisbora.pipeline.graph

import pt.lisbora.pipeline.osm.OsmFeatures
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.graph.isMotorRoad
import kotlin.math.max
import kotlin.math.min

/** Counts for the QA report. */
data class EnrichmentStats(val tramEdges: Int, val signalEdges: Int, val osmCyclewayEdges: Int, val upgradedToRide: Int)

/**
 * Derives the per-edge attributes the router needs once the CML conflation is done: infrastructure,
 * riding upgrades on CML paths, tram tracks, traffic lights and the level of traffic stress.
 */
class EdgeEnrichment(private val graph: RegionGraph, private val projection: LocalProjection) {

    fun apply(features: OsmFeatures): EnrichmentStats {
        val upgraded = applyInfrastructure(features.cycleLaneWayIds)
        val trams = markTramTracks(features)
        val signals = markSignals(features)
        graph.edges.forEach { it.stress = stress(it) }
        syncCopies()
        return EnrichmentStats(
            tramEdges = trams,
            signalEdges = signals,
            osmCyclewayEdges = graph.edges.count { it.infra == EdgeInfra.OSM_CYCLEWAY },
            upgradedToRide = upgraded,
        )
    }

    /**
     * CML class first, then OSM cycleways/lanes. On a CML path where OSM asks to get off (e.g. a footway the
     * CML designates "ciclopedonal"), riding is allowed in the directions OSM already allows the bike —
     * one-way rules are never changed here.
     */
    private fun applyInfrastructure(cycleLaneWayIds: Set<Long>): Int {
        var upgraded = 0
        graph.edges.forEach { e ->
            if (e.copyOf >= 0) return@forEach
            val cml = e.cmlClass
            e.infra = when {
                cml != null -> EdgeInfra.of(cml)
                e.road == RoadKind.CYCLEWAY || e.wayId in cycleLaneWayIds -> EdgeInfra.OSM_CYCLEWAY
                else -> EdgeInfra.NONE
            }
            if (cml != null && e.road != RoadKind.STEPS) {
                if (e.forward == TravelMode.PUSH && e.bikeForward && e.getOffForward) { e.forward = TravelMode.RIDE; upgraded++ }
                if (e.backward == TravelMode.PUSH && e.bikeBackward && e.getOffBackward) { e.backward = TravelMode.RIDE; upgraded++ }
            }
        }
        return upgraded
    }

    /** Roads carrying tram rails: by OSM way, or running along a `railway=tram` line for most of their length. */
    private fun markTramTracks(features: OsmFeatures): Int {
        val index = SegmentIndex(graph, projection) { it.road.isMotorRoad() || it.road == RoadKind.LIVING_STREET || it.road == RoadKind.PEDESTRIAN }
        val covered = HashMap<Int, MutableList<DoubleArray>>()
        features.tramLines.forEach { line ->
            line.zipWithNext { p, q ->
                val ax = projection.x(p.lon); val ay = projection.y(p.lat)
                val bx = projection.x(q.lon); val by = projection.y(q.lat)
                val tramSegment = EdgeSegment(-1, ax, ay, bx, by, 0.0)
                val steps = max(1, (tramSegment.length / TRAM_SPACING_M).toInt())
                for (k in 0 until steps) {
                    val t = (k + 0.5) / steps
                    val x = ax + t * (bx - ax)
                    val y = ay + t * (by - ay)
                    val best = index.near(x, y, TRAM_TOLERANCE_M)
                        .filter { EdgeSegment.bearingDifference(it.bearing, tramSegment.bearing) <= TRAM_MAX_BEARING_DEG }
                        .map { it to it.project(x, y) }
                        .filter { (_, projected) -> projected.first <= TRAM_TOLERANCE_M }
                        .minByOrNull { (_, projected) -> projected.first } ?: continue
                    val (segment, projected) = best
                    val center = segment.alongStart + projected.second * segment.length
                    val half = tramSegment.length / steps / 2
                    covered.getOrPut(segment.edge) { mutableListOf() } += doubleArrayOf(center - half, center + half)
                }
            }
        }
        var count = 0
        graph.edges.forEachIndexed { i, e ->
            if (e.copyOf >= 0) return@forEachIndexed
            val share = covered[i]?.let { CmlConflation.coveredLength(it, e.length) / e.length } ?: 0.0
            e.tram = e.wayId in features.tramRoadWayIds || share >= TRAM_MIN_COVERAGE
            if (e.tram) count++
        }
        return count
    }

    /** Edges passing a traffic light (the nearest edge within [SIGNAL_TOLERANCE_M] of each light). */
    private fun markSignals(features: OsmFeatures): Int {
        val index = SegmentIndex(graph, projection) { it.road != RoadKind.STEPS && it.road != RoadKind.FERRY }
        features.trafficSignals.forEach { light ->
            val x = projection.x(light.lon)
            val y = projection.y(light.lat)
            index.near(x, y, SIGNAL_TOLERANCE_M)
                .map { it to it.project(x, y).first }
                .filter { it.second <= SIGNAL_TOLERANCE_M }
                .minByOrNull { it.second }
                ?.let { (segment, _) -> graph.edges[segment.edge].signals = true }
        }
        return graph.edges.count { it.signals && it.copyOf < 0 }
    }

    private fun syncCopies() {
        graph.edges.forEach { e ->
            if (e.copyOf < 0) return@forEach
            val original = graph.edges[e.copyOf]
            e.cmlClass = original.cmlClass
            e.infra = original.infra
            e.forward = original.forward
            e.backward = original.backward
            e.tram = original.tram
            e.signals = original.signals
            e.stress = original.stress
        }
    }

    companion object {
        private const val TRAM_SPACING_M = 5.0
        private const val TRAM_TOLERANCE_M = 4.0
        private const val TRAM_MAX_BEARING_DEG = 20.0
        private const val TRAM_MIN_COVERAGE = 0.5
        private const val SIGNAL_TOLERANCE_M = 8.0

        /**
         * Level of traffic stress, 1 (calm) to 4: separated or traffic-free ways are 1; on streets it grows
         * with road class, speed limit and lanes, drops one level with a painted lane, and rises with tram rails.
         */
        fun stress(e: RegionEdge): Int {
            if (e.infra == EdgeInfra.SEGREGATED_TRACK || e.infra == EdgeInfra.SHARED_WITH_PEDESTRIANS ||
                e.infra == EdgeInfra.TRAIL || !e.road.isMotorRoad()
            ) {
                return 1
            }
            val speed = e.maxSpeedKmh
            val wide = e.lanes >= 4
            var stress = when (e.road) {
                RoadKind.MOTORWAY, RoadKind.TRUNK -> 4
                RoadKind.PRIMARY -> if (speed in 1..30) 3 else 4
                RoadKind.SECONDARY -> if (wide || speed > 50) 4 else 3
                RoadKind.TERTIARY -> if (wide || speed > 50) 3 else if (speed in 1..30) 1 else 2
                else -> if (speed > 50) 3 else if (speed in 1..30) 1 else 2
            }
            if (e.infra == EdgeInfra.SHARED_STREET_30) stress = min(stress, 2)
            if (e.infra == EdgeInfra.PAINTED_LANE || e.infra == EdgeInfra.OSM_CYCLEWAY) stress -= 1
            if (e.tram) stress += 1
            return stress.coerceIn(1, 4)
        }
    }
}
