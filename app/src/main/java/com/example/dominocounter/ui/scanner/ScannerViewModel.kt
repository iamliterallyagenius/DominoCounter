package com.example.dominocounter.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.data.repo.SettingsRepository
import com.example.dominocounter.detect.ModelMissingException
import com.example.dominocounter.detect.PipDetector
import com.example.dominocounter.detect.PipEditor
import com.example.dominocounter.detect.ReviewPip
import com.example.dominocounter.detect.ScanPhotoSaver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

enum class ScanPhase {
    /** Live camera; waiting for the shutter. */
    PREVIEW,

    /** Photo taken, the model is counting. */
    ANALYZING,

    /** Photo on screen with markers the player can correct. */
    REVIEW
}

/** What stops or interrupts a scan. The fragment turns these into localized text. */
sealed interface ScanError {
    data object PermissionDenied : ScanError
    data object ModelMissing : ScanError
    data class Camera(val detail: String) : ScanError
    data class Model(val detail: String) : ScanError

    /** One photo failed; the next one may well work. */
    data class Detect(val detail: String) : ScanError
}

data class ScannerUiState(
    val phase: ScanPhase = ScanPhase.PREVIEW,

    /** The scanned photo, downscaled for display. Null outside [ScanPhase.REVIEW]. */
    val photo: Bitmap? = null,

    /** Size of the photo that was scanned — the coordinate space of [pips]. */
    val photoWidth: Int = 0,
    val photoHeight: Int = 0,

    /** What the player currently sees; starts as the detector's answer and can be corrected. */
    val pips: List<ReviewPip> = emptyList(),

    /** What the model reported BEFORE any correction — kept for the scan audit trail. */
    val detectedTotal: Int = 0,
    val detectionCount: Int = 0,
    val inferenceMs: Long = 0,

    val torchOn: Boolean = false,
    val torchAvailable: Boolean = false,
    val error: ScanError? = null
) {
    /** The score that will be committed. */
    val total: Int get() = PipEditor.total(pips)

    /** Everything but a one-off failed photo blocks scanning. */
    val canScan: Boolean get() = phase == ScanPhase.PREVIEW && (error == null || error is ScanError.Detect)
}

/** Holds the scan flow across configuration changes and owns the model. */
@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val photoSaver: ScanPhotoSaver
) : ViewModel() {

    private val detector = PipDetector(context)

    /** The scanned photo as a JPEG, kept only while the player has opted in to saving scans. */
    private var pendingJpeg: ByteArray? = null

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    init {
        // Load the model while the camera starts, so the first scan isn't the slow one and a
        // missing or broken model is reported straight away instead of after the first photo.
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { detector.warmUp() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ModelMissingException) {
                setError(ScanError.ModelMissing)
            } catch (t: Throwable) {
                // Includes native-load failures (UnsatisfiedLinkError): a scanner that can't
                // start should say so, not take the whole app down.
                setError(ScanError.Model(t.message ?: t.toString()))
            }
        }
    }

    // ------------------------------------------------------------------ scan flow

    fun onShutter() = _state.update {
        it.copy(phase = ScanPhase.ANALYZING, error = it.error.takeUnless { e -> e is ScanError.Detect })
    }

    /**
     * Takes ownership of [photo] (upright, full resolution) and counts the pips in it. Safe to
     * call from any thread.
     */
    fun onPhoto(photo: Bitmap) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { detector.detect(photo) }
                val pips = result.pips.map { ReviewPip(it.x, it.y, it.w, it.h, it.value) }
                // Encoded before the bitmap can be recycled below. A failure here only costs the
                // saved copy, never the scan.
                val jpeg = if (settingsRepository.saveScanPhotos.first()) {
                    withContext(Dispatchers.Default) { runCatching { encodeJpeg(photo) }.getOrNull() }
                } else {
                    null
                }
                val display = withContext(Dispatchers.Default) { displayCopy(photo) }
                pendingJpeg = jpeg
                val width = photo.width
                val height = photo.height
                if (display !== photo) photo.recycle()

                _state.update {
                    it.copy(
                        phase = ScanPhase.REVIEW,
                        photo = display,
                        photoWidth = width,
                        photoHeight = height,
                        pips = pips,
                        detectedTotal = PipEditor.total(pips),
                        detectionCount = pips.size,
                        inferenceMs = result.inferenceMs
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ModelMissingException) {
                backToPreview(ScanError.ModelMissing)
            } catch (t: Throwable) {
                // Out-of-memory on a huge photo is the realistic case; report it, don't crash.
                backToPreview(ScanError.Detect(t.message ?: t.toString()))
            }
        }
    }

    fun onCaptureFailed(detail: String) = backToPreview(ScanError.Detect(detail))

    /** Tap on a marker removes it; tap on bare table adds a pip. */
    fun onReviewTap(x: Float, y: Float, hitRadius: Float) = _state.update {
        if (it.phase != ScanPhase.REVIEW) it else it.copy(pips = PipEditor.toggleAt(it.pips, x, y, hitRadius))
    }

    fun retake() {
        pendingJpeg = null
        _state.update {
            it.copy(phase = ScanPhase.PREVIEW, photo = null, pips = emptyList(), detectedTotal = 0, detectionCount = 0)
        }
    }

    /** Call as the score is committed: keeps the photo, labelled with the totals, if the player opted in. */
    fun onConfirmed() {
        val jpeg = pendingJpeg ?: return
        pendingJpeg = null
        val current = _state.value
        photoSaver.save(jpeg, confirmedTotal = current.total, modelTotal = current.detectedTotal)
    }

    // ------------------------------------------------------------------ camera state

    fun setTorchAvailable(available: Boolean) = _state.update { it.copy(torchAvailable = available) }

    fun setTorchOn(on: Boolean) = _state.update { it.copy(torchOn = on) }

    fun setError(error: ScanError?) = _state.update { it.copy(error = error) }

    private fun backToPreview(error: ScanError) {
        pendingJpeg = null
        _state.update { it.copy(phase = ScanPhase.PREVIEW, photo = null, pips = emptyList(), error = error) }
    }

    /** Near-lossless: this is data for tuning the model, not a snapshot to share. */
    private fun encodeJpeg(photo: Bitmap): ByteArray =
        ByteArrayOutputStream(photo.byteCount / 8).also {
            photo.compress(Bitmap.CompressFormat.JPEG, SAVED_JPEG_QUALITY, it)
        }.toByteArray()

    /** The full-resolution photo is only needed for counting; the screen gets a lighter copy. */
    private fun displayCopy(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= DISPLAY_MAX_PX) return source
        val ratio = DISPLAY_MAX_PX / longest.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt(),
            (source.height * ratio).roundToInt(),
            true
        )
    }

    override fun onCleared() {
        detector.close()
    }

    private companion object {
        /** Kept under typical GPU texture limits so drawing the photo can't fail. */
        const val DISPLAY_MAX_PX = 2048
        const val SAVED_JPEG_QUALITY = 95
    }
}
