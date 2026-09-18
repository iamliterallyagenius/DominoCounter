package com.example.dominocounter.ui.history

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.dominocounter.R
import com.example.dominocounter.databinding.FragmentMatchHistoryDetailBinding
import com.example.dominocounter.databinding.ViewSidePanelBinding
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.MatchState
import com.example.dominocounter.domain.model.SideState
import com.example.dominocounter.ui.match.RoundLogAdapter
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date

/** A locked, read-only view of a finished match — same visuals as the live scoreboard. */
@AndroidEntryPoint
class MatchHistoryDetailFragment : Fragment(R.layout.fragment_match_history_detail) {

    private val binding by viewBinding(FragmentMatchHistoryDetailBinding::bind)
    private val viewModel: MatchHistoryDetailViewModel by viewModels()

    private val panels = mutableListOf<ViewSidePanelBinding>()
    private lateinit var logAdapter: RoundLogAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.logList.padForNavigationBar()
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        logAdapter = RoundLogAdapter { /* read-only: rows are inert */ }
        binding.logList.layoutManager = LinearLayoutManager(requireContext())
        binding.logList.adapter = logAdapter

        collectWhileStarted(viewModel.state) { state -> state?.let(::render) }
    }

    private fun render(state: MatchState) {
        if (panels.isEmpty()) buildPanels(state)

        binding.toolbar.title = getString(formatLabelRes(state.format))
        binding.toolbar.subtitle = DateFormat.getMediumDateFormat(requireContext())
            .format(Date(state.finishedAt ?: state.startedAt))

        state.sides.forEach { side ->
            val panel = panels[side.index]
            panel.sideName.text = side.label
            panel.sideScore.text = side.score.toString()
            panel.sideToWin.text = getString(R.string.score_to_win, side.pointsToWin)
            panel.sideCard.strokeWidth = if (side.isLeading) LEADING_STROKE_DP.dp() else 0
            panel.sideCard.strokeColor = ContextCompat.getColor(
                requireContext(), RoundLogAdapter.sideColor(side.index)
            )
        }

        logAdapter.sideLabels = state.sides.map(SideState::label)
        logAdapter.submitList(state.log)
    }

    private fun buildPanels(state: MatchState) {
        val inflater = LayoutInflater.from(requireContext())
        val scoreSize = if (state.sides.size >= 3) SCORE_SP_THREE else SCORE_SP_TWO

        state.sides.forEach { side ->
            val panel = ViewSidePanelBinding.inflate(inflater, binding.sideContainer, true)
            panel.sideScore.setTextSize(TypedValue.COMPLEX_UNIT_SP, scoreSize)
            panel.sideStripe.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), RoundLogAdapter.sideColor(side.index))
            )
            // A finished match has nothing left to add — this view is look-only.
            panel.foulButton.visibility = View.GONE
            panel.addScoreButton.visibility = View.GONE
            panels += panel
        }
    }

    private fun formatLabelRes(format: MatchFormat) = when (format) {
        MatchFormat.ONE_V_ONE -> R.string.format_one_v_one
        MatchFormat.TWO_V_TWO -> R.string.format_two_v_two
        MatchFormat.FREE_FOR_ALL -> R.string.format_free_for_all
    }

    override fun onDestroyView() {
        panels.clear()
        super.onDestroyView()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val SCORE_SP_TWO = 56f
        const val SCORE_SP_THREE = 40f
        const val LEADING_STROKE_DP = 3
    }
}
