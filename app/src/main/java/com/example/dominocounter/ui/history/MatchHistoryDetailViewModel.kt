package com.example.dominocounter.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.data.repo.MatchRepository
import com.example.dominocounter.domain.MatchEngine
import com.example.dominocounter.domain.model.MatchState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Purely a read-only view — no [MatchViewModel][com.example.dominocounter.ui.match.MatchViewModel]
 * completion/victory side effects here, since a history entry is by definition already finished.
 */
@HiltViewModel
class MatchHistoryDetailViewModel @Inject constructor(
    matchRepository: MatchRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val matchId: Long = requireNotNull(savedStateHandle.get<Long>("matchId"))

    val state: StateFlow<MatchState?> = matchRepository.observeMatch(matchId)
        .map { it?.let(MatchEngine::reduce) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
