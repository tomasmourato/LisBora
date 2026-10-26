package pt.lisbora.pipeline.qa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.discover.RouteHighlights
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.TravelMode

class GoldenDiscoveriesTest {

    private val config = """
        {"discoveries": [
          {"name": "Rio", "start": [38.7056, -9.1452], "minutes": 45, "purpose": "RIVER",
           "expect": {"minRiversideShare": 0.5, "maxRepeatedShare": 0.5}},
          {"name": "Lazer A→B", "start": [38.7369, -9.1336], "end": [38.6935, -9.2056], "minutes": 75, "purpose": "LEISURE"}
        ]}
    """.trimIndent()

    @Test
    fun `reads loops and A to B with their expectations`() {
        val (river, leisure) = GoldenDiscoveries.parse(config)
        assertEquals(DiscoveryPurpose.RIVER, river.request.purpose)
        assertEquals(45 * 60.0, river.request.durationS)
        assertNull(river.request.end)
        assertEquals(0.5, river.expect.minRiversideShare)
        assertEquals(0.5, river.expect.maxRepeatedShare)
        assertEquals(GeoPoint(38.6935, -9.2056), leisure.request.end)
        assertEquals(DiscoveryExpectations(), leisure.expect)
    }

    @Test
    fun `explains what the best route misses`() {
        val river = GoldenDiscoveries.parse(config).first()
        // 10 km in 60 min instead of 45, a fifth of it by the river, half of it ridden twice.
        val legs = listOf(leg(0, 0.0, 5000.0, riverside = true), leg(1, 5000.0, 2500.0), leg(1, 7500.0, 2500.0))
        val best = discovered(legs, durationS = 3600.0, riversideM = 2000.0)
        val failures = GoldenDiscoveries.failures(river, listOf(best))
        assertEquals(3, failures.size, "$failures")
        assertTrue(failures[0].startsWith("Só 1 rotas"))
        assertTrue(failures[1].startsWith("Duração +33%"))
        assertTrue(failures[2].startsWith("Junto ao rio 20%"))
        assertEquals(0.0, GoldenDiscoveries.repeatedShare(best), "two legs on one edge in a row are one pass")
        assertEquals(listOf("Sem rotas."), GoldenDiscoveries.failures(river, emptyList()))
    }

    private fun leg(edge: Int, startM: Double, lengthM: Double, riverside: Boolean = false) = RouteLeg(
        edge = edge, reverse = false, from = 0.0, to = lengthM,
        flags = EdgeFlags.of(TravelMode.RIDE, TravelMode.RIDE, riverside = riverside),
        name = null, lengthM = lengthM, timeS = lengthM / 4, firstPoint = 0, startM = startM,
    )

    private fun discovered(legs: List<RouteLeg>, durationS: Double, riversideM: Double): DiscoveredRoute {
        val distance = legs.sumOf { it.lengthM }
        val start = GeoPoint(38.7056, -9.1452)
        val route = Route(
            points = listOf(start, start), elevations = listOf(0.0, 0.0), distances = listOf(0.0, distance), legs = legs,
            distanceM = distance, durationS = durationS, ascentM = 0.0, descentM = 0.0, maxGradePercent = 0.0,
            cost = durationS, settledStates = 0,
        )
        val highlights = RouteHighlights(
            riversideM = riversideM, greenM = 0.0, segregatedM = 0.0, infraShare = 0.0, calmShare = 1.0,
            pois = listOf(Poi(PoiKind.BEACH, "Praia", start)), netDescentM = 0.0,
        )
        return DiscoveredRoute(route, listOf(start, start), emptyList(), turnsBack = false, highlights = highlights, score = 0.0)
    }
}
