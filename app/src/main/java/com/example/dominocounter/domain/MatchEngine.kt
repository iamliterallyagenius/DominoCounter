package com.example.dominocounter.domain

import com.example.dominocounter.domain.model.LoggedRound
import com.example.dominocounter.domain.model.MatchSnapshot
import com.example.dominocounter.domain.model.MatchState
import com.example.dominocounter.domain.model.Outcome
import com.example.dominocounter.domain.model.Player
import com.example.dominocounter.domain.model.SideState

/**
 * The entire scoring rulebook, as a pure function.
 *
 * No Android, no coroutines, no Room — so every rule is unit-testable on the JVM with
 * zero mocks, and the "recalculate after an edit" requirement needs no code at all:
 * the fold simply runs again over the new list.
 */
object MatchEngine {

    fun reduce(snapshot: MatchSnapshot): MatchState {
        val sideCount = snapshot.format.sideCount
        val totals = IntArray(sideCount)
        val log = ArrayList<LoggedRound>(snapshot.rounds.size)
        var outcome: Outcome? = null

        // Sequence order, not insertion order: an edited round keeps its place in history.
        for (round in snapshot.rounds.sortedBy { it.sequence }) {

            // Defensive: a side index outside the format can only come from corrupt data.
            // Skip it rather than crash the scoreboard or inflate a total.
            if (round.side !in 0 until sideCount) continue

            if (outcome != null) {
                // R3/R5: the match ended at the decisive round. Anything after it is shown
                // in the log for transparency but contributes nothing.
                log += LoggedRound(round, totals[round.side], isDecisive = false, isIgnored = true)
                continue
            }

            totals[round.side] += round.points

            // Only the receiving side's total changed, so only it can cross the line.
            // That is why a simultaneous two-side win is structurally impossible.
            val crossed = totals[round.side] >= snapshot.targetScore
            if (crossed) {
                outcome = Outcome(
                    winningSide = round.side,
                    finalScores = totals.toList(),
                    decidedByRoundId = round.id,
                    decidedAtSequence = round.sequence
                )
            }

            log += LoggedRound(round, totals[round.side], isDecisive = crossed, isIgnored = false)
        }

        return MatchState(
            matchId = snapshot.id,
            format = snapshot.format,
            status = snapshot.status,
            targetScore = snapshot.targetScore,
            sides = buildSides(snapshot, totals),
            log = log.asReversed(),          // newest first for the RecyclerView
            outcome = outcome,
            startedAt = snapshot.startedAt,
            finishedAt = snapshot.finishedAt
        )
    }

    private fun buildSides(snapshot: MatchSnapshot, totals: IntArray): List<SideState> {
        val bySide: Map<Int, List<Player>> = snapshot.lineup
            .sortedBy { it.seatIndex }
            .groupBy({ it.side }, { it.player })

        val best = totals.max()

        return List(snapshot.format.sideCount) { index ->
            SideState(
                index = index,
                players = bySide[index].orEmpty(),
                score = totals[index],
                isLeading = best > 0 && totals[index] == best,
                pointsToWin = (snapshot.targetScore - totals[index]).coerceAtLeast(0)
            )
        }
    }
}
