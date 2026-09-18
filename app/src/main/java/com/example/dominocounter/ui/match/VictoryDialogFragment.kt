package com.example.dominocounter.ui.match

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.dominocounter.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The result is already saved by the time this shows (R3 + R5's completion transaction
 * already ran) — this dialog only celebrates and navigates.
 */
@AndroidEntryPoint
class VictoryDialogFragment : DialogFragment() {

    private val viewModel: MatchViewModel by hiltNavGraphViewModels(R.id.match_graph)

    init {
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val state = checkNotNull(viewModel.state.value) {
            "VictoryDialogFragment shown without a match state"
        }
        val outcome = checkNotNull(state.outcome)
        val winner = state.sides[outcome.winningSide]
        val scoreLine = state.sides.joinToString(" – ") { it.score.toString() }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.victory_title, winner.label))
            .setMessage(getString(R.string.victory_message, scoreLine))
            .setCancelable(false)
            .setPositiveButton(R.string.victory_return) { _, _ -> returnToMenu() }
            .setNegativeButton(R.string.victory_rematch) { _, _ -> rematch() }
            .create()
    }

    private fun returnToMenu() {
        findNavController().popBackStack(R.id.mainMenuFragment, false)
    }

    /**
     * Creates the new match first, then navigates — so if creation fails we never leave
     * the user staring at a graph that no longer has a match behind it.
     */
    private fun rematch() {
        lifecycleScope.launch {
            val newMatchId = viewModel.createRematch()
            findNavController().navigate(VictoryDialogFragmentDirections.toRematch(newMatchId))
        }
    }
}
