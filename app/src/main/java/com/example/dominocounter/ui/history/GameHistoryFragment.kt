package com.example.dominocounter.ui.history

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dominocounter.R
import com.example.dominocounter.databinding.FragmentGameHistoryBinding
import com.example.dominocounter.domain.model.MatchState
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GameHistoryFragment : Fragment(R.layout.fragment_game_history) {

    private val binding by viewBinding(FragmentGameHistoryBinding::bind)
    private val viewModel: GameHistoryViewModel by viewModels()

    private lateinit var adapter: MatchHistoryAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.historyList.padForNavigationBar()
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        adapter = MatchHistoryAdapter(::openMatch, ::confirmAbandon)
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter

        collectWhileStarted(viewModel.history) { history ->
            adapter.submitList(history)
            binding.historyEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** A finished match opens read-only; one still being played goes back to its live scoreboard. */
    private fun openMatch(match: MatchState) {
        val directions = if (match.isEditable) {
            GameHistoryFragmentDirections.toMatch(match.matchId)
        } else {
            GameHistoryFragmentDirections.toMatchHistoryDetail(match.matchId)
        }
        findNavController().navigate(directions)
    }

    private fun confirmAbandon(match: MatchState) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.score_abandon)
            .setMessage(R.string.score_abandon_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.score_abandon) { _, _ -> viewModel.abandonMatch(match.matchId) }
            .show()
    }
}
