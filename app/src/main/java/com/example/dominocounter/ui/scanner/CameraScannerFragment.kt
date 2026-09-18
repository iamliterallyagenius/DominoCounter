package com.example.dominocounter.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.dominocounter.DominoApplication
import com.example.dominocounter.R
import com.example.dominocounter.cv.AnalysisResult
import com.example.dominocounter.cv.DebugMode
import com.example.dominocounter.cv.DominoAnalyzer
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

@AndroidEntryPoint
class CameraScannerFragment : Fragment(R.layout.fragment_camera_scanner) {

    private val binding by viewBinding(FragmentCameraScannerBinding::bind)
    private val args: CameraScannerFragmentArgs by navArgs()

    /** Same instance the scoreboard holds — the confirmed score becomes a real round. */
    private val matchViewModel: MatchViewModel by hiltNavGraphViewModels(R.id.match_graph)
    private val scannerViewModel: ScannerViewModel by viewModels()

    @Inject lateinit var settingsRepository: SettingsRepository

    private var analysisExecutor: ExecutorService? = null
    private var analyzer: DominoAnalyzer? = null
    private var camera: Camera? = null

    /** Off by default (see Settings) — a diagnostic overlay has no business being one tap away. */
    private var debugEnabled = false

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else scannerViewModel.setCameraError(getString(R.string.status_permission_denied))
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.confirmButton.marginForNavigationBar()

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack(R.id.scoreboardFragment, false)
        }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.confirmButton.setOnClickListener { confirm() }

        collectWhileStarted(scannerViewModel.state, ::render)

        viewLifecycleOwner.lifecycleScope.launch {
            debugEnabled = settingsRepository.scannerDebugEnabled.first()
            scannerViewModel.setDebugAvailable(debugEnabled)
            if (debugEnabled) {
                binding.previewView.setOnClickListener { cycleDebugMode() }
            }
        }

        if (!DominoApplication.openCvLoaded) {
            scannerViewModel.setCameraError(getString(R.string.status_opencv_failed))
            return
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener(
            {
                try {
                    bindUseCases(providerFuture.get())
                } catch (t: Exception) {
                    scannerViewModel.setCameraError(
                        getString(R.string.status_camera_error, t.message ?: t.toString())
                    )
                }
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val cvAnalyzer = DominoAnalyzer { result -> onAnalysisResult(result) }.also { analyzer = it }

        val imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, cvAnalyzer) }

        provider.unbindAll()
        val boundCamera = provider.bindToLifecycle(
            viewLifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis
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

    /** Invoked on the analysis thread — hop to main before touching the ViewModel. */
    private fun onAnalysisResult(result: AnalysisResult) {
        val activity = activity ?: return
        activity.runOnUiThread {
            if (isAdded) scannerViewModel.onAnalysis(result)
        }
    }

    private fun cycleDebugMode() {
        val current = analyzer?.debugMode ?: return
        val next = current.next()
        analyzer?.debugMode = next
        scannerViewModel.setDebugMode(next)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        val enable = cam.cameraInfo.torchState.value != TorchState.ON
        cam.cameraControl.enableTorch(enable)
        scannerViewModel.setTorchOn(enable)
    }

    private fun confirm() {
        val state = scannerViewModel.state.value
        matchViewModel.commitScan(
            side = args.sideIndex,
            points = state.total,
            audit = ScanAudit(detectedTileCount = state.tileCount, rawPipTotal = state.rawTotal)
        )
        // Explicit target rather than navigateUp(): the add-round sheet pops itself when
        // entering this screen, but a plain "back one step" can land back on it instead of
        // the scoreboard if that pop hasn't settled yet. This always lands on the board,
        // exactly like a manual entry's dismiss() does.
        findNavController().popBackStack(R.id.scoreboardFragment, false)
    }

    private fun render(state: ScannerUiState) {
        binding.totalText.text = state.total.toString()
        binding.debugText.text = state.cameraError
            ?: getString(R.string.scan_debug_line, state.tileCount, state.rawTotal, state.frameTimeMs)

        binding.debugView.visibility = if (state.debugMode == DebugMode.OFF) View.GONE else View.VISIBLE
        binding.debugView.setImageBitmap(state.debugBitmap)

        binding.hintText.text = when (state.debugMode) {
            DebugMode.OFF -> if (state.debugAvailable) getString(R.string.hint_tap) else ""
            DebugMode.MASK -> getString(R.string.mode_mask)
            DebugMode.DETECTIONS -> getString(R.string.mode_detections)
        }

        binding.torchButton.isEnabled = state.torchAvailable
        binding.torchButton.text = getString(
            if (state.torchOn) R.string.torch_state_on else R.string.torch_state_off
        )

        // At least one real tile must have been seen — a blank (0-pip) tile is still valid.
        binding.confirmButton.isEnabled = state.cameraError == null && state.tileCount > 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        analyzer = null
        camera = null
    }
}
