package pt.lisbora.feature.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pt.lisbora.R
import pt.lisbora.feature.map.MapDefaults
import pt.lisbora.routing.geo.GeoPoint
import javax.inject.Inject

data class HomeUiState(
    val mapCenter: GeoPoint = MapDefaults.LisbonCenter,
    val pendingMessage: HomeMessage? = null,
)

enum class HomeMessage(@param:StringRes val textRes: Int) {
    SearchComingSoon(R.string.home_search_coming_soon),
    DiscoverComingSoon(R.string.home_discover_coming_soon),
}

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onSearchClick() {
        _uiState.update { it.copy(pendingMessage = HomeMessage.SearchComingSoon) }
    }

    fun onDiscoverClick() {
        _uiState.update { it.copy(pendingMessage = HomeMessage.DiscoverComingSoon) }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(pendingMessage = null) }
    }
}
