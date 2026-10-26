package pt.lisbora.routing.graph

import pt.lisbora.routing.model.CycleInfraClass

/** How a bike can traverse an edge in one direction. */
enum class TravelMode {
    /** Not allowed (e.g. against a one-way roundabout). */
    NONE,

    /** Allowed only walking the bike (footways, steps, against a one-way street on the pavement). */
    PUSH,

    /** Allowed riding. */
    RIDE,
}

/** Cycling infrastructure on an edge: a CML class from the conflation, or OSM-only infrastructure. */
enum class EdgeInfra(val cmlClass: CycleInfraClass?) {
    NONE(null),
    SEGREGATED_TRACK(CycleInfraClass.SEGREGATED_TRACK),
    PAINTED_LANE(CycleInfraClass.PAINTED_LANE),
    SHARED_STREET_30(CycleInfraClass.SHARED_STREET_30),
    SHARED_WITH_PEDESTRIANS(CycleInfraClass.SHARED_WITH_PEDESTRIANS),
    TRAIL(CycleInfraClass.TRAIL),

    /** Cycle track or lane mapped in OSM but absent from the CML network (e.g. outside Lisbon). */
    OSM_CYCLEWAY(null);

    companion object {
        fun of(cmlClass: CycleInfraClass): EdgeInfra = entries.first { it.cmlClass == cmlClass }
    }
}

/** Road type, condensed from OSM `highway` (plus ferries). */
enum class RoadKind {
    OTHER, MOTORWAY, TRUNK, PRIMARY, SECONDARY, TERTIARY, UNCLASSIFIED, RESIDENTIAL, SERVICE,
    LIVING_STREET, PEDESTRIAN, TRACK, PATH, FOOTWAY, CYCLEWAY, STEPS, BRIDLEWAY, PLATFORM, FERRY,
}

/** Riding surface, condensed from OSM `surface`. */
enum class SurfaceKind {
    UNKNOWN,

    /** Asphalt, concrete or generic paved. */
    PAVED,

    /** Smooth paving blocks. */
    PAVING_STONES,

    /** Sett / cobblestones ("paralelepípedo", "calçada"). */
    COBBLESTONE,

    /** Compacted or fine gravel. */
    COMPACTED,
    GRAVEL,

    /** Ground, dirt, earth or generic unpaved. */
    UNPAVED,

    /** Grass or sand. */
    SOFT,
    WOOD,
    OTHER,
}

/**
 * Edge attributes packed into a [Long] (the graph stores one per edge).
 *
 * Layout (bit offsets): 0-1 forward mode, 2-3 backward mode, 4-6 infra, 7-11 road, 12-15 surface,
 * 16 tram, 17 bridge, 18 tunnel, 19 roundabout, 20 traffic signals, 21-22 stress-1, 23-27 max speed / 5,
 * 28-30 lanes, 31 riverside, 32 green, 33 near a viewpoint, 34 inside the municipality of Lisbon.
 * Bits 35-63 are reserved; readers must ignore them.
 */
@JvmInline
value class EdgeFlags(val bits: Long) {

    /** How the edge can be traversed from base to adjacent node ([reverse] = false) or the other way. */
    fun mode(reverse: Boolean): TravelMode = TravelMode.entries[field(if (reverse) 2 else 0, 2)]

    val infra: EdgeInfra get() = EdgeInfra.entries[field(4, 3)]
    val road: RoadKind get() = RoadKind.entries[field(7, 5)]
    val surface: SurfaceKind get() = SurfaceKind.entries[field(12, 4)]
    val tram: Boolean get() = flag(16)
    val bridge: Boolean get() = flag(17)
    val tunnel: Boolean get() = flag(18)
    val roundabout: Boolean get() = flag(19)
    val trafficSignals: Boolean get() = flag(20)

    /** Level of traffic stress, 1 (calm) to 4 (busy, fast traffic). */
    val stress: Int get() = field(21, 2) + 1

    /** Posted speed limit in km/h, or 0 when unknown. */
    val maxSpeedKmh: Int get() = field(23, 5) * 5

    /** Number of lanes, or 0 when unknown. */
    val lanes: Int get() = field(28, 3)

    /** Along the river or a dock, within sight of the water. */
    val riverside: Boolean get() = flag(31)

    /** Through a park, garden or wood (Monsanto). */
    val green: Boolean get() = flag(32)

    /** Passes a viewpoint ("miradouro"). */
    val viewpoint: Boolean get() = flag(33)

    /** Inside the municipality of Lisbon, the only place GIRA bikes may be ridden. */
    val lisbon: Boolean get() = flag(34)

    val isSteps: Boolean get() = road == RoadKind.STEPS
    val isFerry: Boolean get() = road == RoadKind.FERRY

    private fun field(offset: Int, width: Int): Int = ((bits ushr offset) and ((1L shl width) - 1)).toInt()
    private fun flag(offset: Int): Boolean = (bits ushr offset) and 1L == 1L

    override fun toString(): String =
        "EdgeFlags(fwd=${mode(false)}, bwd=${mode(true)}, infra=$infra, road=$road, surface=$surface, " +
            "tram=$tram, bridge=$bridge, tunnel=$tunnel, roundabout=$roundabout, signals=$trafficSignals, " +
            "stress=$stress, maxSpeed=$maxSpeedKmh, lanes=$lanes, riverside=$riverside, green=$green, viewpoint=$viewpoint, lisbon=$lisbon)"

    companion object {
        const val MAX_SPEED_KMH = 155
        const val MAX_LANES = 7

        fun of(
            forward: TravelMode,
            backward: TravelMode,
            infra: EdgeInfra = EdgeInfra.NONE,
            road: RoadKind = RoadKind.OTHER,
            surface: SurfaceKind = SurfaceKind.UNKNOWN,
            tram: Boolean = false,
            bridge: Boolean = false,
            tunnel: Boolean = false,
            roundabout: Boolean = false,
            trafficSignals: Boolean = false,
            stress: Int = 1,
            maxSpeedKmh: Int = 0,
            lanes: Int = 0,
            riverside: Boolean = false,
            green: Boolean = false,
            viewpoint: Boolean = false,
            lisbon: Boolean = false,
        ): EdgeFlags {
            require(stress in 1..4) { "stress must be 1..4: $stress" }
            var bits = forward.ordinal.toLong()
            bits = bits or (backward.ordinal.toLong() shl 2)
            bits = bits or (infra.ordinal.toLong() shl 4)
            bits = bits or (road.ordinal.toLong() shl 7)
            bits = bits or (surface.ordinal.toLong() shl 12)
            if (tram) bits = bits or (1L shl 16)
            if (bridge) bits = bits or (1L shl 17)
            if (tunnel) bits = bits or (1L shl 18)
            if (roundabout) bits = bits or (1L shl 19)
            if (trafficSignals) bits = bits or (1L shl 20)
            bits = bits or ((stress - 1).toLong() shl 21)
            bits = bits or ((maxSpeedKmh.coerceIn(0, MAX_SPEED_KMH) / 5).toLong() shl 23)
            bits = bits or (lanes.coerceIn(0, MAX_LANES).toLong() shl 28)
            if (riverside) bits = bits or (1L shl 31)
            if (green) bits = bits or (1L shl 32)
            if (viewpoint) bits = bits or (1L shl 33)
            if (lisbon) bits = bits or (1L shl 34)
            return EdgeFlags(bits)
        }
    }
}
