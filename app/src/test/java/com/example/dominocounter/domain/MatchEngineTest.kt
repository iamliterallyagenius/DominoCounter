package com.example.dominocounter.domain

import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.MatchSnapshot
import com.example.dominocounter.domain.model.MatchStatus
import com.example.dominocounter.domain.model.Player
import com.example.dominocounter.domain.model.Round
import com.example.dominocounter.domain.model.Rules
import com.example.dominocounter.domain.model.ScoreSource
import com.example.dominocounter.domain.model.Seat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rulebook under test. R1–R5 from ARCHITECTURE.md §2.
 *
 * No mocks, no Robolectric, no coroutines — the engine is a pure function, which is the
 * entire reason the scoring rules were pushed out of the ViewModel.
 */
class MatchEngineTest {

    // =================================================================== R1: fouls

    @Test
    fun `foul credits the side whose button was tapped`() {
        val state = MatchEngine.reduce(
            snapshot(rounds = listOf(round(seq = 1, side = 0, points = Rules.FOUL_POINTS, source = ScoreSource.FOUL)))
        )

        assertThat(state.side(0).score).isEqualTo(50)
        assertThat(state.side(1).score).isEqualTo(0)
    }

    @Test
    fun `fouls and normal rounds accumulate on the same side`() {
        val state = MatchEngine.reduce(
            snapshot(
                rounds = listOf(
                    round(1, side = 0, points = 30),
                    round(2, side = 0, points = 50, source = ScoreSource.FOUL),
                    round(3, side = 1, points = 20)
                )
            )
        )

        assertThat(state.side(0).score).isEqualTo(80)
        assertThat(state.side(1).score).isEqualTo(20)
        assertThat(state.outcome).isNull()
    }

    // =================================================================== R3: win condition

    @Test
    fun `below the target the match stays undecided`() {
        val state = MatchEngine.reduce(snapshot(rounds = listOf(round(1, side = 0, points = 99))))

        assertThat(state.outcome).isNull()
        assertThat(state.side(0).pointsToWin).isEqualTo(1)
    }

    @Test
    fun `exactly the target wins`() {
        val state = MatchEngine.reduce(snapshot(rounds = listOf(round(1, side = 0, points = 100))))

        assertThat(state.outcome?.winningSide).isEqualTo(0)
        assertThat(state.side(0).pointsToWin).isEqualTo(0)
    }

    @Test
    fun `overshooting the target wins, there is no exact-hit rule`() {
        val state = MatchEngine.reduce(
            snapshot(rounds = listOf(round(1, side = 1, points = 60), round(2, side = 1, points = 75)))
        )

        assertThat(state.outcome?.winningSide).isEqualTo(1)
        assertThat(state.outcome?.finalScores).containsExactly(0, 135).inOrder()
    }

    @Test
    fun `the first side to cross wins and the deciding round is flagged`() {
        val state = MatchEngine.reduce(
            snapshot(
                rounds = listOf(
                    round(1, side = 0, points = 60),
                    round(2, side = 1, points = 95),
                    round(3, side = 0, points = 45)   // 105 — decides it
                )
            )
        )

        assertThat(state.outcome?.winningSide).isEqualTo(0)
        assertThat(state.outcome?.decidedAtSequence).isEqualTo(3)
        assertThat(state.log.single { it.isDecisive }.round.sequence).isEqualTo(3)
    }

    @Test
    fun `rounds recorded after the decisive one are shown but not counted`() {
        val state = MatchEngine.reduce(
            snapshot(
                rounds = listOf(
                    round(1, side = 0, points = 100),
                    round(2, side = 1, points = 40)   // must never happen; must never corrupt totals
                )
            )
        )

        assertThat(state.side(1).score).isEqualTo(0)
        assertThat(state.log.first { it.round.sequence == 2 }.isIgnored).isTrue()
        assertThat(state.outcome?.finalScores).containsExactly(100, 0).inOrder()
    }

    // =================================================================== R5: edit / delete

    @Test
    fun `deleting a round recalculates the total`() {
        val rounds = listOf(
            round(1, side = 0, points = 40),
            round(2, side = 0, points = 30),
            round(3, side = 0, points = 25)
        )
        assertThat(MatchEngine.reduce(snapshot(rounds = rounds)).side(0).score).isEqualTo(95)

        // The "delete" is simply the absence of the row — no recalculation code exists.
        val afterDelete = MatchEngine.reduce(snapshot(rounds = rounds.filterNot { it.sequence == 2 }))
        assertThat(afterDelete.side(0).score).isEqualTo(65)
        assertThat(afterDelete.outcome).isNull()
    }

    @Test
    fun `deleting a round can pull a side back below the target`() {
        val rounds = listOf(round(1, side = 0, points = 60), round(2, side = 0, points = 50))
        assertThat(MatchEngine.reduce(snapshot(rounds = rounds)).outcome).isNotNull()

        val afterDelete = MatchEngine.reduce(snapshot(rounds = rounds.take(1)))
        assertThat(afterDelete.outcome).isNull()
    }

    @Test
    fun `editing an earlier round can decide the match immediately`() {
        val rounds = listOf(
            round(1, side = 0, points = 10),
            round(2, side = 1, points = 30),
            round(3, side = 0, points = 40)
        )
        assertThat(MatchEngine.reduce(snapshot(rounds = rounds)).outcome).isNull()

        val corrected = rounds.map { if (it.sequence == 1) it.copy(points = 70) else it }
        val state = MatchEngine.reduce(snapshot(rounds = corrected))

        assertThat(state.side(0).score).isEqualTo(110)
        assertThat(state.outcome?.winningSide).isEqualTo(0)
        // The edit decided it at round 3, not at the round that was edited.
        assertThat(state.outcome?.decidedAtSequence).isEqualTo(3)
    }

    @Test
    fun `a completed match is not editable`() {
        val state = MatchEngine.reduce(
            snapshot(status = MatchStatus.COMPLETED, rounds = listOf(round(1, side = 0, points = 100)))
        )

        assertThat(state.isEditable).isFalse()
        assertThat(state.needsCompletion).isFalse()
    }

    @Test
    fun `an in-progress match whose rounds decide it needs completion`() {
        val state = MatchEngine.reduce(snapshot(rounds = listOf(round(1, side = 0, points = 100))))

        assertThat(state.isEditable).isTrue()
        assertThat(state.needsCompletion).isTrue()
    }

    // =================================================================== R2: formats

    @Test
    fun `free for all tracks three independent sides`() {
        val state = MatchEngine.reduce(
            snapshot(
                format = MatchFormat.FREE_FOR_ALL,
                rounds = listOf(
                    round(1, side = 0, points = 20),
                    round(2, side = 1, points = 35),
                    round(3, side = 2, points = 15)
                )
            )
        )

        assertThat(state.sides).hasSize(3)
        assertThat(state.sides.map { it.score }).containsExactly(20, 35, 15).inOrder()
        assertThat(state.sides.single { it.isLeading }.index).isEqualTo(1)
    }

    @Test
    fun `two v two labels a side with both partner names`() {
        val state = MatchEngine.reduce(snapshot(format = MatchFormat.TWO_V_TWO))

        assertThat(state.side(0).label).isEqualTo("P0 & P1")
        assertThat(state.side(1).label).isEqualTo("P2 & P3")
    }

    // =================================================================== log & presentation

    @Test
    fun `running totals in the log are per side`() {
        val state = MatchEngine.reduce(
            snapshot(
                rounds = listOf(
                    round(1, side = 0, points = 30),
                    round(2, side = 1, points = 10),
                    round(3, side = 0, points = 25)
                )
            )
        )

        val bySequence = state.log.associateBy { it.round.sequence }
        assertThat(bySequence.getValue(1).runningTotal).isEqualTo(30)
        assertThat(bySequence.getValue(2).runningTotal).isEqualTo(10)
        assertThat(bySequence.getValue(3).runningTotal).isEqualTo(55)
    }

    @Test
    fun `the log is newest first`() {
        val state = MatchEngine.reduce(
            snapshot(rounds = listOf(round(1, side = 0, points = 5), round(2, side = 1, points = 5)))
        )

        assertThat(state.log.map { it.round.sequence }).containsExactly(2, 1).inOrder()
    }

    @Test
    fun `rounds are folded by sequence, not by list order`() {
        val shuffled = listOf(
            round(3, side = 0, points = 45),
            round(1, side = 0, points = 60),
            round(2, side = 1, points = 95)
        )
        val state = MatchEngine.reduce(snapshot(rounds = shuffled))

        assertThat(state.outcome?.decidedAtSequence).isEqualTo(3)
        assertThat(state.outcome?.finalScores).containsExactly(105, 95).inOrder()
    }

    @Test
    fun `nobody leads at nil all, and a tie makes both sides leading`() {
        assertThat(MatchEngine.reduce(snapshot()).sides.none { it.isLeading }).isTrue()

        val tied = MatchEngine.reduce(
            snapshot(rounds = listOf(round(1, side = 0, points = 30), round(2, side = 1, points = 30)))
        )
        assertThat(tied.sides.all { it.isLeading }).isTrue()
    }

    @Test
    fun `a round on a side outside the format is ignored rather than crashing`() {
        val state = MatchEngine.reduce(
            snapshot(
                format = MatchFormat.ONE_V_ONE,
                rounds = listOf(round(1, side = 0, points = 10), round(2, side = 7, points = 999))
            )
        )

        assertThat(state.sides).hasSize(2)
        assertThat(state.sides.map { it.score }).containsExactly(10, 0).inOrder()
        assertThat(state.outcome).isNull()
    }

    @Test
    fun `an empty match reduces to a clean scoreboard`() {
        val state = MatchEngine.reduce(snapshot())

        assertThat(state.sides.map { it.score }).containsExactly(0, 0).inOrder()
        assertThat(state.sides.map { it.pointsToWin }).containsExactly(100, 100).inOrder()
        assertThat(state.log).isEmpty()
        assertThat(state.outcome).isNull()
    }

    // =================================================================== fixtures

    private fun snapshot(
        format: MatchFormat = MatchFormat.ONE_V_ONE,
        status: MatchStatus = MatchStatus.IN_PROGRESS,
        targetScore: Int = Rules.DEFAULT_TARGET_SCORE,
        rounds: List<Round> = emptyList()
    ) = MatchSnapshot(
        id = 1L,
        format = format,
        status = status,
        targetScore = targetScore,
        startedAt = 0L,
        finishedAt = null,
        winningSide = null,
        lineup = lineupFor(format),
        rounds = rounds
    )

    private fun lineupFor(format: MatchFormat): List<Seat> =
        (0 until format.playerCount).map { i ->
            Seat(
                player = Player(id = i.toLong(), displayName = "P$i", colorSeed = 0),
                side = i / format.playersPerSide,
                seatIndex = i % format.playersPerSide
            )
        }

    private fun round(
        seq: Int,
        side: Int,
        points: Int,
        source: ScoreSource = ScoreSource.MANUAL
    ) = Round(
        id = seq.toLong(),
        sequence = seq,
        side = side,
        points = points,
        source = source,
        createdAt = seq.toLong()
    )
}
