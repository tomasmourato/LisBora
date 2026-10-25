package pt.lisbora.routing.weighting

import pt.lisbora.routing.eta.RiderProfile

/** Why the rider is going, which shifts how infrastructure is valued. */
enum class TripPurpose { COMMUTE, LEISURE, SPORT }

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
) {
    init {
        maxGradePercent?.let { require(it in 1.0..30.0) { "maxGradePercent out of range: $it" } }
    }
}
