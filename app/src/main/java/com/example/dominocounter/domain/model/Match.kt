package com.example.dominocounter.domain.model

data class Player(
    val id: Long,
    val displayName: String,
    val colorSeed: Int
)

/** One player's place in one match. */
data class Seat(
    val player: Player,
    val side: Int,
    val seatIndex: Int
)

/**
 * What the scanner saw, kept so scanner accuracy can be measured against corrections.
 *
 * @param detectedTileCount how many markers the detector returned (single pips, or pip clusters for a
 *        cluster model). Named for the column it is stored in; the tile-finding scanner it was
 *        named after is gone, and renaming would need a Room migration for no behavioural gain.
 * @param rawPipTotal the pip total the detector reported BEFORE the player corrected it
 */
data class ScanAudit(
    val detectedTileCount: Int,
    val rawPipTotal: Int
)

/**
 * One entry in the audit log. [side] is always the side that RECEIVED the points,
 * for every source including FOUL (R1).
 */
data class Round(
    val id: Long,
    val sequence: Int,
    val side: Int,
    val points: Int,
    val source: ScoreSource,
    val createdAt: Long,
    val editedAt: Long? = null,
    val scan: ScanAudit? = null
) {
    val wasEdited: Boolean get() = editedAt != null
}

/**
 * Everything persisted about one match, as plain domain types.
 *
 * This is the *input* to [com.example.dominocounter.domain.MatchEngine]. Note what it
 * does NOT contain: any score. Scores are derived from [rounds], never stored.
 */
data class MatchSnapshot(
    val id: Long,
    val format: MatchFormat,
    val status: MatchStatus,
    val targetScore: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val winningSide: Int?,
    val lineup: List<Seat>,
    val rounds: List<Round>
)
