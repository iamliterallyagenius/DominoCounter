package com.example.dominocounter.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.dominocounter.R
import com.example.dominocounter.databinding.FragmentGameSetupBinding
import com.example.dominocounter.databinding.ItemSeatBinding
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.util.collectWhileStarted
import com.example.dominocounter.util.padForKeyboardOrNavigationBar
import com.example.dominocounter.util.padForStatusBar
import com.example.dominocounter.util.viewBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GameSetupFragment : Fragment(R.layout.fragment_game_setup) {

    private val binding by viewBinding(FragmentGameSetupBinding::bind)
    private val viewModel: GameSetupViewModel by viewModels()

    /** Four rows, inflated once. Fewer moving parts than a RecyclerView of EditTexts. */
    private val seatRows = mutableListOf<ItemSeatBinding>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        binding.setupScroll.padForKeyboardOrNavigationBar()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        buildSeatRows()

        binding.addPlayerButton.setOnClickListener { viewModel.addPlayer() }
        binding.startButton.setOnClickListener { viewModel.start() }

        collectWhileStarted(viewModel.uiState) { render(it) }
        collectWhileStarted(viewModel.suggestions) { names ->
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
            seatRows.forEach { it.seatInput.setAdapter(adapter) }
        }
        collectWhileStarted(viewModel.events) { event ->
            when (event) {
                is SetupEvent.MatchCreated ->
                    findNavController().navigate(GameSetupFragmentDirections.toMatch(event.matchId))

                is SetupEvent.Error ->
                    Snackbar.make(requireView(), event.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun buildSeatRows() {
        val inflater = LayoutInflater.from(requireContext())
        repeat(MAX_SEATS) { index ->
            val row = ItemSeatBinding.inflate(inflater, binding.seatContainer, true)
            row.seatInput.doAfterTextChanged { viewModel.setName(index, it?.toString().orEmpty()) }
            row.seatLayout.setEndIconOnClickListener { viewModel.removePlayer(index) }

            // Suggestions appear the moment the field is focused, not only once typing
            // starts — tapping an empty seat should immediately offer "who usually sits here."
            row.seatInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) row.seatInput.showDropDown() }
            row.seatInput.setOnClickListener { row.seatInput.showDropDown() }

            seatRows += row
        }
    }

    private fun render(state: SetupUiState) {
        binding.formatLabel.text = getString(formatLabelRes(state.format))
        binding.addPlayerButton.isEnabled = state.canAddPlayer

        seatRows.forEachIndexed { index, row ->
            val seat = state.seats.getOrNull(index)
            row.root.visibility = if (seat == null) View.GONE else View.VISIBLE
            if (seat == null) return@forEachIndexed

            row.seatLayout.hint = seatHint(state.format, seat)
            row.seatLayout.error = if (seat.isDuplicate) getString(R.string.setup_error_duplicate) else null
            row.seatLayout.isEndIconVisible = state.canRemovePlayer

            // Only push text back when it actually diverges (rotation, restore) — writing
            // on every state emission would fight the user's cursor.
            if (row.seatInput.text.toString() != seat.name && !row.seatInput.hasFocus()) {
                row.seatInput.setText(seat.name)
            }
        }

        binding.startButton.isEnabled = state.canStart
    }

    private fun seatHint(format: MatchFormat, seat: SeatUi): String =
        if (format.hasPartners) {
            getString(
                R.string.setup_seat_hint_team,
                getString(if (seat.side == 0) R.string.team_a else R.string.team_b),
                seat.seatInSide + 1
            )
        } else {
            getString(R.string.setup_seat_hint_solo, seat.index + 1)
        }

    private fun formatLabelRes(format: MatchFormat) = when (format) {
        MatchFormat.ONE_V_ONE -> R.string.format_one_v_one
        MatchFormat.TWO_V_TWO -> R.string.format_two_v_two
        MatchFormat.FREE_FOR_ALL -> R.string.format_free_for_all
    }

    private companion object {
        const val MAX_SEATS = 4
    }
}
