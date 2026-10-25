package pt.lisbora.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pt.lisbora.data.datapack.CycleNetworkMeta
import pt.lisbora.data.map.MapData
import pt.lisbora.data.map.MapDataRepository
import pt.lisbora.feature.map.CycleSegment
import pt.lisbora.feature.map.RecenterResult
import pt.lisbora.routing.model.CycleInfraClass
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val mapData = MapData(
        lightStyle = "{}",
        darkStyle = "{}",
        cycleNetworkMeta = CycleNetworkMeta(attribution = "CML", sourceLastEdit = null, lengthKmByClass = emptyMap()),
        hasBasemap = true,
    )
    private val segment = CycleSegment(
        id = "abc", infraClass = CycleInfraClass.SEGREGATED_TRACK, name = "Avenida Almirante Reis",
        typology = null, segregation = null, parish = null, year = null, lengthM = null, contraflow = false,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun repository(load: () -> MapData) = object : MapDataRepository {
        override suspend fun load(): MapData = load()
    }

    private fun viewModel(repository: MapDataRepository = repository { mapData }) = HomeViewModel(repository)

    @Test
    fun `loads the map data on start`() {
        assertEquals(MapLoadState.Ready(mapData), viewModel().uiState.value.map)
    }

    @Test
    fun `a failed load shows an error`() {
        val state = viewModel(repository { throw IOException("boom") }).uiState.value
        assertEquals(MapLoadState.Failed, state.map)
        assertEquals(HomeMessage.MapFailed, state.pendingMessage)
    }

    @Test
    fun `selecting a segment closes the legend and dismissing clears it`() {
        val vm = viewModel()
        vm.onLegendToggle()
        vm.onSegmentClick(segment)
        assertEquals(segment, vm.uiState.value.selectedSegment)
        assertFalse(vm.uiState.value.legendExpanded)

        vm.onSegmentDismiss()
        assertNull(vm.uiState.value.selectedSegment)
    }

    @Test
    fun `hiding the cycle network clears the selection`() {
        val vm = viewModel()
        vm.onSegmentClick(segment)
        vm.onCycleNetworkVisibleChange(false)
        assertFalse(vm.uiState.value.cycleNetworkVisible)
        assertNull(vm.uiState.value.selectedSegment)
    }

    @Test
    fun `granting location asks the map to recenter, denying explains why`() {
        val vm = viewModel()
        vm.onLocationPermissionResult(granted = true)
        assertTrue(vm.uiState.value.locationPermissionGranted)
        assertEquals(1L, vm.uiState.value.recenterRequest)

        vm.onLocationPermissionResult(granted = false)
        assertEquals(1L, vm.uiState.value.recenterRequest)
        assertEquals(HomeMessage.LocationPermissionDenied, vm.uiState.value.pendingMessage)
    }

    @Test
    fun `the silent permission check never moves the camera`() {
        val vm = viewModel()
        vm.onLocationPermissionChecked(granted = true)
        assertTrue(vm.uiState.value.locationPermissionGranted)
        assertEquals(0L, vm.uiState.value.recenterRequest)
    }

    @Test
    fun `recenter results outside Lisbon or without a fix show a message`() {
        val vm = viewModel()
        vm.onRecenterResult(RecenterResult.OUTSIDE_SERVICE_AREA)
        assertEquals(HomeMessage.OutsideServiceArea, vm.uiState.value.pendingMessage)
        vm.onRecenterResult(RecenterResult.UNAVAILABLE)
        assertEquals(HomeMessage.LocationUnavailable, vm.uiState.value.pendingMessage)
        vm.onMessageShown()
        assertNull(vm.uiState.value.pendingMessage)
    }
}
