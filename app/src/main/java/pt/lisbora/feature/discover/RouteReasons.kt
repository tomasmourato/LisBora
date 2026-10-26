package pt.lisbora.feature.discover

import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.PoiKind

/** One reason to ride a discovered route, shown as a chip on its card ("porquê esta rota"). */
sealed interface RouteReason {
    data class Riverside(val metres: Double) : RouteReason
    data class Green(val metres: Double) : RouteReason
    data class Viewpoints(val count: Int) : RouteReason
    /** Museums and monuments (attractions are too mixed a bag: OSM tags trees and shops as such). */
    data class Culture(val count: Int) : RouteReason
    data class Parks(val count: Int) : RouteReason
    data class Playgrounds(val count: Int) : RouteReason
    data class Segregated(val share: Double) : RouteReason
    data class Infra(val share: Double) : RouteReason
    data class Calm(val share: Double) : RouteReason
    data class Climb(val metres: Double) : RouteReason
    data class Descent(val metres: Double) : RouteReason
    /** A→B finishing lower than it starts. */
    data class EndsLower(val metres: Double) : RouteReason
    data object Flat : RouteReason
    data object GentleClimbs : RouteReason
}

/** Picks the reasons that matter for the purpose, in the order the rider cares about them. Pure. */
object RouteReasons {
    const val MAX = 4

    fun of(route: DiscoveredRoute, purpose: DiscoveryPurpose): List<RouteReason> =
        ORDER.getValue(purpose).mapNotNull { reason(it, route) }.take(MAX)

    private enum class Key { RIVERSIDE, GREEN, VIEWPOINTS, CULTURE, PARKS, PLAYGROUNDS, INFRA, CALM, CLIMB, DESCENT, ENDS_LOWER, FLAT, GENTLE }

    private val ORDER: Map<DiscoveryPurpose, List<Key>> = mapOf(
        DiscoveryPurpose.LEISURE to listOf(Key.RIVERSIDE, Key.GREEN, Key.VIEWPOINTS, Key.PARKS, Key.INFRA, Key.CALM, Key.FLAT),
        DiscoveryPurpose.SCENERY to listOf(Key.VIEWPOINTS, Key.RIVERSIDE, Key.GREEN, Key.CULTURE, Key.PARKS, Key.CLIMB),
        DiscoveryPurpose.RIVER to listOf(Key.RIVERSIDE, Key.INFRA, Key.FLAT, Key.CALM, Key.VIEWPOINTS),
        DiscoveryPurpose.SPORT to listOf(Key.CLIMB, Key.CALM, Key.GREEN, Key.INFRA),
        DiscoveryPurpose.CLIMBS to listOf(Key.CLIMB, Key.VIEWPOINTS, Key.CALM, Key.INFRA),
        DiscoveryPurpose.DESCENTS to listOf(Key.ENDS_LOWER, Key.DESCENT, Key.GENTLE, Key.VIEWPOINTS, Key.CALM),
        DiscoveryPurpose.FAMILY to listOf(Key.CALM, Key.INFRA, Key.PARKS, Key.PLAYGROUNDS, Key.RIVERSIDE, Key.FLAT),
        DiscoveryPurpose.CULTURE to listOf(Key.CULTURE, Key.VIEWPOINTS, Key.CALM, Key.INFRA),
    )

    private val CULTURE_KINDS = setOf(PoiKind.MUSEUM, PoiKind.MONUMENT)

    private fun reason(key: Key, discovered: DiscoveredRoute): RouteReason? {
        val route = discovered.route
        val h = discovered.highlights
        val distance = route.distanceM.coerceAtLeast(1.0)
        val places = (discovered.anchors + h.pois).filter { it.name != null }.distinctBy { it.kind to it.name }
        fun count(kinds: Set<PoiKind>) = places.count { it.kind in kinds }.takeIf { it > 0 }
        return when (key) {
            Key.RIVERSIDE -> h.riversideM.takeIf { it >= MIN_SCENIC_M }?.let(RouteReason::Riverside)
            Key.GREEN -> h.greenM.takeIf { it >= MIN_SCENIC_M }?.let(RouteReason::Green)
            Key.VIEWPOINTS -> count(setOf(PoiKind.VIEWPOINT))?.let(RouteReason::Viewpoints)
            Key.CULTURE -> count(CULTURE_KINDS)?.let(RouteReason::Culture)
            Key.PARKS -> count(setOf(PoiKind.PARK))?.let(RouteReason::Parks)
            Key.PLAYGROUNDS -> count(setOf(PoiKind.PLAYGROUND))?.let(RouteReason::Playgrounds)
            Key.INFRA -> when {
                h.segregatedM / distance >= MIN_SEGREGATED_SHARE -> RouteReason.Segregated(h.segregatedM / distance)
                h.infraShare >= MIN_INFRA_SHARE -> RouteReason.Infra(h.infraShare)
                else -> null
            }
            Key.CALM -> h.calmShare.takeIf { it >= MIN_CALM_SHARE }?.let(RouteReason::Calm)
            Key.CLIMB -> route.ascentM.takeIf { it >= MIN_CLIMB_M }?.let(RouteReason::Climb)
            Key.DESCENT -> route.descentM.takeIf { it >= MIN_CLIMB_M }?.let(RouteReason::Descent)
            Key.ENDS_LOWER -> h.netDescentM.takeIf { it >= MIN_NET_DESCENT_M }?.let(RouteReason::EndsLower)
            Key.FLAT -> RouteReason.Flat.takeIf { route.ascentM / (distance / 1000) < FLAT_M_PER_KM }
            Key.GENTLE -> RouteReason.GentleClimbs.takeIf { route.maxGradePercent <= GENTLE_PERCENT }
        }
    }

    const val MIN_SCENIC_M = 400.0
    const val MIN_SEGREGATED_SHARE = 0.25
    const val MIN_INFRA_SHARE = 0.3
    const val MIN_CALM_SHARE = 0.75
    const val MIN_CLIMB_M = 30.0
    const val MIN_NET_DESCENT_M = 25.0
    const val FLAT_M_PER_KM = 6.0
    const val GENTLE_PERCENT = 6.5
}
