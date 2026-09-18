package com.example.dominocounter.ui.match

import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dominocounter.R
import com.example.dominocounter.data.repo.SettingsRepository
import com.example.dominocounter.databinding.FragmentScoreboardBinding
import com.example.dominocounter.databinding.ViewSidePanelBinding
import com.example.dominocounter.domain.model.MatchState
import com.example.dominocounter.domain.model.Rules
import com.example.dominocounter.domain.model.SideState
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScoreboardFragment : Fragment(R.layout.fragment_scoreboard) {

    private val binding by viewBinding(FragmentScoreboardBinding::bind)

    /** Graph-scoped: the same instance both bottom sheets use. */
    private val viewModel: MatchViewModel by hiltNavGraphViewModels(R.id.match_graph)

    @Inject lateinit var settingsRepository: SettingsRepository

    private val panels = mutableListOf<ViewSidePanelBinding>()
    private lateinit var logAdapter: RoundLogAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.logList.padForNavigationBar()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_abandon) {
                confirmAbandon(); true
            } else {
                false
            }
        }

        logAdapter = RoundLogAdapter(::onLogRowClicked)
        binding.logList.layoutManager = LinearLayoutManager(requireContext())
        binding.logList.adapter = logAdapter

        collectWhileStarted(viewModel.state) { state -> state?.let(::render) }
        collectWhileStarted(viewModel.events, ::handleEvent)
    }

    // ------------------------------------------------------------------ rendering

    private fun render(state: MatchState) {
        if (panels.isEmpty()) buildPanels(state)

        binding.toolbar.subtitle = getString(R.string.score_target, state.targetScore)

        state.sides.forEach { side ->
            val panel = panels[side.index]
            panel.sideName.text = side.label
            panel.sideScore.text = side.score.toString()
            panel.sideToWin.text = getString(R.string.score_to_win, side.pointsToWin)

            // The leading side gets a stronger tint rather than a different layout —
            // cheap to render and it survives a three-panel free-for-all.
            panel.applySideColor(requireContext(), side.index, side.isLeading)

            panel.foulButton.isEnabled = state.isEditable
            panel.addScoreButton.isEnabled = state.isEditable
        }

        logAdapter.sideLabels = state.sides.map(SideState::label)
        logAdapter.submitList(state.log)
        binding.logEmpty.visibility = if (state.log.isEmpty()) View.VISIBLE else View.GONE
        binding.toolbar.menu.findItem(R.id.action_abandon)?.isVisible = state.isEditable
    }

    /**
     * Two panels or three, from one layout. The score shrinks for a free-for-all so three
     * columns still fit a phone without truncating.
     */
    private fun buildPanels(state: MatchState) {
        val inflater = LayoutInflater.from(requireContext())
        val scoreSize = if (state.sides.size >= 3) SCORE_SP_THREE else SCORE_SP_TWO

        state.sides.forEach { side ->
            val panel = ViewSidePanelBinding.inflate(inflater, binding.sideContainer, true)
            panel.sideScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, scoreSize)
            panel.foulButton.text = getString(R.string.score_foul, Rules.FOUL_POINTS)
            panel.foulButton.setOnClickListener {
                viewModel.addFoul(side.index)
                maybeBuzz(panel.foulButton)
            }
            panel.addScoreButton.setOnClickListener {
                findNavController().navigate(
                    ScoreboardFragmentDirections.toAddRound(side.index)
                )
            }
            panels += panel
        }
    }

    // ------------------------------------------------------------------ interaction

    private fun onLogRowClicked(row: com.example.dominocounter.domain.model.LoggedRound) {
        val state = viewModel.state.value ?: return
        if (!state.isEditable) {
            Snackbar.make(requireView(), R.string.score_locked, Snackbar.LENGTH_SHORT).show()
            return
        }
        findNavController().navigate(ScoreboardFragmentDirections.toEditRound(row.round.id))
    }

    private fun confirmAbandon() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.score_abandon)
            .setMessage(R.string.score_abandon_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.score_abandon) { _, _ -> viewModel.abandon() }
            .show()
    }

    private fun handleEvent(event: MatchEvent) {
        when (event) {
            is MatchEvent.Victory ->
                findNavController().navigate(ScoreboardFragmentDirections.toVictory())
            is MatchEvent.Abandoned -> returnToMenu()
            is MatchEvent.Error ->
                Snackbar.make(requireView(), event.messageRes, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun maybeBuzz(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (settingsRepository.hapticsEnabled.first()) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }

    private fun returnToMenu() {
        findNavController().popBackStack(R.id.mainMenuFragment, false)
    }

    /**
     * The fragment instance survives a trip to the scanner and back, but its view doesn't —
     * `sideContainer` comes back empty. Without this, `panels` still holds bindings for the
     * old (now-destroyed) container, so `render()`'s `if (panels.isEmpty())` guard thinks
     * the panels already exist and never re-inflates them into the new one.
     */
    override fun onDestroyView() {
        panels.clear()
        super.onDestroyView()
    }

    private companion object {
        const val SCORE_SP_TWO = 56f
        const val SCORE_SP_THREE = 40f
    }
}
