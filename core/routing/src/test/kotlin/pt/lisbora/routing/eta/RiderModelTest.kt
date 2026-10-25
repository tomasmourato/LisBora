package pt.lisbora.routing.eta

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pt.lisbora.routing.graph.SurfaceKind

class RiderModelTest {

    private fun kmh(model: RiderModel, grade: Double, surface: SurfaceKind = SurfaceKind.PAVED) =
        model.ridingSpeed(grade, surface) * 3.6

    @Test
    fun `flat speeds are plausible for each level`() {
        val relaxed = kmh(RiderModel(RiderProfile(level = RiderLevel.RELAXED)), 0.0)
        val medium = kmh(RiderModel(RiderProfile(level = RiderLevel.MEDIUM)), 0.0)
        val sporty = kmh(RiderModel(RiderProfile(level = RiderLevel.SPORTY, bike = BikeType.ROAD)), 0.0)
        assertTrue(relaxed in 14.0..19.0, "relaxed $relaxed")
        assertTrue(medium in 18.0..23.0, "medium $medium")
        assertTrue(sporty in 28.0..36.0, "sporty road $sporty")
    }

    @Test
    fun `climbs are slower and never below walking pace`() {
        val model = RiderModel(RiderProfile())
        val flat = kmh(model, 0.0)
        val climb4 = kmh(model, 0.04)
        val climb8 = kmh(model, 0.08)
        assertTrue(climb4 < flat && climb8 < climb4, "flat $flat, 4% $climb4, 8% $climb8")
        assertTrue(climb8 in 5.0..10.0, "8% climb $climb8")
        val wall = model.ridingSpeed(0.25, SurfaceKind.PAVED)
        assertTrue(wall >= model.walkingSpeed(0.25) && wall * 3.6 < 4.0, "25% wall ${wall * 3.6}")
    }

    @Test
    fun `descents are capped by level and surface`() {
        val model = RiderModel(RiderProfile(level = RiderLevel.MEDIUM))
        assertEquals(RiderLevel.MEDIUM.maxDescentKmh, kmh(model, -0.10), 0.01)
        assertTrue(kmh(model, -0.10, SurfaceKind.COBBLESTONE) < RiderLevel.MEDIUM.maxDescentKmh * 0.7)
    }

    @Test
    fun `electric assistance helps uphill and stops at 25 kmh`() {
        val city = RiderModel(RiderProfile(bike = BikeType.CITY))
        val electric = RiderModel(RiderProfile(bike = BikeType.ELECTRIC))
        assertTrue(kmh(electric, 0.08) > 2 * kmh(city, 0.08))
        assertEquals(25.0, kmh(electric, 0.0), 0.05)
    }

    @ParameterizedTest
    @EnumSource(SurfaceKind::class)
    fun `rough surfaces are never faster than asphalt`(surface: SurfaceKind) {
        val model = RiderModel(RiderProfile())
        for (grade in listOf(-0.06, 0.0, 0.05)) {
            assertTrue(model.ridingSpeed(grade, surface) <= model.ridingSpeed(grade, SurfaceKind.PAVED) + 1e-9)
        }
    }

    @Test
    fun `speed decreases monotonically with grade`() {
        val model = RiderModel(RiderProfile())
        var previous = Double.MAX_VALUE
        var grade = -0.3
        while (grade <= 0.3) {
            val speed = model.ridingSpeed(grade, SurfaceKind.PAVED)
            assertTrue(speed <= previous + 1e-9, "grade $grade")
            previous = speed
            grade += 0.001
        }
    }

    @Test
    fun `personal factor scales speed`() {
        val base = RiderModel(RiderProfile()).ridingSpeed(0.0, SurfaceKind.PAVED)
        val faster = RiderModel(RiderProfile(personalFactor = 1.1)).ridingSpeed(0.0, SurfaceKind.PAVED)
        assertEquals(base * 1.1, faster, 1e-9)
    }
}
