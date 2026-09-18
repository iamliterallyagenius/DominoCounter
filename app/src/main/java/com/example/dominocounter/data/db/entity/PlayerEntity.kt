package com.example.dominocounter.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "players",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Exactly as typed, e.g. "Wael". */
    val displayName: String,

    /** Dedupe key from [com.example.dominocounter.util.NameNormalizer]. Unique. */
    val normalizedName: String,

    /** Stable seed for the avatar colour, so a player looks the same everywhere. */
    val colorSeed: Int,

    val createdAt: Long,

    /** Lets the setup dropdown sort by who actually plays, not alphabetically. */
    val lastPlayedAt: Long? = null,

    /**
     * Players are archived, never deleted: the RESTRICT foreign key from
     * match_participants would reject the delete anyway, and silently dropping
     * someone's match history is worse than hiding a row.
     */
    val isArchived: Boolean = false
)
