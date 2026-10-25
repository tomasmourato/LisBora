package pt.lisbora.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.lisbora.feature.map.MapDefaults
import pt.lisbora.routing.geo.GeoPoint

class HomeViewModelTest {

    private val viewModel = HomeViewModel()

    @Test
    fun `starts centred on Lisbon with no message`() {
        val state = viewModel.uiState.value
        assertEquals(MapDefaults.LisbonCenter, state.mapCenter)
        assertNull(state.pendingMessage)
    }

    @Test
    fun `discover click queues a message that is cleared once shown`() {
        viewModel.onDiscoverClick()
        assertEquals(HomeMessage.DiscoverComingSoon, viewModel.uiState.value.pendingMessage)

        viewModel.onMessageShown()
        assertNull(viewModel.uiState.value.pendingMessage)
    }

    @Test
    fun `search click queues the search message`() {
        viewModel.onSearchClick()
        assertEquals(HomeMessage.SearchComingSoon, viewModel.uiState.value.pendingMessage)
    }

    @Test
    fun `coordinates are formatted the Portuguese way`() {
        assertEquals("38,7223° N · 9,1393° O", formatCoordinates(GeoPoint(38.7223, -9.1393)))
    }
}
