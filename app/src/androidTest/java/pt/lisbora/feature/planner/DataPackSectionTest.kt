package pt.lisbora.feature.planner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.lisbora.data.datapack.DataPackInfo
import pt.lisbora.data.datapack.DataUpdateStatus
import pt.lisbora.ui.theme.LisboraTheme
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class DataPackSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val info = DataPackInfo(
        updatedAt = Instant.parse("2026-10-26T15:00:00Z"),
        cycleNetworkEditedAt = Instant.parse("2026-10-23T09:14:09Z"),
        osmDataAt = Instant.parse("2026-10-24T20:20:51Z"),
        giraStations = 202,
        giraFetchedAt = Instant.parse("2026-10-26T17:26:45Z"),
    )

    @Test
    fun showsTheSourcesAndChecksThenOffersARestart() {
        var status by mutableStateOf<DataUpdateStatus>(DataUpdateStatus.Idle)
        var checks = 0
        var restarts = 0
        composeRule.setContent {
            LisboraTheme { DataPackSection(info, status, onCheck = { checks++ }, onRestart = { restarts++ }) }
        }
        composeRule.onNodeWithText("Rede ciclável da CML: editada a 23/10/2026", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Estações GIRA: 202, lista de 26/10/2026", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Atualizam-se sozinhos uma vez por dia, em Wi-Fi.").assertIsDisplayed()
        composeRule.onNodeWithText("Procurar dados novos").performClick()
        assertEquals(1, checks)

        status = DataUpdateStatus.Downloading(doneBytes = 3_355_443, totalBytes = 8_556_905)
        composeRule.onNodeWithText("A descarregar 3,2 MB de 8,2 MB…").assertIsDisplayed()
        composeRule.onNodeWithText("Procurar dados novos").assertIsNotEnabled()

        status = DataUpdateStatus.Ready("20261102T043012Z")
        composeRule.onNodeWithText("Dados novos de 02/11/2026 prontos", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reiniciar agora").performClick()
        assertEquals(1, restarts)
    }

    @Test
    fun explainsWhyThereIsNothingNew() {
        var status by mutableStateOf<DataUpdateStatus>(DataUpdateStatus.NeedsAppUpdate)
        composeRule.setContent { LisboraTheme { DataPackSection(info, status, onCheck = {}, onRestart = {}) } }
        composeRule.onNodeWithText("precisam de uma versão mais recente da app", substring = true).assertIsDisplayed()

        status = DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.NETWORK)
        composeRule.onNodeWithText("Não foi possível ligar ao servidor. Tenta mais tarde.").assertIsDisplayed()
    }
}
