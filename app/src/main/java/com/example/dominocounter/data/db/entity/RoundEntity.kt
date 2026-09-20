package com.example.dominocounter.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.dominocounter.domain.model.ScoreSource

@Entity(
    tableName = "rounds",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["matchId", "sequence"])]
)
data class RoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,

    /** Display order. Survives edits; gaps after a delete are harmless. */
    val sequence: Int,

    /** R1: always the side that RECEIVED the points, fouls included. */
    val side: Int,

    val points: Int,
    val source: ScoreSource,
    val createdAt: Long,

    /** Non-null ⇒ the log row shows an "edited" marker. */
    val editedAt: Long? = null,

    // ---- scan audit; null for MANUAL and FOUL ----
    val detectedTileCount: Int? = null,

    /**
     * What the pip detector reported BEFORE any user correction. One nullable column buys a
     * real accuracy metric: after a few hundred scans you can measure how often the scanner
     * was corrected and by how much, which tells you whether the model needs retraining on
     * more photos instead of guessing.
     */
    val rawPipTotal: Int? = null
)
