package pt.lisbora.feature.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.lisbora.R
import pt.lisbora.data.map.MapData
import pt.lisbora.data.map.MapDataRepository
import pt.lisbora.feature.map.CycleSegment
import pt.lisbora.feature.map.RecenterResult
import javax.inject.Inject

sealed interface MapLoadState {
    data object Loading : MapLoadState
    data class Ready(val data: MapData) : MapLoadState
    data object Failed : MapLoadState
}

data class HomeUiState(
    val map: MapLoadState = MapLoadState.Loading,
    val cycleNetworkVisible: Boolean = true,
    val legendExpanded: Boolean = false,
    val selectedSegment: CycleSegment? = null,
    val locationPermissionGranted: Boolean = false,
    /** Incremented to ask the map to fly to the user's position. */
    val recenterRequest: Long = 0L,
    val pendingMessage: HomeMessage? = null,
)

enum class HomeMessage(@param:StringRes val textRes: Int) {
    DiscoverComingSoon(R.string.home_discover_coming_soon),
    LocationPermissionDenied(R.string.message_location_denied),
    OutsideServiceArea(R.string.message_outside_area),
    LocationUnavailable(R.string.message_location_unavailable),
    MapFailed(R.string.message_map_failed),
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mapDataRepository: MapDataRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val state = try {
                MapLoadState.Ready(mapDataRepository.load())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MapLoadState.Failed
            }
            _uiState.update {
                it.copy(map = state, pendingMessage = if (state is MapLoadState.Failed) HomeMessage.MapFailed else it.pendingMessage)
            }
        }
    }

    fun onDiscoverClick() = showMessage(HomeMessage.DiscoverComingSoon)

    fun onMessageShown() {
        _uiState.update { it.copy(pendingMessage = null) }
    }

    fun onLegendToggle() {
        _uiState.update { it.copy(legendExpanded = !it.legendExpanded) }
    }

    fun onCycleNetworkVisibleChange(visible: Boolean) {
        _uiState.update {
            it.copy(cycleNetworkVisible = visible, selectedSegment = if (visible) it.selectedSegment else null)
        }
    }

    fun onSegmentClick(segment: CycleSegment?) {
        _uiState.update {
            it.copy(selectedSegment = segment, legendExpanded = if (segment != null) false else it.legendExpanded)
        }
    }

    fun onSegmentDismiss() = onSegmentClick(null)

    /** Silent check on start: enables the location dot without moving the camera. */
    fun onLocationPermissionChecked(granted: Boolean) {
        _uiState.update { it.copy(locationPermissionGranted = granted) }
    }

    /** Answer to a permission request the user triggered with "my location". */
    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                locationPermissionGranted = granted,
                recenterRequest = if (granted) it.recenterRequest + 1 else it.recenterRequest,
                pendingMessage = if (granted) it.pendingMessage else HomeMessage.LocationPermissionDenied,
            )
        }
    }

    fun onMyLocationClick() {
        _uiState.update { it.copy(recenterRequest = it.recenterRequest + 1) }
    }

    fun onRecenterResult(result: RecenterResult) {
        when (result) {
            RecenterResult.CENTERED -> Unit
            RecenterResult.OUTSIDE_SERVICE_AREA -> showMessage(HomeMessage.OutsideServiceArea)
            RecenterResult.UNAVAILABLE -> showMessage(HomeMessage.LocationUnavailable)
        }
    }

    private fun showMessage(message: HomeMessage) {
        _uiState.update { it.copy(pendingMessage = message) }
    }
}
