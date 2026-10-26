package pt.lisbora.feature.navigation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pt.lisbora.data.settings.RidingSettingsRepository
import javax.inject.Inject

/**
 * The screen's side of a ride: the [NavigationSession] (which outlives the screen) with the voice switch.
 * Starting a ride also starts the [NavigationService], which keeps it going with the screen off.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val session: NavigationSession,
    private val settings: RidingSettingsRepository,
    voice: VoiceGuide,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<NavigationUiState> =
        combine(session.uiState, settings.settings.map { it.voiceGuidance }, voice.available) { state, on, available ->
            state.copy(voiceOn = on, voiceUnavailable = available == false)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, session.uiState.value)

    fun start(request: NavigationRequest) {
        session.start(request)
        NavigationService.start(context)
    }

    fun onStop() = session.onStop()

    fun onSummaryDismiss() = session.onSummaryDismiss()

    fun onRecenter() = session.onRecenter()

    fun onMessageShown() = session.onMessageShown()

    fun onExportGpx(uri: String) = session.onExportGpx(uri)

    fun onGiraGoToStation() = session.onGiraGoToStation()

    fun onGiraSwapped() = session.onGiraSwapped()

    fun onToggleVoice() {
        viewModelScope.launch { settings.update { it.copy(voiceGuidance = !it.voiceGuidance) } }
    }
}
