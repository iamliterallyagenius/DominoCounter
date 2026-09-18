package com.example.dominocounter.ui.scanner

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.example.dominocounter.cv.AnalysisResult
import com.example.dominocounter.cv.DebugMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ScannerUiState(
    /** The stabilised count — what gets committed as the round's score. */
    val total: Int = 0,
    /** This frame's unfiltered measurement, kept for the scan audit trail. */
    val rawTotal: Int = 0,
    val tileCount: Int = 0,
    val frameTimeMs: Long = 0,
    val debugBitmap: Bitmap? = null,
    val debugMode: DebugMode = DebugMode.OFF,
    val torchOn: Boolean = false,
    val torchAvailable: Boolean = false,
    val cameraError: String? = null,
    /** Gated by Settings — the mask/detections overlay isn't a player-facing feature. */
    val debugAvailable: Boolean = false
)

/** Holds the live CV feed and torch state so rotation doesn't restart the camera bind. */
@HiltViewModel
class ScannerViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    /** Called from the analysis thread's callback, already hopped to main by the caller. */
    fun onAnalysis(result: AnalysisResult) {
        _state.update {
            it.copy(
                total = result.stableTotal,
                rawTotal = result.rawTotal,
                tileCount = result.tileCount,
                frameTimeMs = result.frameTimeMs,
                debugBitmap = result.debugBitmap ?: it.debugBitmap
            )
        }
    }

    fun setDebugMode(mode: DebugMode) = _state.update { it.copy(debugMode = mode) }

    fun setTorchAvailable(available: Boolean) = _state.update { it.copy(torchAvailable = available) }

    fun setTorchOn(on: Boolean) = _state.update { it.copy(torchOn = on) }

    fun setCameraError(message: String?) = _state.update { it.copy(cameraError = message) }

    fun setDebugAvailable(available: Boolean) = _state.update { it.copy(debugAvailable = available) }
}
