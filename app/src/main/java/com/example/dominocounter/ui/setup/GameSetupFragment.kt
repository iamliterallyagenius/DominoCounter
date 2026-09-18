package com.example.dominocounter.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.dominocounter.R
import com.example.dominocounter.databinding.FragmentGameSetupBinding
import com.example.dominocounter.databinding.ItemSeatBinding
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.ui.match.RoundLogAdapter
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

    private lateinit var suggestions: SuggestionOverlay

    private var keyboardOpen = false

    /** Set while a suggestion is being written into a seat, so that edit doesn't reopen the list. */
    private var picking = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.padForStatusBar()
        suggestions = SuggestionOverlay(
            binding.suggestionOverlay,
            binding.suggestionCard,
            binding.suggestionScroll,
            binding.suggestionList
        )
        binding.setupScroll.padForKeyboardOrNavigationBar { keyboardHeight ->
            keyboardOpen = keyboardHeight > 0
            focusedSeat()?.let(::scrollSeatAboveKeyboard)
            suggestions.reposition()
        }
        // The card floats over the form, so it has to follow the field as the form scrolls.
        binding.setupScroll.setOnScrollChangeListener { _, _, _, _, _ -> suggestions.reposition() }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        buildSeatRows()

        binding.addPlayerButton.setOnClickListener { viewModel.addPlayer() }
        binding.startButton.setOnClickListener { viewModel.start() }

        collectWhileStarted(viewModel.uiState) { render(it) }
        collectWhileStarted(viewModel.suggestions) { suggestions.setNames(it) }
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
            row.seatInput.doAfterTextChanged {
                viewModel.setName(index, it?.toString().orEmpty())
                if (row.seatInput.hasFocus() && !picking) suggestions.refresh()
            }
            row.seatLayout.setEndIconOnClickListener { viewModel.removePlayer(index) }

            // Suggestions appear the moment the field is focused, not only once typing
            // starts — tapping an empty seat should immediately offer "who usually sits here."
            row.seatInput.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showSuggestions(row)
                    // Already open when moving between seats; otherwise the keyboard's own
                    // arrival triggers the scroll.
                    if (keyboardOpen) scrollSeatAboveKeyboard(row)
                } else {
                    suggestions.detach(row.seatInput)
                }
            }
            // Tapping the field again brings the list back after a pick dismissed it.
            row.seatInput.setOnClickListener { showSuggestions(row) }

            seatRows += row
        }
    }

    /** Shows the suggestions for whatever is already in the seat — everyone when it's empty. */
    private fun showSuggestions(row: ItemSeatBinding) {
        suggestions.attach(row.seatInput, row.seatLayout) { name -> pickSuggestion(row, name) }
    }

    private fun pickSuggestion(row: ItemSeatBinding, name: String) {
        picking = true
        row.seatInput.setText(name)
        row.seatInput.setSelection(name.length)
        picking = false
        suggestions.detach(row.seatInput)
    }

    private fun focusedSeat() = seatRows.firstOrNull { it.seatInput.hasFocus() }

    /**
     * The scroll view only nudges a focused field to the keyboard's edge — the lower seats
     * (team B) would be typed into with the keyboard right under them and no room for the
     * suggestion dropdown. Bring the seat up near the top instead, as far as the content allows.
     */
    private fun scrollSeatAboveKeyboard(row: ItemSeatBinding) {
        val scroll = binding.setupScroll
        val seatBounds = Rect(0, 0, row.seatLayout.width, row.seatLayout.height)
        scroll.offsetDescendantRectToMyCoords(row.seatLayout, seatBounds)

        val margin = (SEAT_TOP_MARGIN_DP * resources.displayMetrics.density).toInt()
        scroll.smoothScrollTo(0, (seatBounds.top - margin).coerceAtLeast(0))
    }

    private fun render(state: SetupUiState) {
        binding.formatLabel.text = getString(formatLabelRes(state.format))
        binding.addPlayerButton.isEnabled = state.canAddPlayer

        seatRows.forEachIndexed { index, row ->
            val seat = state.seats.getOrNull(index)
            row.root.visibility = if (seat == null) View.GONE else View.VISIBLE
            if (seat == null) return@forEachIndexed

            renderTeamHeader(row, state.format, seat)
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

    /**
     * Team play groups the seats: each team's first seat gets a coloured header, and team B's
     * also a "VS" divider above it. The header carries the team name, so the field hints only
     * need the player's position within it.
     */
    private fun renderTeamHeader(row: ItemSeatBinding, format: MatchFormat, seat: SeatUi) {
        val startsTeam = format.hasPartners && seat.seatInSide == 0
        row.teamHeader.isVisible = startsTeam
        row.teamDivider.isVisible = startsTeam && seat.side > 0
        if (!startsTeam) return

        val color = ContextCompat.getColor(requireContext(), RoundLogAdapter.sideColor(seat.side))
        row.teamHeader.text = getString(if (seat.side == 0) R.string.team_a else R.string.team_b)
        row.teamHeader.setTextColor(color)
        row.teamHeader.compoundDrawableTintList = ColorStateList.valueOf(color)
    }

    private fun seatHint(format: MatchFormat, seat: SeatUi): String =
        getString(R.string.setup_seat_hint, if (format.hasPartners) seat.seatInSide + 1 else seat.index + 1)

    private fun formatLabelRes(format: MatchFormat) = when (format) {
        MatchFormat.ONE_V_ONE -> R.string.format_one_v_one
        MatchFormat.TWO_V_TWO -> R.string.format_two_v_two
        MatchFormat.FREE_FOR_ALL -> R.string.format_free_for_all
    }

    private companion object {
        const val MAX_SEATS = 4
        const val SEAT_TOP_MARGIN_DP = 72
    }
}
