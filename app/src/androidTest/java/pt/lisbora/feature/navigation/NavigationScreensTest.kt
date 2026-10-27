package pt.lisbora.feature.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.feature.discover.GiraTrip
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.navigation.CyclewayChange
import pt.lisbora.routing.navigation.Instruction
import pt.lisbora.routing.navigation.Maneuver
import pt.lisbora.ui.theme.LisboraTheme

@RunWith(AndroidJUnit4::class)
class NavigationScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun checkAccessibility() = composeRule.enableAccessibilityChecks()

    private val start = GeoPoint(38.7105, -9.1376)
    private val route: Route = run {
        val end = GeoPoint(38.7105, -9.1300)
        val length = start.distanceTo(end)
        Route(
            points = listOf(start, end), elevations = listOf(5.0, 6.0), distances = listOf(0.0, length),
            legs = listOf(
                RouteLeg(0, false, 0.0, length, EdgeFlags.of(TravelMode.RIDE, TravelMode.RIDE), "Rua Augusta", length, 120.0, 0, 0.0),
            ),
            distanceM = length, durationS = 120.0, ascentM = 1.0, descentM = 0.0, maxGradePercent = 0.5, cost = 120.0, settledStates = 0,
        )
    }

    private class Recorder : NavigationActionsAdapter() {
        val calls = mutableListOf<String>()
        override fun onNavigationStop() { calls += "stop" }
        override fun onToggleVoice() { calls += "voice" }
        override fun onGiraGoToStation() { calls += "go" }
        override fun onGiraSwapped() { calls += "swapped" }
        override fun onExportGpx() { calls += "gpx" }
    }

    @Test
    fun theNextTurnInLargeTypeAndTheTimeLeft() {
        val actions = Recorder()
        val state = NavigationUiState(
            active = true,
            waitingForGps = false,
            route = route,
            next = Instruction(180.0, start, Maneuver.LEFT, "Rua da Conceição", CyclewayChange.ENTER),
            distanceToNextM = 183.0,
        )
        composeRule.setContent {
            LisboraTheme {
                Column {
                    NavigationTop(state, actions)
                    NavigationBottom(state, actions)
                }
            }
        }
        composeRule.onNodeWithText("180 m").assertIsDisplayed()
        composeRule.onNodeWithText("Vire à esquerda e entre na ciclovia").assertIsDisplayed()
        composeRule.onNodeWithText("Rua da Conceição").assertIsDisplayed()
        composeRule.onNodeWithText("2 min").assertIsDisplayed()
        composeRule.onNodeWithText("Terminar").performClick()
        composeRule.onNodeWithContentDescription("Desligar as indicações por voz").performClick()
        assertEquals(listOf("stop", "voice"), actions.calls)
    }

    @Test
    fun withTheVoiceOffTheButtonTurnsItOn() {
        composeRule.setContent { LisboraTheme { NavigationBottom(NavigationUiState(active = true, route = route, voiceOn = false), Recorder()) } }
        composeRule.onNodeWithContentDescription("Ligar as indicações por voz").assertIsDisplayed()
    }

    @Test
    fun nearTheEndOfTheGiraPeriodTheNearestStationIsOffered() {
        val actions = Recorder()
        val pickUp = GiraStation(101, "101 - Rossio", start)
        val swap = GiraStation(205, "205 - Rua Augusta", GeoPoint(38.7110, -9.1370), bikes = 4, freeDocks = 6)
        val state = NavigationUiState(
            active = true,
            waitingForGps = false,
            route = route,
            gira = GiraNavigation(
                trip = GiraTrip(pickUp, pickUp, 0.0, null),
                startedAtMs = 0,
                elapsedS = 40 * 60.0,
                warning = GiraWarning.FIVE_MINUTES,
                suggestion = NearbyStation(swap, 300.0),
            ),
        )
        composeRule.setContent { LisboraTheme { Column { NavigationTop(state, actions); NavigationBottom(state, actions) } } }
        composeRule.onNodeWithText("Faltam 5 minutos para os 45 min da viagem GIRA").assertIsDisplayed()
        composeRule.onNodeWithText("Troca de bicicleta na estação GIRA 205, a 300 m (6 docas livres, 4 bicicletas)").assertIsDisplayed()
        composeRule.onNodeWithText("GIRA: 40 min desta viagem").assertIsDisplayed()
        composeRule.onNodeWithText("Ir até lá").performClick()
        composeRule.onNodeWithText("Já troquei").performClick()
        assertEquals(listOf("go", "swapped"), actions.calls)
    }

    @Test
    fun walkingToTheGiraStationSaysHowFarItIsNowAndThenToTakeABike() {
        val pickUp = GiraStation(101, "101 - Rossio", start)
        val walking = NavigationUiState(
            active = true,
            route = route,
            gira = GiraNavigation(trip = GiraTrip(pickUp, pickUp, walkToPickUpM = 400.0, walkFromDropOffM = null), toPickUpM = 120.0),
        )
        var state by mutableStateOf(walking)
        composeRule.setContent { LisboraTheme { NavigationTop(state, Recorder()) } }
        composeRule.onNodeWithText("Vai até à estação GIRA 101 (a 120 m) e levanta uma bicicleta").assertIsDisplayed()

        state = walking.copy(gira = walking.gira!!.copy(toPickUpM = 8.0))
        composeRule.onNodeWithText("Levanta uma bicicleta na estação GIRA 101").assertIsDisplayed()
    }

    @Test
    fun sidewaysThePanelHasTheNextTurnTheTimeLeftAndTerminar() {
        val actions = Recorder()
        val state = NavigationUiState(
            active = true,
            waitingForGps = false,
            route = route,
            next = Instruction(180.0, start, Maneuver.RIGHT, "Rua do Ouro", CyclewayChange.NONE),
            distanceToNextM = 93.0,
        )
        composeRule.setContent { LisboraTheme { NavigationSidePanel(state, actions, Modifier.size(width = 320.dp, height = 360.dp)) } }
        composeRule.onNodeWithText("90 m").assertIsDisplayed()
        composeRule.onNodeWithText("Rua do Ouro").assertIsDisplayed()
        composeRule.onNodeWithText("2 min").assertIsDisplayed()
        composeRule.onNodeWithText("Terminar").performClick()
        assertEquals(listOf("stop"), actions.calls)
    }

    @Test
    fun theSummarySaysHowTheRideWentAndOffersTheGpx() {
        val actions = Recorder()
        val summary = RideSummary(
            title = "LisBora", distanceM = 3200.0, movingS = 660.0, elapsedS = 700.0, climbM = 42.0, arrived = true,
            newPersonalFactor = 1.08, track = emptyList(), plannedRoute = route,
        )
        composeRule.setContent { LisboraTheme { RideSummaryPanel(summary, actions) } }
        composeRule.onNodeWithText("Chegaste!").assertIsDisplayed()
        composeRule.onNodeWithText("3,2 km").assertIsDisplayed()
        composeRule.onNodeWithText("17 km/h").assertIsDisplayed()
        composeRule.onNodeWithText("As próximas estimativas contam com o teu ritmo: 8% mais rápido do que o previsto.").assertIsDisplayed()
        composeRule.onNodeWithText("Exportar GPX").performClick()
        assertEquals(listOf("gpx"), actions.calls)
    }

    @Test
    fun aFreeRideShowsWhatHasBeenRiddenAndTheGiraTime() {
        val actions = Recorder()
        val state = NavigationUiState(
            active = true,
            free = true,
            waitingForGps = false,
            riddenM = 3200.0,
            movingS = 660.0,
            gira = GiraNavigation(trip = null, startedAtMs = 0, elapsedS = 12 * 60.0),
        )
        composeRule.setContent { LisboraTheme { Column { NavigationTop(state, actions); NavigationBottom(state, actions) } } }
        composeRule.onNodeWithText("3,2 km").assertIsDisplayed()
        composeRule.onNodeWithText("11 min a pedalar · 17 km/h de média").assertIsDisplayed()
        composeRule.onNodeWithText("GIRA: 12 min desta viagem").assertIsDisplayed()
        composeRule.onNodeWithText("Terminar").performClick()
        assertEquals(listOf("stop"), actions.calls)
    }

    @Test
    fun theSummaryLeavesOutAClimbThatIsNotKnown() {
        val summary = RideSummary(
            title = "LisBora", distanceM = 3200.0, movingS = 660.0, elapsedS = 700.0, climbM = null, arrived = false,
            newPersonalFactor = null, track = emptyList(), plannedRoute = null,
        )
        composeRule.setContent { LisboraTheme { RideSummaryPanel(summary, Recorder()) } }
        composeRule.onNodeWithText("Viagem terminada").assertIsDisplayed()
        composeRule.onNodeWithText("3,2 km").assertIsDisplayed()
        composeRule.onNodeWithText("Subida").assertDoesNotExist()
    }

    @Test
    fun pedalarOffersAGiraBikeAndStartsTheRide() {
        val calls = mutableListOf<String>()
        var setup by mutableStateOf(FreeRideSetup())
        val actions = object : FreeRideActions {
            override fun onFreeRideOpen() = Unit
            override fun onFreeRideClose() { calls += "close" }
            override fun onFreeRideGira(on: Boolean) {
                calls += "gira $on"
                setup = setup.copy(gira = on)
            }
            override fun onFreeRideStart() { calls += "start" }
        }
        composeRule.setContent { LisboraTheme { FreeRideContent(setup, emptyList(), actions) } }
        composeRule.onNodeWithText("Pedalar sem rota").assertIsDisplayed()
        composeRule.onNodeWithText("Bicicleta GIRA").performClick()
        composeRule.onNodeWithText("Bicicleta GIRA").assertIsOn()
        composeRule.onNodeWithText("Começar").performClick()
        assertEquals(listOf("gira true", "start"), calls)
    }
}
