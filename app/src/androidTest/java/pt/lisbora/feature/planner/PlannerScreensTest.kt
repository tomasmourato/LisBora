package pt.lisbora.feature.planner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.feature.map.RoutePalette
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.ui.theme.LisboraTheme

@RunWith(AndroidJUnit4::class)
class PlannerScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val torre = Place("Torre de Belém", PlaceCategory.MONUMENT, GeoPoint(38.6916, -9.2160), "Belém, Lisboa")

    @Test
    fun emptySearchListsShortcutsAndSavedPlaces() {
        val typed = mutableListOf<String>()
        var setKind: SavedKind? = null
        val actions = object : PlannerActionsAdapter() {
            override fun onQueryChange(query: String) { typed += query }
            override fun onSetSaved(kind: SavedKind) { setKind = kind }
        }
        val saved = listOf(SavedPlace(1, SavedKind.FAVOURITE, torre))
        composeRule.setContent {
            LisboraTheme { SearchOverlay(SearchUiState(SearchTarget.DESTINATION), saved, emptyList(), actions) }
        }
        composeRule.onNodeWithText("A minha localização").assertIsDisplayed()
        composeRule.onNodeWithText("Escolher no mapa").assertIsDisplayed()
        composeRule.onNodeWithText("Torre de Belém").assertIsDisplayed()
        composeRule.onNodeWithText("Casa").performClick()
        assertEquals(SavedKind.HOME, setKind)
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("torre")
        assertEquals(listOf("torre"), typed)
    }

    @Test
    fun searchResultsShowKindContextAndDistance() {
        var chosen: Place? = null
        val actions = object : PlannerActionsAdapter() {
            override fun onPlaceChosen(place: Place) { chosen = place }
        }
        composeRule.setContent {
            LisboraTheme {
                SearchOverlay(
                    SearchUiState(SearchTarget.PLACE, query = "torre", results = listOf(SearchResultItem(torre, 6_200.0))),
                    emptyList(), emptyList(), actions,
                )
            }
        }
        composeRule.onNodeWithText("Monumento · Belém, Lisboa · 6,2 km").assertIsDisplayed()
        composeRule.onNodeWithText("Torre de Belém").performClick()
        assertEquals(torre, chosen)
    }

    @Test
    fun placeCardOffersDirectionsAndSaving() {
        var directionsTo: Place? = null
        var favourite: Place? = null
        val actions = object : PlannerActionsAdapter() {
            override fun onDirectionsTo(place: Place) { directionsTo = place }
            override fun onToggleFavourite(place: Place) { favourite = place }
        }
        composeRule.setContent {
            LisboraTheme { PlaceCard(torre, fromMap = false, isFavourite = false, distanceM = 950.0, actions = actions) }
        }
        composeRule.onNodeWithText("Belém, Lisboa · a 950 m").assertIsDisplayed()
        composeRule.onNodeWithText("Direções").performClick()
        composeRule.onNodeWithContentDescription("Guardar").performClick()
        assertEquals(torre, directionsTo)
        assertEquals(torre, favourite)
    }

    @Test
    fun routesPanelShowsCardsAndDetails() {
        var selected = -1
        var toggled = false
        val actions = object : PlannerActionsAdapter() {
            override fun onRouteSelect(index: Int) { selected = index }
            override fun onToggleDetails() { toggled = true }
        }
        val options = RouteOptions.from(listOf(route(1_380.0, EdgeInfra.SEGREGATED_TRACK), route(1_200.0, EdgeInfra.NONE)), null)
        val directions = PlannerMode.Directions(
            origin = Endpoint.MyLocation,
            destination = Endpoint.AtPlace(torre),
            routes = RoutesState.Ready(options),
            detailsExpanded = true,
        )
        composeRule.setContent {
            LisboraTheme { RoutesPanel(directions, null, RoutePalette.Light, 600.dp, actions) }
        }
        composeRule.onNodeWithText("23 min").assertIsDisplayed()
        composeRule.onNodeWithText("Recomendada").assertIsDisplayed()
        composeRule.onNodeWithText("Mais rápida").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Rota 2 de 2: 20 min", substring = true).performClick()
        assertEquals(1, selected)
        composeRule.onNodeWithText("Ciclovia segregada").assertIsDisplayed()
        composeRule.onNodeWithText("Esconder detalhes").performClick()
        assertEquals(true, toggled)
    }

    private fun route(durationS: Double, infra: EdgeInfra): Route {
        val points = listOf(GeoPoint(38.70, -9.20), GeoPoint(38.70, -9.18))
        val length = points[0].distanceTo(points[1])
        val leg = RouteLeg(
            edge = 0, reverse = false, from = 0.0, to = length,
            flags = EdgeFlags.of(TravelMode.RIDE, TravelMode.RIDE, infra = infra, stress = 1),
            name = "Avenida Brasília", lengthM = length, timeS = durationS, firstPoint = 0, startM = 0.0,
        )
        return Route(
            points = points, elevations = listOf(5.0, 6.0), distances = listOf(0.0, length), legs = listOf(leg),
            distanceM = length, durationS = durationS, ascentM = 1.0, descentM = 0.0, maxGradePercent = 0.5,
            cost = durationS, settledStates = 0,
        )
    }
}
