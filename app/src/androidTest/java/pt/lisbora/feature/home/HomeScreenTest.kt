package pt.lisbora.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.data.datapack.CycleNetworkMeta
import pt.lisbora.data.map.MapData
import pt.lisbora.feature.map.CycleSegment
import pt.lisbora.feature.navigation.NavigationUiState
import pt.lisbora.feature.planner.PlannerActionsAdapter
import pt.lisbora.feature.planner.PlannerUiState
import pt.lisbora.routing.model.CycleInfraClass
import pt.lisbora.ui.theme.LisboraTheme
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun checkAccessibility() = composeRule.enableAccessibilityChecks()

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
        planner: PlannerUiState = PlannerUiState(),
        onSearchClick: () -> Unit = {},
        onDiscoverClick: () -> Unit = {},
        onFreeRideClick: () -> Unit = {},
        onLegendToggle: () -> Unit = {},
        onSegmentDismiss: () -> Unit = {},
        onMyLocationClick: () -> Unit = {},
    ) {
        LisboraTheme {
            HomeScreen(
                uiState = state,
                darkTheme = false,
                onDiscoverClick = onDiscoverClick,
                onMessageShown = {},
                onLegendToggle = onLegendToggle,
                onCycleNetworkVisibleChange = {},
                onSegmentDismiss = onSegmentDismiss,
                onMyLocationClick = onMyLocationClick,
                planner = planner,
                plannerActions = object : PlannerActionsAdapter() {
                    override fun onSearchOpen() = onSearchClick()
                    override fun onFreeRideOpen() = onFreeRideClick()
                },
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
                onFreeRideClick = { clicks++ },
                onLegendToggle = { clicks++ },
                onMyLocationClick = { clicks++ },
            )
        }

        composeRule.onNodeWithText("Para onde vais?").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Descobrir").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Pedalar").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Legenda da rede ciclável").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("A minha localização").assertIsDisplayed().performClick()
        assertEquals(5, clicks)
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

    @Test
    fun sidewaysWhileRidingTheNavigationTakesALeftColumnAndTheMapFollowsTheRiderOnTheRight() {
        var insets: MapInsets? = null
        composeRule.setContent {
            // Wider than tall, like a phone on its side in a GIRA bike's holder (and within the test device's width).
            Box(Modifier.size(width = 400.dp, height = 320.dp)) {
                LisboraTheme {
                    HomeScreen(
                        uiState = HomeUiState(map = readyMap),
                        darkTheme = false,
                        onDiscoverClick = {},
                        onMessageShown = {},
                        onLegendToggle = {},
                        onCycleNetworkVisibleChange = {},
                        onSegmentDismiss = {},
                        onMyLocationClick = {},
                        navigation = NavigationUiState(active = true),
                    ) { insets = it }
                }
            }
        }

        composeRule.onNodeWithText("À espera do GPS…").assertIsDisplayed()
        composeRule.onNodeWithText("Terminar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Desligar as indicações por voz").assertIsDisplayed()
        composeRule.onNodeWithText("Para onde vais?").assertDoesNotExist()
        // The rider is followed right of the panel (240 dp wide on so narrow a screen, 16 dp from the edge).
        assertEquals(256f, insets!!.follow.calculateLeftPadding(LayoutDirection.Ltr).value, 0.5f)
    }

    @Test
    fun onANarrowScreenWithLargeTextDescobrirGoesBelowPedalar() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                Box(Modifier.size(width = 320.dp, height = 640.dp)) { Screen(state = HomeUiState(map = readyMap)) }
            }
        }
        val pedalar = composeRule.onNodeWithContentDescription("Pedalar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val discover = composeRule.onNodeWithContentDescription("Descobrir").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(discover.top >= pedalar.bottom)
        assertTrue(discover.right <= 320.dp)
    }
}
