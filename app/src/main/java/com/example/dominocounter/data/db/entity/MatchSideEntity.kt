package com.example.dominocounter.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Frozen final score per side, written once inside the completion transaction.
 *
 * Two fixed columns on `matches` cannot hold three sides, and this also turns
 * "biggest blowout" / "average winning score" / "points per match" into single-scan
 * queries instead of folds over every round ever played.
 */
@Entity(
    tableName = "match_sides",
    primaryKeys = ["matchId", "side"],
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("matchId")]
)
data class MatchSideEntity(
    val matchId: Long,
    val side: Int,
    val finalScore: Int
)
