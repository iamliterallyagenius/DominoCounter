package com.example.dominocounter.ui.match

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.navigation.fragment.navArgs
import com.example.dominocounter.R
import com.example.dominocounter.databinding.SheetEditRoundBinding
import com.example.dominocounter.domain.model.Rules
import com.example.dominocounter.domain.model.ScoreSource
import com.example.dominocounter.util.padForKeyboardOrNavigationBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint

/**
 * Edit or delete one audit-log entry. Both paths go through the repository, which refuses
 * the write in SQL if the match has since finished (R5).
 */
@AndroidEntryPoint
class EditRoundBottomSheet : BottomSheetDialogFragment() {

    private var binding: SheetEditRoundBinding? = null
    private val args: EditRoundBottomSheetArgs by navArgs()
    private val viewModel: MatchViewModel by hiltNavGraphViewModels(R.id.match_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetEditRoundBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = binding ?: return
        views.root.padForKeyboardOrNavigationBar()

        // The round is already in memory as part of the match state; re-querying would be
        // a second source of truth for the same row.
        val logged = viewModel.findRound(args.roundId)
        if (logged == null) {
            dismiss()
            return
        }
        val round = logged.round
        val sideLabel = viewModel.state.value?.sides?.getOrNull(round.side)?.label.orEmpty()

        views.sheetTitle.text = getString(R.string.edit_round_title, round.sequence)
        views.sheetSubtitle.text = "$sideLabel · ${getString(sourceLabel(round.source))}"
        views.pointsInput.setText(round.points.toString())

        views.saveButton.setOnClickListener { save() }
        views.deleteButton.setOnClickListener {
            viewModel.deleteRound(args.roundId)
            dismiss()
        }
    }

    private fun save() {
        val views = binding ?: return
        val points = views.pointsInput.text?.toString()?.trim()?.toIntOrNull()

        if (points == null || points <= 0 || points > Rules.MAX_MANUAL_POINTS) {
            views.pointsLayout.error = getString(R.string.add_round_points)
            return
        }
        views.pointsLayout.error = null

        viewModel.editRound(args.roundId, points)
        dismiss()
    }

    private fun sourceLabel(source: ScoreSource) = when (source) {
        ScoreSource.MANUAL -> R.string.source_manual
        ScoreSource.SCAN -> R.string.source_scan
        ScoreSource.FOUL -> R.string.source_foul
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
