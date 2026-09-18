package com.example.dominocounter.ui.stats

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dominocounter.R
import com.example.dominocounter.data.db.relation.PlayerRecord
import com.example.dominocounter.databinding.FragmentPlayerStatsBinding
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlayerStatsFragment : Fragment(R.layout.fragment_player_stats) {

    private val binding by viewBinding(FragmentPlayerStatsBinding::bind)
    private val viewModel: PlayerStatsViewModel by viewModels()

    private lateinit var leaderboardAdapter: PlayerPairingAdapter
    private lateinit var synergyAdapter: PlayerPairingAdapter
    private lateinit var headToHeadAdapter: PlayerPairingAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.padForStatusBar()
        binding.statsScroll.padForNavigationBar()
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        leaderboardAdapter = PlayerPairingAdapter { row -> viewModel.selectPlayer(row.playerId, row.displayName) }
        binding.leaderboardList.layoutManager = LinearLayoutManager(requireContext())
        binding.leaderboardList.adapter = leaderboardAdapter

        // Tapping a synergy/rival row jumps the detail section to that player too.
        synergyAdapter = PlayerPairingAdapter { row -> viewModel.selectPlayer(row.playerId, row.displayName) }
        binding.synergyList.layoutManager = LinearLayoutManager(requireContext())
        binding.synergyList.adapter = synergyAdapter

        headToHeadAdapter = PlayerPairingAdapter { row -> viewModel.selectPlayer(row.playerId, row.displayName) }
        binding.headToHeadList.layoutManager = LinearLayoutManager(requireContext())
        binding.headToHeadList.adapter = headToHeadAdapter

        collectWhileStarted(viewModel.uiState, ::render)
    }

    private fun render(state: PlayerStatsUiState) {
        leaderboardAdapter.submitList(state.leaderboard)
        binding.leaderboardEmpty.visibility = if (state.leaderboard.isEmpty()) View.VISIBLE else View.GONE

        val hasSelection = state.selectedPlayerId != null
        binding.selectedHeader.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.selectedHeader.text = state.selectedName.orEmpty()
        binding.detailEmpty.visibility = if (hasSelection) View.GONE else View.VISIBLE
        binding.recordHeader.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.synergyHeader.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.headToHeadHeader.visibility = if (hasSelection) View.VISIBLE else View.GONE

        renderRecords(state.records)
        synergyAdapter.submitList(state.synergy)
        headToHeadAdapter.submitList(state.headToHead)
    }

    private fun renderRecords(records: List<PlayerRecord>) {
        binding.recordContainer.removeAllViews()
        records.forEach { record ->
            val row = TextView(requireContext())
            row.text = getString(
                R.string.stats_format_row,
                getString(formatLabelRes(record.format)),
                record.won,
                record.played,
                (record.winRate * 100).toInt()
            )
            row.setPadding(0, 8, 0, 8)
            binding.recordContainer.addView(row)
        }
    }

    private fun formatLabelRes(format: MatchFormat) = when (format) {
        MatchFormat.ONE_V_ONE -> R.string.format_one_v_one
        MatchFormat.TWO_V_TWO -> R.string.format_two_v_two
        MatchFormat.FREE_FOR_ALL -> R.string.format_free_for_all
    }
}
