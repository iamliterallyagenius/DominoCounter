package com.example.dominocounter.ui.match

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.R
import com.example.dominocounter.data.repo.MatchNotEditableException
import com.example.dominocounter.data.repo.MatchRepository
import com.example.dominocounter.data.repo.NewMatch
import com.example.dominocounter.data.repo.SeatAssignment
import com.example.dominocounter.domain.MatchEngine
import com.example.dominocounter.domain.model.LoggedRound
import com.example.dominocounter.domain.model.MatchState
import com.example.dominocounter.domain.model.ScanAudit
import com.example.dominocounter.domain.model.ScoreSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MatchEvent {
    data class Victory(val state: MatchState) : MatchEvent
    data object Abandoned : MatchEvent
    data class Error(@StringRes val messageRes: Int) : MatchEvent
}

/**
 * Scoped to the `match_graph` nav graph, so the scoreboard and both bottom sheets share
 * one instance. It is created when the graph is entered and destroyed when the graph is
 * popped — no stale match can leak into the next game.
 */
@HiltViewModel
class MatchViewModel @Inject constructor(
    private val matchRepository: MatchRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val matchId: Long = requireNotNull(savedStateHandle.get<Long>(ARG_MATCH_ID)) {
        "match_graph was entered without a matchId"
    }

    private val _events = Channel<MatchEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var victoryAnnounced = false

    /**
     * The whole screen, derived. Room emits on any write to any of the four tables, the
     * engine folds the rounds, and the scoreboard re-renders. Editing and deleting need
     * no recalculation code because there is no stored total to correct.
     */
    val state: StateFlow<MatchState?> = matchRepository.observeMatch(matchId)
        .map { snapshot -> snapshot?.let(MatchEngine::reduce) }
        .onEach { state -> state?.let { settleOutcome(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------------ intents

    fun addManual(side: Int, points: Int) = mutate {
        matchRepository.addRound(matchId, side, points, ScoreSource.MANUAL)
    }

    /** R1: credits the side whose button was tapped. */
    fun addFoul(side: Int) = mutate {
        matchRepository.addFoul(matchId, side)
    }

    /** Used by the camera scanner once it lands. */
    fun commitScan(side: Int, points: Int, audit: ScanAudit?) = mutate {
        matchRepository.addRound(matchId, side, points, ScoreSource.SCAN, audit)
    }

    fun editRound(roundId: Long, points: Int) = mutate {
        matchRepository.editRound(roundId, points)
    }

    fun deleteRound(roundId: Long) = mutate {
        matchRepository.deleteRound(roundId)
    }

    fun abandon() = mutate {
        matchRepository.abandonMatch(matchId)
        _events.send(MatchEvent.Abandoned)
    }

    /** Lets the edit sheet read its round out of state instead of re-querying. */
    fun findRound(roundId: Long): LoggedRound? =
        state.value?.log?.firstOrNull { it.round.id == roundId }

    /**
     * Same players, same sides, same target score, fresh rounds. Called from the victory
     * dialog; the caller awaits the new id and navigates into it.
     */
    suspend fun createRematch(): Long {
        val finished = checkNotNull(state.value) { "No match to rematch" }
        val seats = finished.sides.flatMap { side ->
            side.players.mapIndexed { seatIndex, player ->
                SeatAssignment(playerId = player.id, side = side.index, seatIndex = seatIndex)
            }
        }
        return matchRepository.createMatch(NewMatch(finished.format, seats, finished.targetScore))
    }

    // ------------------------------------------------------------------ internals

    /**
     * R3 + R5. Completion is a consequence of the rounds, never an imperative call from a
     * button handler — so it fires identically whether the target was crossed by adding a
     * round or by editing an earlier one.
     */
    private suspend fun settleOutcome(state: MatchState) {
        if (state.needsCompletion) {
            // Writing re-emits the snapshot as COMPLETED; the announcement happens on that
            // pass, so the dialog can never appear before the result is durable.
            matchRepository.completeMatch(matchId, checkNotNull(state.outcome))
            return
        }
        if (state.outcome != null && !victoryAnnounced) {
            victoryAnnounced = true
            _events.send(MatchEvent.Victory(state))
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: MatchNotEditableException) {
                _events.send(MatchEvent.Error(R.string.score_locked))
            }
        }
    }

    private companion object {
        const val ARG_MATCH_ID = "matchId"
    }
}
