package com.example.dominocounter.ui.history

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

@HiltViewModel
class GameHistoryViewModel @Inject constructor(
    matchRepository: MatchRepository
) : ViewModel() {

    val history: StateFlow<List<MatchState>> = matchRepository.observeHistory()
        .map { snapshots -> snapshots.map(MatchEngine::reduce) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
