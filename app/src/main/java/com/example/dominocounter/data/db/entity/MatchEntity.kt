package com.example.dominocounter.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.MatchStatus

@Entity(
    tableName = "matches",
    indices = [Index(value = ["status", "startedAt"])]   // drives the Resume lookup
)
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: MatchStatus = MatchStatus.IN_PROGRESS,

    /**
     * Stored, not derived from player count, because stats MUST segment by it:
     * a free-for-all win-rate baseline is 1 in 3, a 2v2 baseline is 1 in 2, and
     * averaging them together produces a meaningless number.
     */
    val format: MatchFormat,

    /**
     * Snapshot of the Settings value at creation. Change the target to 150 next month
     * and last month's matches must still read as the games they actually were.
     */
    val targetScore: Int,

    /** Null until COMPLETED. */
    val winningSide: Int? = null
)
