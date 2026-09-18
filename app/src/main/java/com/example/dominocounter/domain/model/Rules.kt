package com.example.dominocounter.domain.model

/**
 * The rulebook, in one place. Nothing here depends on Android, Room or the UI.
 */
object Rules {

    /** R3: first side to reach this total wins immediately. Snapshotted per match. */
    const val DEFAULT_TARGET_SCORE = 100

    /** R1: a foul awards this many points to the side whose button was tapped. */
    const val FOUL_POINTS = 50

    val ALLOWED_PLAYER_COUNTS = 2..4

    /** Guard rail for manual entry — a single round can't plausibly exceed this. */
    const val MAX_MANUAL_POINTS = 300
}

/**
 * R2. `sideCount` is the only thing the rest of the app needs; player count is
 * derived from it so a future 4-player free-for-all is one enum constant, not a
 * redesign.
 */
enum class MatchFormat(val sideCount: Int, val playersPerSide: Int) {
    /** 2 players, one each. */
    ONE_V_ONE(sideCount = 2, playersPerSide = 1),

    /** 3 players, no partners — draw instead of skip. */
    FREE_FOR_ALL(sideCount = 3, playersPerSide = 1),

    /** 4 players, two teams of two. */
    TWO_V_TWO(sideCount = 2, playersPerSide = 2);

    val playerCount: Int get() = sideCount * playersPerSide

    /** True only when a side can have teammates — i.e. when synergy stats mean anything. */
    val hasPartners: Boolean get() = playersPerSide > 1

    companion object {
        fun forPlayerCount(count: Int): MatchFormat = entries.firstOrNull { it.playerCount == count }
            ?: throw IllegalArgumentException("No format for $count players")
    }
}

enum class MatchStatus {
    IN_PROGRESS,

    /** R5: terminal and immutable. Rounds can no longer be added, edited or deleted. */
    COMPLETED,

    /** R6: abandoned on purpose by the user. Excluded from every stats query. */
    ABANDONED
}

enum class ScoreSource { MANUAL, SCAN, FOUL }
