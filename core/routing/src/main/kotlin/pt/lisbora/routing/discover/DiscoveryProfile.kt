package pt.lisbora.routing.discover

import pt.lisbora.routing.weighting.RouteBias
import pt.lisbora.routing.weighting.RoutingPreferences
import pt.lisbora.routing.weighting.TripPurpose
import kotlin.math.max
import kotlin.math.min

/** How candidate routes are ranked; each term is added to the score (negative weights penalise). */
data class ScoreWeights(
    /** Per share (0..1) of the distance on cycling infrastructure. */
    val infra: Double = 0.5,
    /** Per share ridden at traffic stress 1–2. */
    val calm: Double = 0.5,
    val riverside: Double = 0.0,
    val green: Double = 0.0,
    /** Per unit of worth of the points of interest passed (capped). */
    val pois: Double = 0.0,
    /** Per metre climbed per kilometre (negative = prefers flat routes, positive = seeks climbs). */
    val ascentPerKm: Double = -0.01,
    /** Penalty per percent the steepest climb goes above [gentleClimbPercent]. */
    val steepClimb: Double = 0.0,
    val gentleClimbPercent: Double = 8.0,
    /** Per traffic light per kilometre (negative = fewer stops). */
    val signalsPerKm: Double = 0.0,
    /** Per metre descended on smooth surfaces per kilometre. */
    val smoothDescentPerKm: Double = 0.0,
    /** Penalty per share of the distance on streets the route already took. */
    val repeated: Double = 2.5,
)

/**
 * What a [DiscoveryPurpose] means for Descobrir: which streets the router favours ([bias] and the
 * restrictions), which places routes are built through ([anchorKinds] and the riverside and green
 * spots of the graph) and how candidates are ranked ([weights]).
 */
data class DiscoveryProfile(
    val tripPurpose: TripPurpose,
    val bias: RouteBias = RouteBias.NEUTRAL,
    /** Upper bound for the rider's maximum climb, in percent. */
    val maxGradePercent: Double? = null,
    val avoidCobbles: Boolean = false,
    val avoidTramTracks: Boolean = false,
    val preferSegregated: Boolean = false,
    /** Worth of each kind of point of interest as a place to go through. */
    val anchorKinds: Map<PoiKind, Double> = emptyMap(),
    /** Worth of a riverside or green spot of the graph as a place to go through (0 = never). */
    val riversideSpot: Double = 0.0,
    val greenSpot: Double = 0.0,
    val weights: ScoreWeights = ScoreWeights(),
    /** Cost multiplier for streets an earlier leg already took (low where going back the same way is fine). */
    val reusePenalty: Float = 2.5f,
    /** Tours through places may go there and back the same way (along the river), not only round a loop. */
    val outAndBack: Boolean = false,
) {
    /** The rider's preferences adjusted for this purpose. Restrictions only ever get stricter; no ferries. */
    fun preferences(base: RoutingPreferences): RoutingPreferences = base.copy(
        purpose = tripPurpose,
        maxGradePercent = listOfNotNull(base.maxGradePercent, maxGradePercent).minOrNull(),
        avoidCobbles = base.avoidCobbles || avoidCobbles,
        avoidTramTracks = base.avoidTramTracks || avoidTramTracks,
        preferSegregated = base.preferSegregated || preferSegregated,
        allowFerries = false,
        bias = bias,
    )

    /** Points of interest worth mentioning on a route of this purpose. */
    val interestingKinds: Set<PoiKind> get() = anchorKinds.keys + PoiKind.VIEWPOINT

    fun with(tweaks: Set<DiscoveryTweak>): DiscoveryProfile = tweaks.fold(this) { profile, tweak ->
        when (tweak) {
            DiscoveryTweak.MORE_RIVER -> profile.copy(
                bias = profile.bias.copy(riverside = min(profile.bias.riverside, 0.75) * 0.75),
                riversideSpot = profile.riversideSpot + 0.8,
                weights = profile.weights.copy(riverside = profile.weights.riverside + 1.5),
            )
            DiscoveryTweak.FEWER_CLIMBS -> profile.copy(
                bias = profile.bias.copy(climb = min(max(profile.bias.climb, 1.0) * 2.0, 4.0)),
                weights = profile.weights.copy(
                    ascentPerKm = min(profile.weights.ascentPerKm, 0.0) - 0.04,
                    steepClimb = profile.weights.steepClimb + 0.15,
                    gentleClimbPercent = min(profile.weights.gentleClimbPercent, 6.0),
                ),
            )
        }
    }

    companion object {
        fun of(purpose: DiscoveryPurpose, tweaks: Set<DiscoveryTweak> = emptySet()): DiscoveryProfile =
            PROFILES.getValue(purpose).with(tweaks)

        private val PROFILES: Map<DiscoveryPurpose, DiscoveryProfile> = mapOf(
            DiscoveryPurpose.LEISURE to DiscoveryProfile(
                tripPurpose = TripPurpose.LEISURE,
                bias = RouteBias(riverside = 0.85, green = 0.85, viewpoint = 0.9),
                anchorKinds = mapOf(PoiKind.PARK to 1.0, PoiKind.VIEWPOINT to 0.8, PoiKind.BEACH to 0.6),
                riversideSpot = 0.7,
                greenSpot = 0.5,
                weights = ScoreWeights(infra = 1.0, calm = 0.8, riverside = 0.6, green = 0.6, pois = 0.4, ascentPerKm = -0.015, steepClimb = 0.05),
            ),
            DiscoveryPurpose.SCENERY to DiscoveryProfile(
                tripPurpose = TripPurpose.LEISURE,
                bias = RouteBias(riverside = 0.8, green = 0.85, viewpoint = 0.7),
                anchorKinds = mapOf(PoiKind.VIEWPOINT to 1.5, PoiKind.PARK to 0.5, PoiKind.MONUMENT to 0.3),
                riversideSpot = 0.6,
                weights = ScoreWeights(riverside = 0.7, green = 0.5, pois = 1.3, ascentPerKm = -0.005),
            ),
            // Lisbon's riverside is a long line: riding it there and back beats leaving it.
            DiscoveryPurpose.RIVER to DiscoveryProfile(
                tripPurpose = TripPurpose.LEISURE,
                bias = RouteBias(riverside = 0.55),
                anchorKinds = mapOf(PoiKind.BEACH to 0.5, PoiKind.VIEWPOINT to 0.2),
                riversideSpot = 1.5,
                weights = ScoreWeights(infra = 0.6, riverside = 3.0, ascentPerKm = -0.03, repeated = 0.8),
                reusePenalty = 1.3f,
                outAndBack = true,
            ),
            DiscoveryPurpose.SPORT to DiscoveryProfile(
                tripPurpose = TripPurpose.SPORT,
                anchorKinds = mapOf(PoiKind.VIEWPOINT to 0.3),
                greenSpot = 0.3,
                weights = ScoreWeights(infra = 0.3, calm = 0.5, ascentPerKm = 0.0, signalsPerKm = -0.3),
            ),
            DiscoveryPurpose.CLIMBS to DiscoveryProfile(
                tripPurpose = TripPurpose.SPORT,
                bias = RouteBias(climb = 0.45),
                anchorKinds = mapOf(PoiKind.VIEWPOINT to 1.2),
                weights = ScoreWeights(infra = 0.2, calm = 0.4, pois = 0.3, ascentPerKm = 0.1),
            ),
            DiscoveryPurpose.DESCENTS to DiscoveryProfile(
                tripPurpose = TripPurpose.LEISURE,
                bias = RouteBias(climb = 1.5),
                maxGradePercent = 6.0,
                avoidCobbles = true,
                anchorKinds = mapOf(PoiKind.VIEWPOINT to 0.8),
                weights = ScoreWeights(ascentPerKm = 0.0, steepClimb = 0.25, gentleClimbPercent = 5.0, smoothDescentPerKm = 0.03),
            ),
            DiscoveryPurpose.FAMILY to DiscoveryProfile(
                tripPurpose = TripPurpose.LEISURE,
                bias = RouteBias(riverside = 0.85, green = 0.8),
                maxGradePercent = 6.0,
                avoidCobbles = true,
                avoidTramTracks = true,
                preferSegregated = true,
                anchorKinds = mapOf(PoiKind.PARK to 1.0, PoiKind.PLAYGROUND to 1.0, PoiKind.BEACH to 0.5),
                riversideSpot = 0.7,
                greenSpot = 0.6,
                weights = ScoreWeights(
                    infra = 1.2, calm = 1.5, riverside = 0.8, green = 0.5, pois = 0.5,
                    ascentPerKm = -0.06, steepClimb = 0.2, gentleClimbPercent = 6.0, repeated = 1.0,
                ),
                reusePenalty = 1.5f,
                outAndBack = true,
            ),
            DiscoveryPurpose.CULTURE to DiscoveryProfile(
                tripPurpose = TripPurpose.LEISURE,
                bias = RouteBias(viewpoint = 0.9),
                anchorKinds = mapOf(PoiKind.MUSEUM to 1.2, PoiKind.MONUMENT to 1.0, PoiKind.ATTRACTION to 1.0, PoiKind.VIEWPOINT to 0.4),
                weights = ScoreWeights(calm = 0.6, pois = 1.3),
            ),
        )
    }
}
