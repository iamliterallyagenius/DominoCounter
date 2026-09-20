package com.example.dominocounter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.data.repo.SettingsRepository
import com.example.dominocounter.domain.model.Rules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val targetScore: Int = Rules.DEFAULT_TARGET_SCORE,
    val torchDefaultOn: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val saveScanPhotos: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.targetScore,
        settingsRepository.torchDefaultOn,
        settingsRepository.hapticsEnabled,
        settingsRepository.saveScanPhotos
    ) { target, torch, haptics, saveScans ->
        SettingsUiState(target, torch, haptics, saveScans)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTargetScore(value: Int) = viewModelScope.launch { settingsRepository.setTargetScore(value) }

    fun setTorchDefaultOn(value: Boolean) =
        viewModelScope.launch { settingsRepository.setTorchDefaultOn(value) }

    fun setHapticsEnabled(value: Boolean) =
        viewModelScope.launch { settingsRepository.setHapticsEnabled(value) }

    fun setSaveScanPhotos(value: Boolean) =
        viewModelScope.launch { settingsRepository.setSaveScanPhotos(value) }
}
