package com.example.dominocounter.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dominocounter.data.repo.MatchRepository
import com.example.dominocounter.data.repo.NewMatch
import com.example.dominocounter.data.repo.PlayerRepository
import com.example.dominocounter.data.repo.SeatAssignment
import com.example.dominocounter.data.repo.SettingsRepository
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.Player
import com.example.dominocounter.util.NameNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeatUi(
    val index: Int,
    val side: Int,
    val seatInSide: Int,
    val name: String,
    val isDuplicate: Boolean
)

data class SetupUiState(
    val playerCount: Int,
    val format: MatchFormat,
    val seats: List<SeatUi>,
    val canStart: Boolean,
    val canAddPlayer: Boolean,
    val canRemovePlayer: Boolean
)

sealed interface SetupEvent {
    data class MatchCreated(val matchId: Long) : SetupEvent
    data class Error(val message: String) : SetupEvent
}

@HiltViewModel
class GameSetupViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val matchRepository: MatchRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val playerCount = MutableStateFlow(DEFAULT_PLAYER_COUNT)
    private val names = MutableStateFlow(List(MAX_SEATS) { "" })

    init {
        // Prefill with last game's lineup so an unchanged table never gets retyped.
        // A fresh install (or the very first match) leaves the defaults as-is.
        viewModelScope.launch {
            val lastNames = matchRepository.getLastLineupNames()
            if (lastNames.isNotEmpty()) {
                playerCount.value = lastNames.size.coerceIn(MIN_SEATS, MAX_SEATS)
                names.value = List(MAX_SEATS) { i -> lastNames.getOrElse(i) { "" } }
            }
        }
    }

    /**
     * Fed to every seat's AutoCompleteTextView. Loading recent players once and letting
     * ArrayAdapter filter locally beats a database query per keystroke, and the list is
     * tiny by nature — these are the people who actually sit at your table.
     */
    val suggestions: StateFlow<List<String>> = playerRepository.observeRecent(SUGGESTION_LIMIT)
        .map { players -> players.map(Player::displayName) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<SetupUiState> = combine(playerCount, names) { count, typed ->
        buildState(count, typed)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        buildState(DEFAULT_PLAYER_COUNT, List(MAX_SEATS) { "" })
    )

    private val _events = Channel<SetupEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var submitting = false

    /** Format follows automatically from the count: 2 → 1v1, 3 → free-for-all, 4 → 2v2. */
    fun addPlayer() {
        if (playerCount.value < MAX_SEATS) playerCount.value += 1
    }

    /** Shifts the remaining names down so no gap opens up in the middle of the list. */
    fun removePlayer(index: Int) {
        val count = playerCount.value
        if (count <= MIN_SEATS) return

        val current = names.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            current.add("")
        }
        names.value = current
        playerCount.value = count - 1
    }

    fun setName(index: Int, name: String) {
        names.value = names.value.toMutableList().also { it[index] = name }
    }

    fun start() {
        val state = uiState.value
        if (!state.canStart || submitting) return
        submitting = true

        viewModelScope.launch {
            try {
                // Resolve every typed name to a player row first: reusing an existing
                // profile is what keeps a person's history on one id instead of scattered
                // across near-duplicate rows.
                val seats = state.seats.map { seat ->
                    val player = playerRepository.getOrCreate(seat.name)
                    SeatAssignment(
                        playerId = player.id,
                        side = seat.side,
                        seatIndex = seat.seatInSide
                    )
                }

                val targetScore = settingsRepository.targetScore.first()
                val matchId = matchRepository.createMatch(
                    NewMatch(state.format, seats, targetScore)
                )
                playerRepository.markPlayed(seats.map(SeatAssignment::playerId))
                _events.send(SetupEvent.MatchCreated(matchId))
            } catch (t: Throwable) {
                _events.send(SetupEvent.Error(t.message ?: "Could not start the match"))
            } finally {
                submitting = false
            }
        }
    }

    private fun buildState(count: Int, typed: List<String>): SetupUiState {
        val format = MatchFormat.forPlayerCount(count)
        val active = typed.take(count)

        // Duplicate detection runs on the normalized key, so "Wael" and "wael " collide
        // here exactly as they would collide in the database.
        val keys = active.map { NameNormalizer.normalize(it) }
        val duplicateKeys = keys
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val seats = active.mapIndexed { index, name ->
            SeatUi(
                index = index,
                side = index / format.playersPerSide,
                seatInSide = index % format.playersPerSide,
                name = name,
                isDuplicate = keys[index] in duplicateKeys
            )
        }

        return SetupUiState(
            playerCount = count,
            format = format,
            seats = seats,
            canStart = seats.all { it.name.isNotBlank() && !it.isDuplicate },
            canAddPlayer = count < MAX_SEATS,
            canRemovePlayer = count > MIN_SEATS
        )
    }

    private companion object {
        const val MIN_SEATS = 2
        const val MAX_SEATS = 4
        const val DEFAULT_PLAYER_COUNT = MIN_SEATS
        const val SUGGESTION_LIMIT = 50
    }
}
