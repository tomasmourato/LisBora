package pt.lisbora.routing

import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.SurfaceKind
import pt.lisbora.routing.graph.TravelMode
import kotlin.math.max

/** Things along a route the rider should know about. None of them makes the rest of the route "safe". */
enum class HazardKind {
    /** Riding on a street with tram rails, where wheels can get caught. */
    TRAM_TRACKS,

    /** Cobblestones (sett, "calçada"). */
    COBBLES,

    /** Riding with busy, fast traffic (stress 4) and no cycling infrastructure. */
    BUSY_ROAD,

    /** Sustained descent of [RouteAnalysis.STEEP_GRADE] or more. */
    STEEP_DESCENT,

    /** Sustained climb of [RouteAnalysis.STEEP_GRADE] or more. */
    STEEP_CLIMB,

    /** Walking the bike: footways, or against a one-way street. */
    WALK,

    /** Crossing by ferry (timetable and fare apply). */
    FERRY,
}

/** One stretch of a [HazardKind] between [startM] and [endM] along the route. */
data class RouteHazard(
    val kind: HazardKind,
    val startM: Double,
    val endM: Double,
    /** Where the stretch starts, for a map marker. */
    val point: GeoPoint,
    /** Steepest grade of a climb or descent, in percent (positive). */
    val gradePercent: Double? = null,
) {
    val lengthM: Double get() = endM - startM
}

/** Summaries of a [Route] for the route cards: hazards, traffic stress and grades. */
object RouteAnalysis {

    /** Grade from which climbs and descents are listed. */
    const val STEEP_GRADE = 0.08

    /** Run over which [STEEP_GRADE] has to hold, so a short ramp is not reported. */
    const val STEEP_WINDOW_M = 50.0

    /** Stretches of the same kind closer than this are merged into one. */
    const val MERGE_GAP_M = 30.0

    /** Shortest stretch reported for each kind; shorter ones (a tram crossing) are left out. */
    val MIN_LENGTH_M: Map<HazardKind, Double> = mapOf(
        HazardKind.TRAM_TRACKS to 30.0,
        HazardKind.COBBLES to 50.0,
        HazardKind.BUSY_ROAD to 80.0,
        HazardKind.STEEP_DESCENT to STEEP_WINDOW_M,
        HazardKind.STEEP_CLIMB to STEEP_WINDOW_M,
        HazardKind.WALK to 15.0,
        HazardKind.FERRY to 0.0,
    )

    /** All hazards of [route], ordered by where they start. */
    fun hazards(route: Route): List<RouteHazard> {
        val stretches = mutableListOf<Stretch>()
        for (kind in LEG_KINDS) {
            val runs = mutableListOf<Stretch>()
            for (leg in route.legs) {
                if (!kind.appliesTo(leg)) continue
                val last = runs.lastOrNull()
                if (last != null && leg.startM - last.end <= MERGE_GAP_M) last.end = max(last.end, leg.endM)
                else runs += Stretch(kind, leg.startM, leg.endM)
            }
            stretches += runs
        }
        stretches += gradeStretches(route, climb = true)
        stretches += gradeStretches(route, climb = false)
        return stretches
            .filter { it.end - it.start >= MIN_LENGTH_M.getValue(it.kind) }
            .map { RouteHazard(it.kind, it.start, it.end, pointAt(route, it.start), it.grade?.let { g -> g * 100 }) }
            .sortedWith(compareBy({ it.startM }, { it.kind.ordinal }))
    }

    /** Metres ridden at each traffic stress level 1..4 (index 0..3); walking and ferries are left out. */
    fun lengthByStress(route: Route): DoubleArray {
        val lengths = DoubleArray(4)
        route.legs.filter { it.mode == TravelMode.RIDE && !it.flags.isFerry }
            .forEach { lengths[it.flags.stress - 1] += it.lengthM }
        return lengths
    }

    /**
     * Grade of travel (rise / run) at each point of [route], measured over the next [windowM] metres
     * (or the last [windowM] near the end), so single noisy points do not show up.
     */
    fun grades(route: Route, windowM: Double = 30.0): DoubleArray {
        val d = route.distances
        val e = route.elevations
        val result = DoubleArray(d.size)
        if (d.size < 2) return result
        var j = 0
        for (i in d.indices) {
            if (j < i) j = i
            while (j < d.size - 1 && d[j] - d[i] < windowM) j++
            var from = i
            // Near the end there is not a full window ahead: look back instead.
            while (from > 0 && d[j] - d[from] < windowM) from--
            val run = d[j] - d[from]
            result[i] = if (run > 0) (e[j] - e[from]) / run else 0.0
        }
        return result
    }

    /** The point at [distance] metres along [route], interpolated between its shape points. */
    fun pointAt(route: Route, distance: Double): GeoPoint {
        val d = route.distances
        if (d.isEmpty()) error("Route has no points")
        if (distance <= d.first()) return route.points.first()
        if (distance >= d.last()) return route.points.last()
        var i = d.binarySearch(distance)
        if (i >= 0) return route.points[i]
        i = -i - 2
        val span = d[i + 1] - d[i]
        val t = if (span > 0) (distance - d[i]) / span else 0.0
        val a = route.points[i]
        val b = route.points[i + 1]
        return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    private class Stretch(val kind: HazardKind, val start: Double, var end: Double, var grade: Double? = null)

    /** Stretches where the grade over [STEEP_WINDOW_M] reaches [STEEP_GRADE] uphill ([climb]) or downhill. */
    private fun gradeStretches(route: Route, climb: Boolean): List<Stretch> {
        val d = route.distances
        val e = route.elevations
        val kind = if (climb) HazardKind.STEEP_CLIMB else HazardKind.STEEP_DESCENT
        val ferries = route.legs.filter { it.flags.isFerry }.map { it.startM..it.endM }
        val runs = mutableListOf<Stretch>()
        var j = 0
        for (i in d.indices) {
            if (j < i) j = i
            while (j < d.size - 1 && d[j] - d[i] < STEEP_WINDOW_M) j++
            val run = d[j] - d[i]
            if (run < STEEP_WINDOW_M) break
            if (ferries.any { d[i] < it.endInclusive && d[j] > it.start }) continue
            val grade = (e[j] - e[i]) / run * if (climb) 1 else -1
            if (grade < STEEP_GRADE) continue
            val last = runs.lastOrNull()
            if (last != null && d[i] - last.end <= MERGE_GAP_M) {
                last.end = max(last.end, d[j])
                last.grade = max(last.grade ?: 0.0, grade)
            } else {
                runs += Stretch(kind, d[i], d[j], grade)
            }
        }
        return runs
    }

    private val LEG_KINDS = listOf(
        HazardKind.TRAM_TRACKS, HazardKind.COBBLES, HazardKind.BUSY_ROAD, HazardKind.WALK, HazardKind.FERRY,
    )

    private fun HazardKind.appliesTo(leg: RouteLeg): Boolean = when (this) {
        HazardKind.TRAM_TRACKS -> leg.flags.tram && leg.mode == TravelMode.RIDE
        HazardKind.COBBLES -> leg.flags.surface == SurfaceKind.COBBLESTONE && !leg.flags.isFerry
        HazardKind.BUSY_ROAD -> leg.mode == TravelMode.RIDE && leg.infra == EdgeInfra.NONE &&
            leg.flags.stress >= 4 && !leg.flags.isFerry
        HazardKind.WALK -> leg.mode == TravelMode.PUSH
        HazardKind.FERRY -> leg.flags.isFerry
        HazardKind.STEEP_CLIMB, HazardKind.STEEP_DESCENT -> false
    }
}
