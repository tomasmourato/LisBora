package pt.lisbora.routing.eta

import pt.lisbora.routing.graph.SurfaceKind
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Bike types with their physical parameters. */
enum class BikeType(
    val massKg: Double,
    /** Drag area in m² (rider position included). */
    val dragArea: Double,
    /** Rolling resistance coefficient on asphalt. */
    val rollingResistance: Double,
    /** Electric assistance up to 25 km/h (EU pedelec), as in GIRA bikes. */
    val electricAssist: Boolean = false,
) {
    CITY(massKg = 17.0, dragArea = 0.65, rollingResistance = 0.0065),
    ROAD(massKg = 9.0, dragArea = 0.38, rollingResistance = 0.0045),
    MOUNTAIN(massKg = 14.0, dragArea = 0.55, rollingResistance = 0.0085),
    ELECTRIC(massKg = 24.0, dragArea = 0.65, rollingResistance = 0.0065, electricAssist = true),
}

/** How hard the rider pedals, as sustained power on the flat. */
enum class RiderLevel(val powerW: Double, val maxDescentKmh: Double) {
    RELAXED(powerW = 70.0, maxDescentKmh = 24.0),
    MEDIUM(powerW = 110.0, maxDescentKmh = 30.0),
    SPORTY(powerW = 180.0, maxDescentKmh = 40.0),
}

/**
 * Who is riding. [personalFactor] scales the predicted speed after calibrating against
 * the rider's own trips (1.0 = model as is).
 */
data class RiderProfile(
    val bike: BikeType = BikeType.CITY,
    val level: RiderLevel = RiderLevel.MEDIUM,
    val riderMassKg: Double = 75.0,
    val personalFactor: Double = 1.0,
) {
    init {
        require(riderMassKg in 20.0..200.0) { "riderMassKg out of range: $riderMassKg" }
        require(personalFactor in 0.5..2.0) { "personalFactor out of range: $personalFactor" }
    }
}

/**
 * Physical speed model: the speed at which the rider's power balances rolling resistance,
 * gravity and air drag, solved per grade and surface and cached in lookup tables.
 *
 * Riders push harder on climbs (up to [MAX_CLIMB_POWER_BOOST]×), never go slower than walking
 * the bike, and brake on descents to a level- and surface-dependent cap.
 */
class RiderModel(val profile: RiderProfile) {

    private val totalMassKg = profile.riderMassKg + profile.bike.massKg
    private val tables: Array<DoubleArray> = Array(SurfaceKind.entries.size) { surface ->
        DoubleArray(TABLE_SIZE) { i -> solveSpeed(MIN_GRADE + i * GRADE_STEP, SurfaceKind.entries[surface]) }
    }

    /** Riding speed in m/s on [grade] (rise/run, negative downhill) over [surface]. */
    fun ridingSpeed(grade: Double, surface: SurfaceKind): Double {
        val position = (grade.coerceIn(MIN_GRADE, MAX_GRADE) - MIN_GRADE) / GRADE_STEP
        val i = floor(position).toInt().coerceAtMost(TABLE_SIZE - 2)
        val t = position - i
        val table = tables[surface.ordinal]
        return (table[i] + t * (table[i + 1] - table[i])) * profile.personalFactor
    }

    /** Walking speed in m/s while pushing the bike on [grade]. */
    fun walkingSpeed(grade: Double): Double =
        (WALK_SPEED_MPS * (1 - WALK_SLOWDOWN_PER_GRADE * max(0.0, grade))).coerceAtLeast(MIN_WALK_SPEED_MPS)

    private fun solveSpeed(grade: Double, surface: SurfaceKind): Double {
        val angle = atan(grade)
        val rolling = profile.bike.rollingResistance * surface.rollingFactor()
        val resistance = totalMassKg * G * (rolling * cos(angle) + sin(angle))
        val aero = 0.5 * AIR_DENSITY * profile.bike.dragArea
        val boost = min(MAX_CLIMB_POWER_BOOST, 1 + CLIMB_POWER_BOOST_PER_GRADE * max(0.0, grade))
        val riderPower = profile.level.powerW * boost * DRIVETRAIN_EFFICIENCY
        var speed = speedForPower(riderPower, resistance, aero)
        if (profile.bike.electricAssist) {
            val assisted = speedForPower(riderPower + ASSIST_POWER_W, resistance, aero)
            speed = max(speed, min(assisted, ASSIST_LIMIT_MPS))
        }
        val descentCap = profile.level.maxDescentKmh / 3.6 * surface.descentFactor()
        speed = min(speed, descentCap)
        // Below walking pace on a steep climb, riders get off and push.
        return max(speed, walkingSpeed(grade))
    }

    /** Solves power = v·(resistance + aero·v²) for v ≥ 0 by bisection (the function is monotonic there). */
    private fun speedForPower(power: Double, resistance: Double, aero: Double): Double {
        var low = 0.0
        var high = MAX_SOLVER_SPEED_MPS
        repeat(60) {
            val mid = (low + high) / 2
            if (mid * (resistance + aero * mid * mid) < power) low = mid else high = mid
        }
        return low
    }

    companion object {
        const val G = 9.81
        const val AIR_DENSITY = 1.2
        const val DRIVETRAIN_EFFICIENCY = 0.95
        const val CLIMB_POWER_BOOST_PER_GRADE = 8.0
        const val MAX_CLIMB_POWER_BOOST = 1.7
        const val ASSIST_POWER_W = 250.0
        const val ASSIST_LIMIT_MPS = 25 / 3.6
        const val WALK_SPEED_MPS = 4.5 / 3.6
        const val MIN_WALK_SPEED_MPS = 2.5 / 3.6
        const val WALK_SLOWDOWN_PER_GRADE = 3.0
        private const val MAX_SOLVER_SPEED_MPS = 30.0
        private const val MIN_GRADE = -0.30
        private const val MAX_GRADE = 0.30
        private const val GRADE_STEP = 0.0025
        private val TABLE_SIZE = Math.round((MAX_GRADE - MIN_GRADE) / GRADE_STEP).toInt() + 1
    }
}

/** Rolling resistance relative to asphalt. */
internal fun SurfaceKind.rollingFactor(): Double = when (this) {
    SurfaceKind.UNKNOWN, SurfaceKind.PAVED -> 1.0
    SurfaceKind.PAVING_STONES -> 1.5
    SurfaceKind.COBBLESTONE -> 2.5
    SurfaceKind.COMPACTED -> 2.0
    SurfaceKind.GRAVEL -> 3.0
    SurfaceKind.UNPAVED -> 3.5
    SurfaceKind.SOFT -> 6.0
    SurfaceKind.WOOD -> 1.5
    SurfaceKind.OTHER -> 2.0
}

/** Share of the normal descent speed cap that is reasonable on this surface. */
internal fun SurfaceKind.descentFactor(): Double = when (this) {
    SurfaceKind.UNKNOWN, SurfaceKind.PAVED, SurfaceKind.WOOD -> 1.0
    SurfaceKind.PAVING_STONES, SurfaceKind.COMPACTED -> 0.8
    SurfaceKind.COBBLESTONE, SurfaceKind.GRAVEL, SurfaceKind.UNPAVED, SurfaceKind.OTHER -> 0.6
    SurfaceKind.SOFT -> 0.4
}
