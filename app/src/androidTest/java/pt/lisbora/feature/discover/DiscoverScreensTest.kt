package pt.lisbora.feature.discover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.feature.planner.Endpoint
import pt.lisbora.feature.planner.PlannerActionsAdapter
import pt.lisbora.feature.planner.PlannerMode
import pt.lisbora.feature.planner.SearchTarget
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.discover.RouteHighlights
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.ui.theme.LisboraTheme

@RunWith(AndroidJUnit4::class)
class DiscoverScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun checkAccessibility() = composeRule.enableAccessibilityChecks()

    private val cais = GeoPoint(38.7056, -9.1452)
    private val saved = SavedRoute(
        id = 7, purpose = DiscoveryPurpose.RIVER, tweaks = emptySet(), stops = listOf(cais, GeoPoint(38.70, -9.17), cais),
        turnsBack = true, distanceM = 11_100.0, durationS = 2_580.0, ascentM = 16.0, highlight = "Doca de Santo Amaro",
    )

    @Test
    fun choicesSetTimePurposeAndDestination() {
        var chosenMinutes = 0
        var chosenPurpose: DiscoveryPurpose? = null
        var chosenDestination = false
        var chosenGira = false
        var searched = false
        var opened: SavedRoute? = null
        var removed: SavedRoute? = null
        val actions = object : PlannerActionsAdapter() {
            override fun onDiscoverMinutes(minutes: Int) { chosenMinutes = minutes }
            override fun onDiscoverPurpose(purpose: DiscoveryPurpose) { chosenPurpose = purpose }
            override fun onDiscoverDestinationChange(toDestination: Boolean) { chosenDestination = toDestination }
            override fun onDiscoverGira(on: Boolean) { chosenGira = on }
            override fun onDiscoverSearch() { searched = true }
            override fun onSavedRouteOpen(route: SavedRoute) { opened = route }
            override fun onSavedRouteRemove(route: SavedRoute) { removed = route }
        }
        composeRule.setContent {
            LisboraTheme {
                DiscoverPanel(PlannerMode.Discover(DiscoverSetup(start = Endpoint.MyLocation)), listOf(saved), null, RoutePalette.Light, 600.dp, actions)
            }
        }
        composeRule.onNodeWithText("1 h 30").performClick()
        assertEquals(90, chosenMinutes)
        composeRule.onNodeWithText("Cultura").performClick()
        assertEquals(DiscoveryPurpose.CULTURE, chosenPurpose)
        composeRule.onNodeWithText("Até um destino").performClick()
        assertEquals(true, chosenDestination)
        composeRule.onNodeWithText("Bicicleta GIRA").performClick()
        assertEquals(true, chosenGira)
        composeRule.onNodeWithTag(DISCOVER_SEARCH_TAG).performClick()
        assertEquals(true, searched)

        composeRule.onNodeWithText("Rio · 11,1 km · 43 min").performClick()
        assertEquals(saved, opened)
        composeRule.onNodeWithContentDescription("Remover a rota guardada").performClick()
        assertEquals(saved, removed)
    }

    @Test
    fun routesSayWhyAndOfferAnotherOne() {
        var another = false
        var chosenTweak: DiscoveryTweak? = null
        var save = false
        var selected = -1
        val actions = object : PlannerActionsAdapter() {
            override fun onDiscoverAnother() { another = true }
            override fun onDiscoverTweak(tweak: DiscoveryTweak) { chosenTweak = tweak }
            override fun onDiscoverSave() { save = true }
            override fun onRouteSelect(index: Int) { selected = index }
        }
        val viewpoint = Poi(PoiKind.VIEWPOINT, "Miradouro de Santa Catarina", cais, 70)
        val options = DiscoverOptions.from(
            listOf(discovered(2_700.0, riversideM = 3_200.0, anchors = listOf(viewpoint)), discovered(2_400.0, riversideM = 0.0, anchors = emptyList())),
            DiscoveryPurpose.SCENERY,
            null,
        )
        val station = GiraStation(101, "Bicicletas Gira Estação 101", cais)
        val discover = PlannerMode.Discover(
            DiscoverSetup(start = Endpoint.MyLocation, purpose = DiscoveryPurpose.SCENERY, gira = true),
            DiscoverResults.Ready(options, gira = GiraTrip(station, station, walkToPickUpM = 300.0, walkFromDropOffM = null)),
        )
        composeRule.setContent {
            LisboraTheme { DiscoverPanel(discover, emptyList(), null, RoutePalette.Light, 600.dp, actions) }
        }
        composeRule.onNodeWithText("45 min").assertIsDisplayed()
        composeRule.onNodeWithText("1 miradouro").assertIsDisplayed()
        composeRule.onNodeWithText("3,2 km junto ao rio").assertIsDisplayed()
        composeRule.onNodeWithText("Passa por Miradouro de Santa Catarina").assertIsDisplayed()
        composeRule.onNodeWithText("Levanta na estação GIRA 101, a 300 m a pé · entrega na mesma estação").assertIsDisplayed()

        composeRule.onNodeWithText("Outra rota").performClick()
        assertEquals(true, another)
        composeRule.onNodeWithText("Mais rio").performClick()
        assertEquals(DiscoveryTweak.MORE_RIVER, chosenTweak)
        composeRule.onNodeWithText("Guardar").performClick()
        assertEquals(true, save)
        composeRule.onNodeWithContentDescription("Rota 2 de 2: 40 min", substring = true).performClick()
        assertEquals(1, selected)
    }

    @Test
    fun headerAsksForTheDestinationTowardsOne() {
        var clicked: SearchTarget? = null
        val discover = PlannerMode.Discover(DiscoverSetup(start = Endpoint.MyLocation, toDestination = true))
        composeRule.setContent {
            LisboraTheme { DiscoverHeader(discover, RoutePalette.Light, onBack = {}, onEndpointClick = { clicked = it }, onPreferences = {}) }
        }
        composeRule.onNodeWithText("Descobrir").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Partida: A minha localização").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Destino: Escolher o destino").performClick()
        assertEquals(SearchTarget.DESTINATION, clicked)
    }

    private fun discovered(durationS: Double, riversideM: Double, anchors: List<Poi>): DiscoveredRoute {
        val points = listOf(cais, GeoPoint(38.70, -9.17))
        val length = points[0].distanceTo(points[1])
        val leg = RouteLeg(
            edge = 0, reverse = false, from = 0.0, to = length,
            flags = EdgeFlags.of(TravelMode.RIDE, TravelMode.RIDE, infra = EdgeInfra.SEGREGATED_TRACK, stress = 1, riverside = riversideM > 0),
            name = "Avenida Brasília", lengthM = length, timeS = durationS, firstPoint = 0, startM = 0.0,
        )
        val route = Route(
            points = points, elevations = listOf(5.0, 6.0), distances = listOf(0.0, length), legs = listOf(leg),
            distanceM = length, durationS = durationS, ascentM = 1.0, descentM = 0.0, maxGradePercent = 0.5,
            cost = durationS, settledStates = 0,
        )
        val highlights = RouteHighlights(
            riversideM = riversideM, greenM = 0.0, segregatedM = length, infraShare = 1.0, calmShare = 1.0, pois = anchors, netDescentM = 0.0,
        )
        return DiscoveredRoute(route, listOf(cais, points[1], cais), anchors, turnsBack = false, highlights = highlights, score = 1.0)
    }
}
