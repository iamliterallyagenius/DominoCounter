package com.example.dominocounter.domain.model

/**
 * The fully computed view of a match: the output of the engine and the thing the
 * scoreboard renders. Immutable, and a pure function of [MatchSnapshot].
 */
data class MatchState(
    val matchId: Long,
    val format: MatchFormat,
    val status: MatchStatus,
    val targetScore: Int,
    val sides: List<SideState>,
    /** Newest first — the order the audit RecyclerView wants. */
    val log: List<LoggedRound>,
    val outcome: Outcome?,
    /** Carried through for game history, which sorts and labels by when a match was played. */
    val startedAt: Long,
    val finishedAt: Long?
) {
    /** R5: only an in-progress match accepts new, edited or deleted rounds. */
    val isEditable: Boolean get() = status == MatchStatus.IN_PROGRESS

    /**
     * True when the rounds say a side has won but the database hasn't been told yet.
     * This is the signal that the completion transaction must run.
     */
    val needsCompletion: Boolean get() = outcome != null && status == MatchStatus.IN_PROGRESS

    fun side(index: Int): SideState = sides[index]
}

data class SideState(
    val index: Int,
    val players: List<Player>,
    val score: Int,
    /** Shared by all tied sides; false for everyone while the match is still 0-0. */
    val isLeading: Boolean,
    val pointsToWin: Int
) {
    val label: String get() = players.joinToString(" & ") { it.displayName }
}

data class LoggedRound(
    val round: Round,
    /** That side's total immediately after this round — what the log row shows. */
    val runningTotal: Int,
    /** The round that crossed the target and ended the match. */
    val isDecisive: Boolean,
    /**
     * Recorded after the match was already decided. Should never happen; if it does,
     * the engine refuses to count it rather than silently producing a wrong total.
     */
    val isIgnored: Boolean
)

data class Outcome(
    val winningSide: Int,
    /** Frozen totals at the moment of the deciding round, indexed by side. */
    val finalScores: List<Int>,
    val decidedByRoundId: Long,
    val decidedAtSequence: Int
)
