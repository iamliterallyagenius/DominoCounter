package com.example.dominocounter.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.data.db.relation.PlayerPairing
import com.example.dominocounter.data.db.relation.PlayerRecord
import com.example.dominocounter.data.repo.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PlayerStatsUiState(
    val leaderboard: List<PlayerPairing> = emptyList(),
    val selectedPlayerId: Long? = null,
    val selectedName: String? = null,
    val records: List<PlayerRecord> = emptyList(),
    val synergy: List<PlayerPairing> = emptyList(),
    val headToHead: List<PlayerPairing> = emptyList()
)

private data class Selection(val playerId: Long, val displayName: String)
private data class Detail(
    val records: List<PlayerRecord>,
    val synergy: List<PlayerPairing>,
    val headToHead: List<PlayerPairing>
)

private val EMPTY_DETAIL = Detail(emptyList(), emptyList(), emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerStatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val selectedPlayer = MutableStateFlow<Selection?>(null)

    private val detail = selectedPlayer.flatMapLatest { selection ->
        if (selection == null) {
            flowOf(EMPTY_DETAIL)
        } else {
            combine(
                statsRepository.observeRecord(selection.playerId),
                statsRepository.observeSynergy(selection.playerId),
                statsRepository.observeHeadToHead(selection.playerId)
            ) { records, synergy, headToHead -> Detail(records, synergy, headToHead) }
        }
    }

    val uiState: StateFlow<PlayerStatsUiState> = combine(
        statsRepository.observeLeaderboard(),
        selectedPlayer,
        detail
    ) { leaderboard, selection, detail ->
        PlayerStatsUiState(
            leaderboard = leaderboard,
            selectedPlayerId = selection?.playerId,
            selectedName = selection?.displayName,
            records = detail.records,
            synergy = detail.synergy,
            headToHead = detail.headToHead
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerStatsUiState())

    fun selectPlayer(id: Long, name: String) {
        selectedPlayer.value = Selection(id, name)
    }
}
