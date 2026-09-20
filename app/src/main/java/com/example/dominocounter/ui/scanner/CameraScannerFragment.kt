package com.example.dominocounter.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.dominocounter.R
import com.example.dominocounter.data.repo.SettingsRepository
import com.example.dominocounter.databinding.FragmentCameraScannerBinding
import com.example.dominocounter.domain.model.ScanAudit
import com.example.dominocounter.ui.match.MatchViewModel
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.marginForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Point, shoot, review: the player takes one photo of the tiles, the model marks every pip it
 * finds, and the player fixes any miss with a tap before the total is committed as a round.
 */
@AndroidEntryPoint
class CameraScannerFragment : Fragment(R.layout.fragment_camera_scanner) {

    private val binding by viewBinding(FragmentCameraScannerBinding::bind)
    private val args: CameraScannerFragmentArgs by navArgs()

    /** Same instance the scoreboard holds — the confirmed score becomes a real round. */
    private val matchViewModel: MatchViewModel by hiltNavGraphViewModels(R.id.match_graph)
    private val scannerViewModel: ScannerViewModel by viewModels()

    @Inject lateinit var settingsRepository: SettingsRepository

    private var captureExecutor: ExecutorService? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else scannerViewModel.setError(ScanError.PermissionDenied)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.controls.marginForNavigationBar()

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack(R.id.scoreboardFragment, false)
        }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.captureButton.setOnClickListener { capture() }
        binding.retakeButton.setOnClickListener { scannerViewModel.retake() }
        binding.confirmButton.setOnClickListener { confirm() }
        binding.reviewView.onTapAt = scannerViewModel::onReviewTap

        collectWhileStarted(scannerViewModel.state, ::render)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener(
            {
                try {
                    bindUseCases(providerFuture.get())
                } catch (t: Exception) {
                    scannerViewModel.setError(ScanError.Camera(t.message ?: t.toString()))
                }
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        // Preview and still share one aspect ratio so the preview shows exactly what gets scanned.
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
            .also { it.surfaceProvider = binding.previewView.surfaceProvider }

        // Enough pixels for small pips, capped so a 50 MP sensor doesn't hand us a 200 MB bitmap.
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(MAX_CAPTURE_LONG_SIDE, MAX_CAPTURE_LONG_SIDE * 3 / 4),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .build()
        imageCapture = capture
        captureExecutor = captureExecutor ?: Executors.newSingleThreadExecutor()

        provider.unbindAll()
        val boundCamera = provider.bindToLifecycle(
            viewLifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture
        )
        camera = boundCamera

        val hasFlash = boundCamera.cameraInfo.hasFlashUnit()
        scannerViewModel.setTorchAvailable(hasFlash)
        if (hasFlash) {
            viewLifecycleOwner.lifecycleScope.launch {
                if (settingsRepository.torchDefaultOn.first()) {
                    boundCamera.cameraControl.enableTorch(true)
                    scannerViewModel.setTorchOn(true)
                }
            }
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        val enable = cam.cameraInfo.torchState.value != TorchState.ON
        cam.cameraControl.enableTorch(enable)
        scannerViewModel.setTorchOn(enable)
    }

    private fun capture() {
        val capture = imageCapture ?: return
        val executor = captureExecutor ?: return
        if (!scannerViewModel.state.value.canScan) return

        scannerViewModel.onShutter()
        // Callbacks arrive on the capture executor, not the main thread, so they only talk to
        // the ViewModel (which is thread-safe) and never touch views.
        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val photo = try {
                        image.use { it.toUprightBitmap() }
                    } catch (t: Throwable) {
                        scannerViewModel.onCaptureFailed(t.message ?: t.toString())
                        return
                    }
                    scannerViewModel.onPhoto(photo)
                }

                override fun onError(exception: ImageCaptureException) {
                    scannerViewModel.onCaptureFailed(exception.message ?: exception.toString())
                }
            }
        )
    }

    /** CameraX hands over sensor-orientation pixels plus the rotation needed to make them upright. */
    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val raw = toBitmap()
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val rotated = Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true
        )
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    // ------------------------------------------------------------------ result

    private fun confirm() {
        val state = scannerViewModel.state.value
        if (state.phase != ScanPhase.REVIEW || state.total <= 0) return

        matchViewModel.commitScan(
            side = args.sideIndex,
            points = state.total,
            audit = ScanAudit(detectedTileCount = state.detectionCount, rawPipTotal = state.detectedTotal)
        )
        scannerViewModel.onConfirmed()
        // Explicit target rather than navigateUp(): the add-round sheet pops itself when
        // entering this screen, but a plain "back one step" can land back on it instead of
        // the scoreboard if that pop hasn't settled yet. This always lands on the board,
        // exactly like a manual entry's dismiss() does.
        findNavController().popBackStack(R.id.scoreboardFragment, false)
    }

    private fun render(state: ScannerUiState) {
        val reviewing = state.phase == ScanPhase.REVIEW

        binding.reviewView.isVisible = reviewing
        if (reviewing) {
            state.photo?.let { binding.reviewView.showPhoto(it, state.photoWidth, state.photoHeight) }
            binding.reviewView.setPips(state.pips)
        }

        binding.progress.isVisible = state.phase == ScanPhase.ANALYZING
        binding.captureButton.isVisible = !reviewing
        binding.captureButton.isEnabled = state.canScan
        binding.reviewButtons.isVisible = reviewing
        binding.confirmButton.isEnabled = reviewing && state.total > 0

        binding.labelText.isVisible = reviewing
        binding.totalText.isVisible = reviewing
        binding.totalText.text = state.total.toString()
        binding.timingText.isVisible = reviewing
        binding.timingText.text = getString(R.string.scan_timing, state.inferenceMs)

        binding.hintText.text = when {
            state.error != null -> messageFor(state.error)
            state.phase == ScanPhase.ANALYZING -> getString(R.string.scan_analyzing)
            reviewing -> getString(if (state.total == 0) R.string.scan_hint_empty else R.string.scan_hint_review)
            else -> getString(R.string.scan_hint_capture)
        }

        binding.torchButton.isVisible = !reviewing
        binding.torchButton.isEnabled = state.torchAvailable
        binding.torchButton.text = getString(
            if (state.torchOn) R.string.torch_state_on else R.string.torch_state_off
        )
    }

    private fun messageFor(error: ScanError): String = when (error) {
        ScanError.PermissionDenied -> getString(R.string.status_permission_denied)
        ScanError.ModelMissing -> getString(R.string.status_model_missing)
        is ScanError.Camera -> getString(R.string.status_camera_error, error.detail)
        is ScanError.Model -> getString(R.string.status_model_failed, error.detail)
        is ScanError.Detect -> getString(R.string.status_detect_failed, error.detail)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        captureExecutor?.shutdown()
        captureExecutor = null
        imageCapture = null
        camera = null
    }

    private companion object {
        const val MAX_CAPTURE_LONG_SIDE = 3264
    }
}
