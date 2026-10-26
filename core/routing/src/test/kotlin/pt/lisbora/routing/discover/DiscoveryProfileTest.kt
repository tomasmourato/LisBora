package pt.lisbora.routing.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.TestGraphBuilder
import pt.lisbora.routing.weighting.RoutingPreferences

class DiscoveryProfileTest {

    @Test
    fun `a purpose only ever makes the rider's restrictions stricter, and never takes the ferry`() {
        val rider = RoutingPreferences(maxGradePercent = 5.0, avoidTramTracks = true)
        DiscoveryPurpose.entries.forEach { purpose ->
            val prefs = DiscoveryProfile.of(purpose).preferences(rider)
            assertTrue(prefs.maxGradePercent!! <= 5.0, "$purpose")
            assertTrue(prefs.avoidTramTracks, "$purpose")
            assertFalse(prefs.allowFerries, "$purpose")
        }
        val family = DiscoveryProfile.of(DiscoveryPurpose.FAMILY).preferences(RoutingPreferences())
        assertEquals(6.0, family.maxGradePercent)
        assertTrue(family.avoidCobbles && family.avoidTramTracks && family.preferSegregated)
    }

    @Test
    fun `quick adjustments pull towards the river and away from climbs`() {
        val leisure = DiscoveryProfile.of(DiscoveryPurpose.LEISURE)
        val moreRiver = DiscoveryProfile.of(DiscoveryPurpose.LEISURE, setOf(DiscoveryTweak.MORE_RIVER))
        assertTrue(moreRiver.bias.riverside < leisure.bias.riverside)
        assertTrue(moreRiver.weights.riverside > leisure.weights.riverside)

        val climbs = DiscoveryProfile.of(DiscoveryPurpose.CLIMBS)
        val fewer = DiscoveryProfile.of(DiscoveryPurpose.CLIMBS, setOf(DiscoveryTweak.FEWER_CLIMBS))
        assertTrue(fewer.bias.climb > climbs.bias.climb)
        assertTrue(fewer.weights.ascentPerKm < 0)
    }

    @Test
    fun `points of interest are found near a point and along a line`() {
        val g = TestGraphBuilder()
        val a = Poi(PoiKind.VIEWPOINT, "A", g.at(0.0, 0.0))
        val b = Poi(PoiKind.PARK, "B", g.at(150.0, 30.0))
        val c = Poi(PoiKind.MUSEUM, "C", g.at(1000.0, 0.0))
        val index = PoiIndex(listOf(a, b, c))
        assertEquals(listOf(a), index.near(g.at(10.0, 0.0), 40.0))
        assertEquals(setOf(a, b), index.near(g.at(75.0, 0.0), 120.0).toSet())
        // Sampled every 25 m, as routes are: B is 30 m from the line.
        val line = (0..40).map { g.at(it * 25.0, 0.0) }
        assertEquals(listOf(a, b, c), index.along(line, 40.0))
        assertEquals(listOf(c), index.along(line, 40.0) { it.kind == PoiKind.MUSEUM })
    }
}
