package com.example.dominocounter.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.data.db.relation.MatchSummary
import com.example.dominocounter.data.repo.MatchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainMenuViewModel @Inject constructor(
    matchRepository: MatchRepository
) : ViewModel() {

    /** Non-null while an unfinished match exists (R6) — drives the Resume card. */
    val resumable: StateFlow<MatchSummary?> = matchRepository.observeResumable()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
