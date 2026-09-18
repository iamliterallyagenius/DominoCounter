package com.example.dominocounter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.dominocounter.data.db.entity.PlayerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {

    /** Recency first: the setup dropdown should offer who actually plays, not the alphabet. */
    @Query(
        """
        SELECT * FROM players
        WHERE isArchived = 0
        ORDER BY lastPlayedAt DESC, displayName COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int = 20): Flow<List<PlayerEntity>>

    @Query(
        """
        SELECT * FROM players
        WHERE isArchived = 0 AND normalizedName LIKE '%' || :normalizedQuery || '%'
        ORDER BY lastPlayedAt DESC, displayName COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    fun observeMatching(normalizedQuery: String, limit: Int = 20): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): PlayerEntity?

    @Query("SELECT * FROM players WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<PlayerEntity>

    /** Returns -1 when the unique normalizedName index rejects a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(player: PlayerEntity): Long

    @Query("UPDATE players SET lastPlayedAt = :timestamp WHERE id IN (:ids)")
    suspend fun touchLastPlayed(ids: List<Long>, timestamp: Long)

    @Query("UPDATE players SET displayName = :displayName, normalizedName = :normalizedName WHERE id = :id")
    suspend fun rename(id: Long, displayName: String, normalizedName: String)

    @Query("UPDATE players SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
}
