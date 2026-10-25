package pt.lisbora.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.data.datapack.CycleNetworkMeta
import pt.lisbora.data.map.MapData
import pt.lisbora.feature.map.CycleSegment
import pt.lisbora.routing.model.CycleInfraClass
import pt.lisbora.ui.theme.LisboraTheme
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val readyMap = MapLoadState.Ready(
        MapData(
            lightStyle = "{}",
            darkStyle = "{}",
            cycleNetworkMeta = CycleNetworkMeta(
                attribution = "Rede Ciclável © CML",
                sourceLastEdit = Instant.parse("2026-10-23T09:14:09Z"),
                lengthKmByClass = mapOf(CycleInfraClass.SEGREGATED_TRACK to 118.9),
            ),
            hasBasemap = true,
        ),
    )

    @Composable
    private fun Screen(
        state: HomeUiState,
        onSearchClick: () -> Unit = {},
        onDiscoverClick: () -> Unit = {},
        onLegendToggle: () -> Unit = {},
        onSegmentDismiss: () -> Unit = {},
        onMyLocationClick: () -> Unit = {},
    ) {
        LisboraTheme {
            HomeScreen(
                uiState = state,
                darkTheme = false,
                onSearchClick = onSearchClick,
                onDiscoverClick = onDiscoverClick,
                onMessageShown = {},
                onLegendToggle = onLegendToggle,
                onCycleNetworkVisibleChange = {},
                onSegmentDismiss = onSegmentDismiss,
                onMyLocationClick = onMyLocationClick,
            ) {}
        }
    }

    @Test
    fun overlayButtonsAreShownAndClickable() {
        var clicks = 0
        composeRule.setContent {
            Screen(
                state = HomeUiState(map = readyMap),
                onSearchClick = { clicks++ },
                onDiscoverClick = { clicks++ },
                onLegendToggle = { clicks++ },
                onMyLocationClick = { clicks++ },
            )
        }

        composeRule.onNodeWithText("Para onde vais?").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Descobrir").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Legenda da rede ciclável").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("A minha localização").assertIsDisplayed().performClick()
        assertEquals(4, clicks)
    }

    @Test
    fun legendListsClassesWithLengthsAndSource() {
        composeRule.setContent { Screen(state = HomeUiState(map = readyMap, legendExpanded = true)) }

        composeRule.onNodeWithText("Ciclovia segregada").assertIsDisplayed()
        composeRule.onNodeWithText("118,9 km").assertIsDisplayed()
        composeRule.onNodeWithText("Trilho").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mostrar a rede ciclável no mapa").assertIsOn()
        composeRule.onNodeWithText("dados de 23/10/2026", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectedSegmentShowsDetailsAndCanBeDismissed() {
        var dismissed = false
        val segment = CycleSegment(
            id = "a6fae945", infraClass = CycleInfraClass.SEGREGATED_TRACK, name = "Avenida Almirante Reis",
            typology = "Pista Ciclável Unidirecional", segregation = "Pista ciclável (separação física)",
            parish = "Arroios", year = "2022", lengthM = 1_904.0, contraflow = false,
        )
        composeRule.setContent {
            Screen(state = HomeUiState(map = readyMap, selectedSegment = segment), onSegmentDismiss = { dismissed = true })
        }

        composeRule.onNodeWithText("Avenida Almirante Reis").assertIsDisplayed()
        composeRule.onNodeWithText("Freguesia: Arroios · desde 2022 · 1,9 km").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Fechar").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun pendingMessageIsShownAsSnackbar() {
        composeRule.setContent {
            Screen(state = HomeUiState(map = readyMap, pendingMessage = HomeMessage.OutsideServiceArea))
        }
        composeRule.onNodeWithText("Estás fora da área coberta pela LisBora.").assertIsDisplayed()
    }
}
