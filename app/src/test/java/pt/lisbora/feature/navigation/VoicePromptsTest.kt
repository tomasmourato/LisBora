package pt.lisbora.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.discover.GiraTrip
import pt.lisbora.feature.planner.fakeRoute
import pt.lisbora.routing.HazardKind
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.io.TrackPoint
import pt.lisbora.routing.navigation.CyclewayChange
import pt.lisbora.routing.navigation.Instruction
import pt.lisbora.routing.navigation.Maneuver
import pt.lisbora.routing.navigation.RouteProgress

class VoicePromptsTest {

    private val point = GeoPoint(38.71, -9.14)
    private val route = fakeRoute(listOf(point, GeoPoint(38.72, -9.14)), durationS = 300.0)
    private val depart = Instruction(atM = 0.0, point = point, maneuver = Maneuver.DEPART, street = "Cais do Sodré")
    private val renamed = Instruction(atM = 300.0, point = point, maneuver = Maneuver.CONTINUE, street = "Rua do Arsenal")
    private val left = Instruction(atM = 600.0, point = point, maneuver = Maneuver.LEFT, street = "Rua do Alecrim")
    private val right = Instruction(atM = 650.0, point = point, maneuver = Maneuver.RIGHT, street = "Rua das Flores")
    private val cycleway = Instruction(atM = 900.0, point = point, maneuver = Maneuver.CONTINUE, street = null, cycleway = CyclewayChange.ENTER)
    private val arrive = Instruction(atM = 1100.0, point = point, maneuver = Maneuver.ARRIVE, street = null)
    private val cobbles = RouteHazard(HazardKind.COBBLES, startM = 800.0, endM = 900.0, point = point)

    /** Riding [alongM] metres into [route], which has [instructions]. */
    private fun riding(
        alongM: Double,
        instructions: List<Instruction> = listOf(depart, renamed, left, right, arrive),
        upcoming: RouteHazard? = null,
        gira: GiraNavigation? = null,
    ) = NavigationUiState(
        active = true,
        route = route,
        instructions = instructions,
        progress = RouteProgress(alongM, point, 0.0, offRoute = false, remainingM = 1100.0 - alongM, remainingS = 300.0, arrived = false),
        upcoming = upcoming,
        distanceToUpcomingM = upcoming?.let { it.startM - alongM },
        waitingForGps = false,
        gira = gira,
    )

    @Test
    fun `a turn is announced from afar when far away, about 150 m before, and just before with the one after it`() {
        val voice = VoicePrompts()
        assertEquals(emptyList<VoicePrompt>(), voice.next(NavigationUiState(active = true, route = route)))
        // A new street name alone is not said: the left turn is the next thing to say.
        assertEquals(listOf(VoicePrompt.Turn(left, 600.0)), voice.next(riding(0.0)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(290.0)))
        assertEquals(listOf(VoicePrompt.Turn(left, 155.0)), voice.next(riding(445.0)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(480.0)))
        assertEquals(listOf(VoicePrompt.Turn(left, null, right)), voice.next(riding(562.0)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(580.0)))
        // The next one is close: no "daqui a", just "now" when it comes.
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(600.0)))
        assertEquals(listOf(VoicePrompt.Turn(right, null)), voice.next(riding(615.0)))
        // Far to the destination: said with its distance; arriving is said when the ride ends.
        assertEquals(listOf(VoicePrompt.Turn(arrive, 450.0)), voice.next(riding(650.0)))
        assertEquals(listOf(VoicePrompt.Turn(arrive, 150.0)), voice.next(riding(950.0)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(1070.0)))
    }

    @Test
    fun `going straight onto a cycle track is said`() {
        val voice = VoicePrompts()
        val instructions = listOf(depart, renamed, cycleway, arrive)
        assertEquals(listOf(VoicePrompt.Turn(cycleway, 900.0)), voice.next(riding(0.0, instructions)))
        assertEquals(listOf(VoicePrompt.Turn(cycleway, null)), voice.next(riding(870.0, instructions)))
    }

    @Test
    fun `hazards once, leaving the route and failing to find another once each, and nothing while waiting for the GPS`() {
        val voice = VoicePrompts()
        val instructions = listOf(arrive)
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(0.0, instructions).copy(waitingForGps = true)))
        assertEquals(listOf(VoicePrompt.Turn(arrive, 1000.0)), voice.next(riding(100.0, instructions)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(600.0, instructions, upcoming = cobbles)))
        assertEquals(listOf(VoicePrompt.Ahead(cobbles, 140.0)), voice.next(riding(660.0, instructions, upcoming = cobbles)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(700.0, instructions, upcoming = cobbles)))

        val off = riding(700.0, instructions).copy(rerouting = true)
        assertEquals(listOf(VoicePrompt.OffRoute), voice.next(off))
        assertEquals(emptyList<VoicePrompt>(), voice.next(off))
        val failed = riding(700.0, instructions).copy(rerouteFailed = true)
        assertEquals(listOf(VoicePrompt.RerouteFailed), voice.next(failed))
        assertEquals(emptyList<VoicePrompt>(), voice.next(failed))
    }

    @Test
    fun `a new route after a detour is announced afresh`() {
        val voice = VoicePrompts()
        voice.next(riding(445.0))
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(450.0)))
        val detour = riding(450.0).copy(route = fakeRoute(listOf(point, GeoPoint(38.73, -9.14))))
        assertEquals(listOf(VoicePrompt.Turn(left, 150.0)), voice.next(detour))
    }

    @Test
    fun `on a GIRA ride, the 45-minute warnings and the station to swap at, and no turns before the pick-up`() {
        val station = GiraStation(101, "Cais do Sodré", point, bikes = 3, freeDocks = 6)
        val trip = GiraTrip(station, station, walkToPickUpM = 200.0, walkFromDropOffM = null)
        val voice = VoicePrompts()
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(0.0, gira = GiraNavigation(trip))))

        val started = GiraNavigation(trip, startedAtMs = 0, elapsedS = 35 * 60.0)
        val nearby = NearbyStation(station, 300.0)
        val ten = started.copy(warning = GiraWarning.TEN_MINUTES, suggestion = nearby)
        assertEquals(
            listOf(VoicePrompt.GiraTime(GiraWarning.TEN_MINUTES, 10, nearby), VoicePrompt.Turn(left, 500.0)),
            voice.next(riding(100.0, gira = ten)),
        )
        assertEquals(emptyList<VoicePrompt>(), voice.next(riding(120.0, gira = ten.copy(elapsedS = 36 * 60.0))))
        val five = ten.copy(elapsedS = 40.5 * 60, warning = GiraWarning.FIVE_MINUTES)
        assertEquals(listOf(VoicePrompt.GiraTime(GiraWarning.FIVE_MINUTES, 5, nearby)), voice.next(riding(150.0, gira = five)))
        val there = five.copy(swapStation = station, atSwapStation = true)
        assertEquals(listOf(VoicePrompt.GiraAtStation(station)), voice.next(riding(150.0, gira = there)))
    }

    @Test
    fun `arriving is said once, and a new ride starts afresh`() {
        val voice = VoicePrompts()
        voice.next(riding(0.0))
        val summary = RideSummary("Teste", 1100.0, 240.0, 260.0, 5.0, arrived = true, newPersonalFactor = null, track = emptyList<TrackPoint>(), plannedRoute = route)
        assertEquals(listOf(VoicePrompt.Arrived), voice.next(NavigationUiState(summary = summary)))
        assertEquals(emptyList<VoicePrompt>(), voice.next(NavigationUiState(summary = summary.copy(newPersonalFactor = 1.1))))
        assertEquals(emptyList<VoicePrompt>(), voice.next(NavigationUiState()))
        assertEquals(listOf(VoicePrompt.Turn(left, 600.0)), voice.next(riding(0.0)))
        // Stopped early: nothing to say.
        assertEquals(emptyList<VoicePrompt>(), voice.next(NavigationUiState(summary = summary.copy(arrived = false))))
    }
}
