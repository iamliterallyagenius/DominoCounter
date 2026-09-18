package com.example.dominocounter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.dominocounter.data.db.entity.RoundEntity

@Dao
interface RoundDao {

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM rounds WHERE matchId = :matchId")
    suspend fun nextSequence(matchId: Long): Int

    @Insert
    suspend fun insert(round: RoundEntity): Long

    @Query("SELECT * FROM rounds WHERE id = :roundId")
    suspend fun findById(roundId: Long): RoundEntity?

    /**
     * R5 enforced in SQL, not in the UI.
     *
     * The correlated EXISTS means a round belonging to a COMPLETED match simply cannot be
     * updated, whatever calls it — a stale Fragment, a replayed event, a future bug. Returns
     * the affected row count so the repository can turn 0 into a typed failure.
     */
    @Query(
        """
        UPDATE rounds
        SET points = :points, editedAt = :editedAt
        WHERE id = :roundId
          AND EXISTS (
              SELECT 1 FROM matches m
              WHERE m.id = rounds.matchId AND m.status = 'IN_PROGRESS'
          )
        """
    )
    suspend fun updatePoints(roundId: Long, points: Int, editedAt: Long): Int

    @Query(
        """
        DELETE FROM rounds
        WHERE id = :roundId
          AND EXISTS (
              SELECT 1 FROM matches m
              WHERE m.id = rounds.matchId AND m.status = 'IN_PROGRESS'
          )
        """
    )
    suspend fun delete(roundId: Long): Int
}
