package com.example.dominocounter.data.db.relation

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import com.example.dominocounter.data.db.entity.MatchEntity
import com.example.dominocounter.data.db.entity.MatchParticipantEntity
import com.example.dominocounter.data.db.entity.MatchSideEntity
import com.example.dominocounter.data.db.entity.PlayerEntity
import com.example.dominocounter.data.db.entity.RoundEntity
import com.example.dominocounter.domain.model.MatchFormat

data class ParticipantWithPlayer(
    @Embedded val participant: MatchParticipantEntity,
    @Relation(parentColumn = "playerId", entityColumn = "id")
    val player: PlayerEntity
)

/**
 * The single aggregate the scoreboard observes. One Flow; any write to any of the four
 * tables re-emits it, and the totals recompute. There is no second code path named
 * "recalculate".
 */
data class MatchDetail(
    @Embedded val match: MatchEntity,

    @Relation(
        entity = MatchParticipantEntity::class,
        parentColumn = "id",
        entityColumn = "matchId"
    )
    val participants: List<ParticipantWithPlayer>,

    @Relation(parentColumn = "id", entityColumn = "matchId")
    val rounds: List<RoundEntity>,

    /** Populated only for COMPLETED matches. */
    @Relation(parentColumn = "id", entityColumn = "matchId")
    val frozenSides: List<MatchSideEntity>
)

/** Lightweight row for the Resume card and future match history list. */
data class MatchSummary(
    val id: Long,
    val startedAt: Long,
    val format: MatchFormat,
    val targetScore: Int,
    @ColumnInfo(name = "playerNames") val playerNames: String?,
    @ColumnInfo(name = "roundCount") val roundCount: Int
)

/** One row per format — never summed together; see MatchEntity.format. */
data class PlayerRecord(
    val format: MatchFormat,
    val played: Int,
    val won: Int
) {
    val winRate: Double get() = if (played == 0) 0.0 else won.toDouble() / played
}

/**
 * Used for both teammate synergy and head-to-head: the two queries differ by a single
 * operator on `side`.
 */
data class PlayerPairing(
    val playerId: Long,
    val displayName: String,
    val played: Int,
    val won: Int
) {
    val winRate: Double get() = if (played == 0) 0.0 else won.toDouble() / played
}
