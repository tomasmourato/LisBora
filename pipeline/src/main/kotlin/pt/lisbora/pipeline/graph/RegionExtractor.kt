package pt.lisbora.pipeline.graph

import com.graphhopper.GraphHopper
import com.graphhopper.routing.ev.GetOffBike
import com.graphhopper.routing.ev.Lanes
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.ev.OSMWayID
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.RoadEnvironment
import com.graphhopper.routing.ev.Roundabout
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.util.FetchMode
import com.graphhopper.util.Parameters
import pt.lisbora.pipeline.osm.GraphHopperImport
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode

/** Cuts the region out of a GraphHopper graph: edges whose both end nodes lie inside [bounds]. */
class RegionExtractor(private val hopper: GraphHopper, private val bounds: GeoBounds) {

    private val em = hopper.encodingManager
    private val bikeAccess = em.getBooleanEncodedValue(VehicleAccess.key("bike"))
    private val getOffBike = em.getBooleanEncodedValue(GetOffBike.KEY)
    private val roadClass = em.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
    private val roadEnvironment = em.getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment::class.java)
    private val roundabout = em.getBooleanEncodedValue(Roundabout.KEY)
    private val surface = em.getEnumEncodedValue(Surface.KEY, Surface::class.java)
    private val maxSpeed = em.getDecimalEncodedValue(MaxSpeed.KEY)
    private val lanes = em.getIntEncodedValue(Lanes.KEY)
    private val wayId = em.getIntEncodedValue(OSMWayID.KEY)
    private val turnRestriction = em.getTurnBooleanEncodedValue(TurnRestriction.key(GraphHopperImport.PROFILE))

    fun extract(): RegionGraph {
        val graph = hopper.baseGraph
        val nodeAccess = graph.nodeAccess
        val nodeIndex = IntArray(graph.nodes) { -1 }
        val edgeIndex = IntArray(graph.edges) { -1 }
        val nodes = mutableListOf<RegionNode>()
        val edges = mutableListOf<RegionEdge>()
        val explorer = graph.createEdgeExplorer()

        fun regionNode(ghNode: Int): Int {
            if (nodeIndex[ghNode] < 0) {
                nodeIndex[ghNode] = nodes.size
                nodes += RegionNode(nodeAccess.getLat(ghNode), nodeAccess.getLon(ghNode))
            }
            return nodeIndex[ghNode]
        }

        fun inside(ghNode: Int) = nodeAccess.getLat(ghNode) in bounds.south..bounds.north &&
            nodeAccess.getLon(ghNode) in bounds.west..bounds.east

        val all = graph.allEdges
        while (all.next()) {
            val base = all.baseNode
            val adj = all.adjNode
            if (!inside(base) || !inside(adj)) continue
            val forward = all.get(bikeAccess)
            val backward = all.getReverse(bikeAccess)
            if (!forward && !backward) continue

            // Artificial copies made for via-way turn restrictions share the original's geometry reference.
            var original = all.edge
            graph.forEdgeAndCopiesOfEdge(explorer, base, all.edge) { copy -> if (copy < original) original = copy }

            val isRoundabout = all.get(roundabout)
            val environment = all.get(roadEnvironment)
            val ghRoad = all.get(roadClass)
            val geometry = all.fetchWayGeometry(FetchMode.ALL)
            val offForward = all.get(getOffBike)
            val offBackward = all.getReverse(getOffBike)
            edgeIndex[all.edge] = edges.size
            edges += RegionEdge(
                base = regionNode(base),
                adj = regionNode(adj),
                points = MutableList(geometry.size()) { ShapePoint(geometry.getLat(it), geometry.getLon(it)) },
                wayId = all.get(wayId).toLong(),
                name = (all.getValue(Parameters.Details.STREET_NAME) as? String)?.takeIf { it.isNotBlank() },
                forward = travelMode(forward, offForward, backward, isRoundabout),
                backward = travelMode(backward, offBackward, forward, isRoundabout),
                bikeForward = forward,
                bikeBackward = backward,
                getOffForward = offForward,
                getOffBackward = offBackward,
                road = if (environment == RoadEnvironment.FERRY) RoadKind.FERRY else roadKind(ghRoad),
                surface = surfaceKind(all.get(surface)),
                maxSpeedKmh = maxOf(all.get(maxSpeed), all.getReverse(maxSpeed)).let { if (it.isFinite()) it.toInt() else 0 },
                lanes = all.get(lanes),
                roundabout = isRoundabout,
                bridge = environment == RoadEnvironment.BRIDGE,
                tunnel = environment == RoadEnvironment.TUNNEL,
                copyOf = if (original != all.edge) edgeIndex[original] else -1,
            )
        }

        val restrictions = mutableListOf<Restriction>()
        val turnCosts = graph.turnCostStorage
        if (turnCosts != null) {
            val iterator = turnCosts.allTurnCosts
            while (iterator.next()) {
                if (!iterator.get(turnRestriction)) continue
                val from = edgeIndex[iterator.fromEdge]
                val to = edgeIndex[iterator.toEdge]
                val via = nodeIndex[iterator.viaNode]
                if (from >= 0 && to >= 0 && via >= 0) restrictions += Restriction(from, via, to)
            }
        }
        return RegionGraph(nodes, edges, restrictions)
    }

    companion object {
        /**
         * Riding where GraphHopper allows the bike and does not ask to get off; pushing where it asks to get off,
         * or against a one-way street (walking the bike on the pavement), except on roundabouts.
         */
        fun travelMode(access: Boolean, getOff: Boolean, reverseAccess: Boolean, roundabout: Boolean): TravelMode = when {
            access && !getOff -> TravelMode.RIDE
            access -> TravelMode.PUSH
            reverseAccess && !roundabout -> TravelMode.PUSH
            else -> TravelMode.NONE
        }

        fun roadKind(roadClass: RoadClass): RoadKind = when (roadClass) {
            RoadClass.MOTORWAY -> RoadKind.MOTORWAY
            RoadClass.TRUNK -> RoadKind.TRUNK
            RoadClass.PRIMARY -> RoadKind.PRIMARY
            RoadClass.SECONDARY -> RoadKind.SECONDARY
            RoadClass.TERTIARY -> RoadKind.TERTIARY
            RoadClass.UNCLASSIFIED, RoadClass.ROAD -> RoadKind.UNCLASSIFIED
            RoadClass.RESIDENTIAL -> RoadKind.RESIDENTIAL
            RoadClass.SERVICE, RoadClass.BUSWAY -> RoadKind.SERVICE
            RoadClass.LIVING_STREET -> RoadKind.LIVING_STREET
            RoadClass.PEDESTRIAN -> RoadKind.PEDESTRIAN
            RoadClass.TRACK -> RoadKind.TRACK
            RoadClass.PATH -> RoadKind.PATH
            RoadClass.FOOTWAY, RoadClass.CORRIDOR -> RoadKind.FOOTWAY
            RoadClass.CYCLEWAY -> RoadKind.CYCLEWAY
            RoadClass.STEPS -> RoadKind.STEPS
            RoadClass.BRIDLEWAY -> RoadKind.BRIDLEWAY
            RoadClass.PLATFORM -> RoadKind.PLATFORM
            RoadClass.OTHER, RoadClass.CONSTRUCTION -> RoadKind.OTHER
        }

        fun surfaceKind(surface: Surface): SurfaceKind = when (surface) {
            Surface.MISSING -> SurfaceKind.UNKNOWN
            Surface.PAVED, Surface.ASPHALT, Surface.CONCRETE -> SurfaceKind.PAVED
            Surface.PAVING_STONES -> SurfaceKind.PAVING_STONES
            Surface.COBBLESTONE -> SurfaceKind.COBBLESTONE
            Surface.COMPACTED, Surface.FINE_GRAVEL -> SurfaceKind.COMPACTED
            Surface.GRAVEL -> SurfaceKind.GRAVEL
            Surface.UNPAVED, Surface.GROUND, Surface.DIRT -> SurfaceKind.UNPAVED
            Surface.GRASS, Surface.SAND -> SurfaceKind.SOFT
            Surface.WOOD -> SurfaceKind.WOOD
            Surface.OTHER -> SurfaceKind.OTHER
        }
    }
}
