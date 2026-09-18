package com.example.dominocounter.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The junction table the whole stats feature rests on.
 *
 * The shortcut — teamAPlayer1Id … teamBPlayer2Id columns on `matches` — cannot express a
 * 3-player free-for-all, turns "who does Wael win with" into a four-way OR across eight
 * columns, and needs a migration for every rule change.
 *
 * As a junction table, teammate synergy is a self-join on (matchId, side) with a differing
 * playerId. That works for any number of players per side, and it handles free-for-all for
 * free: each FFA side holds exactly one player, so the join returns nothing and synergy is
 * correctly empty with no special case anywhere.
 */
@Entity(
    tableName = "match_participants",
    primaryKeys = ["matchId", "playerId"],
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["playerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("playerId"), Index(value = ["matchId", "side"])]
)
data class MatchParticipantEntity(
    val matchId: Long,
    val playerId: Long,

    /** 0-based. Two sides for 1v1 and 2v2, three for free-for-all. */
    val side: Int,

    /** Display order within the side. */
    val seatIndex: Int
)
