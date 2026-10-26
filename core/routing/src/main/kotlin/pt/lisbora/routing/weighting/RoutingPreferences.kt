package pt.lisbora.routing.weighting

import pt.lisbora.routing.eta.RiderProfile

/** Why the rider is going, which shifts how infrastructure is valued. */
enum class TripPurpose { COMMUTE, LEISURE, SPORT }

/**
 * Pulls routes towards some streets, for Descobrir: cost multipliers on riverside, green and viewpoint
 * edges (below 1 favours them; when several apply the smallest is used), and on climbs of at least
 * [BikeWeighting.CLIMB_BIAS_FROM] (below 1 seeks them, above 1 avoids them). All 1 = plain A→B routing.
 */
data class RouteBias(
    val riverside: Double = 1.0,
    val green: Double = 1.0,
    val viewpoint: Double = 1.0,
    val climb: Double = 1.0,
) {
    init {
        require(riverside in RANGE && green in RANGE && viewpoint in RANGE && climb in RANGE) { "bias out of range: $this" }
    }

    /** Smallest multiplier these biases can give an edge, for the A* heuristic. */
    val minFactor: Double get() = minOf(1.0, riverside, green, viewpoint) * minOf(1.0, climb)

    companion object {
        // Before NEUTRAL: its constructor checks the range.
        private val RANGE = 0.2..5.0
        val NEUTRAL = RouteBias()
    }
}

/** What the rider asked the router to favour or avoid. */
data class RoutingPreferences(
    val rider: RiderProfile = RiderProfile(),
    val purpose: TripPurpose = TripPurpose.COMMUTE,
    /** Steepest uphill grade the rider accepts, in percent; steeper segments are heavily penalised. */
    val maxGradePercent: Double? = null,
    val avoidCobbles: Boolean = false,
    val avoidTramTracks: Boolean = false,
    /** Favour physically segregated cycle tracks more strongly. */
    val preferSegregated: Boolean = false,
    val allowFerries: Boolean = true,
    val bias: RouteBias = RouteBias.NEUTRAL,
    /**
     * Never leave the municipality of Lisbon: GIRA bikes may only be ridden there. Ignored on graphs
     * built before edges carried that information.
     */
    val stayInLisbon: Boolean = false,
) {
    init {
        maxGradePercent?.let { require(it in 1.0..30.0) { "maxGradePercent out of range: $it" } }
    }
}
