package com.example.dominocounter.ui.menu

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.dominocounter.R
import com.example.dominocounter.databinding.FragmentMainMenuBinding
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainMenuFragment : Fragment(R.layout.fragment_main_menu) {

    private val binding by viewBinding(FragmentMainMenuBinding::bind)
    private val viewModel: MainMenuViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.root.padForStatusBar()
        binding.root.padForNavigationBar()

        binding.newGameButton.setOnClickListener {
            findNavController().navigate(MainMenuFragmentDirections.toGameSetup())
        }
        binding.statsButton.setOnClickListener {
            findNavController().navigate(MainMenuFragmentDirections.toPlayerStats())
        }
        binding.historyButton.setOnClickListener {
            findNavController().navigate(MainMenuFragmentDirections.toGameHistory())
        }
        binding.settingsButton.setOnClickListener {
            findNavController().navigate(MainMenuFragmentDirections.toSettings())
        }

        collectWhileStarted(viewModel.resumable) { summary ->
            binding.resumeCard.visibility = if (summary == null) View.GONE else View.VISIBLE
            if (summary != null) {
                binding.resumeSubtitle.text = getString(
                    R.string.resume_subtitle,
                    summary.playerNames.orEmpty(),
                    summary.roundCount
                )
                binding.resumeButton.setOnClickListener {
                    findNavController().navigate(MainMenuFragmentDirections.toMatch(summary.id))
                }
            }
        }
    }
}
