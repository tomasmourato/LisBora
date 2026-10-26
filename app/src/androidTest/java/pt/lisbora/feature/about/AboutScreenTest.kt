package pt.lisbora.feature.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.BuildConfig
import pt.lisbora.ui.theme.LisboraTheme

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun checkAccessibility() = composeRule.enableAccessibilityChecks()

    @Test
    fun showsTheVersionThePrivacyPolicyTheDataSourcesAndTheLicences() {
        var notices = 0
        composeRule.setContent { LisboraTheme { AboutScreen(onBack = {}, onNotices = { notices++ }) } }
        composeRule.onNodeWithText("LisBora ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        composeRule.onNodeWithText("Política de privacidade").assertIsDisplayed()
        composeRule.onNodeWithText("© Câmara Municipal de Lisboa", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Licenças de código aberto").performScrollTo().performClick()
        assertEquals(1, notices)
    }
}
