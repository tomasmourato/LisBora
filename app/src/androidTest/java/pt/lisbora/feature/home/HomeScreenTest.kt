package pt.lisbora.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.ui.theme.LisboraTheme

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsSearchBarAndDiscoverButton() {
        var discoverClicks = 0
        var searchClicks = 0
        composeRule.setContent {
            LisboraTheme {
                HomeScreen(
                    uiState = HomeUiState(),
                    onSearchClick = { searchClicks++ },
                    onDiscoverClick = { discoverClicks++ },
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText("Para onde vais?").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Descobrir").assertIsDisplayed().performClick()

        assertEquals(1, searchClicks)
        assertEquals(1, discoverClicks)
    }

    @Test
    fun showsPendingMessageAsSnackbar() {
        composeRule.setContent {
            LisboraTheme {
                HomeScreen(
                    uiState = HomeUiState(pendingMessage = HomeMessage.DiscoverComingSoon),
                    onSearchClick = {},
                    onDiscoverClick = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithText("As rotas automáticas chegam na Fase 4.").assertIsDisplayed()
    }
}
