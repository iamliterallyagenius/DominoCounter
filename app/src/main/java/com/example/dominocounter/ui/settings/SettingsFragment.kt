package com.example.dominocounter.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.dominocounter.R
import com.example.dominocounter.databinding.FragmentSettingsBinding
import com.example.dominocounter.util.AppLanguage
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val binding by viewBinding(FragmentSettingsBinding::bind)
    private val viewModel: SettingsViewModel by viewModels()

    /** Guards against re-writing a preference while we're the ones applying it to the view. */
    private var applyingState = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.settingsScroll.padForNavigationBar()
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.languageValue.setText(AppLanguage.current().label)
        binding.languageRow.setOnClickListener { showLanguageChooser() }

        binding.targetScoreSlider.addOnChangeListener { _, value, fromUser ->
            binding.targetScoreValue.text = getString(R.string.settings_target_score_value, value.toInt())
            if (fromUser) viewModel.setTargetScore(value.toInt())
        }
        binding.torchSwitch.setOnCheckedChangeListener { _, checked ->
            if (!applyingState) viewModel.setTorchDefaultOn(checked)
        }
        binding.hapticsSwitch.setOnCheckedChangeListener { _, checked ->
            if (!applyingState) viewModel.setHapticsEnabled(checked)
        }
        binding.saveScansSwitch.setOnCheckedChangeListener { _, checked ->
            if (!applyingState) viewModel.setSaveScanPhotos(checked)
        }

        collectWhileStarted(viewModel.uiState, ::render)
    }

    private fun showLanguageChooser() {
        val languages = AppLanguage.entries
        val current = AppLanguage.current()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(
                languages.map { getString(it.label) }.toTypedArray(),
                languages.indexOf(current)
            ) { dialog, which ->
                dialog.dismiss()
                // Applying re-creates the activity, so only do it for an actual change.
                if (languages[which] != current) languages[which].apply()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render(state: SettingsUiState) {
        applyingState = true

        if (binding.targetScoreSlider.value.toInt() != state.targetScore) {
            binding.targetScoreSlider.value = state.targetScore.toFloat()
                .coerceIn(binding.targetScoreSlider.valueFrom, binding.targetScoreSlider.valueTo)
        }
        binding.targetScoreValue.text = getString(R.string.settings_target_score_value, state.targetScore)
        binding.torchSwitch.isChecked = state.torchDefaultOn
        binding.hapticsSwitch.isChecked = state.hapticsEnabled
        binding.saveScansSwitch.isChecked = state.saveScanPhotos

        applyingState = false
    }
}
