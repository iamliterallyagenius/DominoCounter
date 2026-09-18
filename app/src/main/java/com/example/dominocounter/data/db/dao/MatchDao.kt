package com.example.dominocounter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.dominocounter.data.db.entity.MatchEntity
import com.example.dominocounter.data.db.entity.MatchParticipantEntity
import com.example.dominocounter.data.db.entity.MatchSideEntity
import com.example.dominocounter.data.db.relation.MatchDetail
import com.example.dominocounter.data.db.relation.MatchSummary
import com.example.dominocounter.domain.model.MatchStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDao {

    @Insert
    suspend fun insertMatch(match: MatchEntity): Long

    @Insert
    suspend fun insertParticipants(participants: List<MatchParticipantEntity>)

    @Insert
    suspend fun insertSides(sides: List<MatchSideEntity>)

    @Transaction
    @Query("SELECT * FROM matches WHERE id = :matchId")
    fun observeDetail(matchId: Long): Flow<MatchDetail?>

    @Transaction
    @Query("SELECT * FROM matches WHERE id = :matchId")
    suspend fun getDetail(matchId: Long): MatchDetail?

    @Query("SELECT status FROM matches WHERE id = :matchId")
    suspend fun statusOf(matchId: Long): MatchStatus?

    /**
     * Setup screen prefill: the lineup of the most recently started match, in seat order,
     * regardless of how it ended — so "same players as last time" always means last time.
     */
    @Query(
        """
        SELECT p.displayName
        FROM match_participants mp
        JOIN players p ON p.id = mp.playerId
        WHERE mp.matchId = (SELECT id FROM matches ORDER BY startedAt DESC LIMIT 1)
        ORDER BY mp.side, mp.seatIndex
        """
    )
    suspend fun lastLineupNames(): List<String>

    /** R6: the Resume card. Most recently started match that was never finished. */
    @Query(
        """
        SELECT m.id            AS id,
               m.startedAt     AS startedAt,
               m.format        AS format,
               m.targetScore   AS targetScore,
               (SELECT GROUP_CONCAT(p.displayName, ', ')
                  FROM match_participants mp
                  JOIN players p ON p.id = mp.playerId
                 WHERE mp.matchId = m.id)                       AS playerNames,
               (SELECT COUNT(*) FROM rounds r WHERE r.matchId = m.id) AS roundCount
        FROM matches m
        WHERE m.status = 'IN_PROGRESS'
        ORDER BY m.startedAt DESC
        LIMIT 1
        """
    )
    fun observeResumable(): Flow<MatchSummary?>

    /** Game history: every finished match (won, lost or abandoned), most recent first. */
    @Transaction
    @Query("SELECT * FROM matches WHERE status != 'IN_PROGRESS' ORDER BY startedAt DESC")
    fun observeHistory(): Flow<List<MatchDetail>>

    /**
     * R3 + R5. The `status = 'IN_PROGRESS'` predicate makes completion idempotent: a
     * duplicate call (rotation, a re-collected flow) affects 0 rows and is a no-op
     * rather than a second, conflicting result.
     */
    @Query(
        """
        UPDATE matches
        SET status = 'COMPLETED', winningSide = :winningSide, finishedAt = :finishedAt
        WHERE id = :matchId AND status = 'IN_PROGRESS'
        """
    )
    suspend fun markCompleted(matchId: Long, winningSide: Int, finishedAt: Long): Int

    @Query(
        """
        UPDATE matches
        SET status = 'ABANDONED', finishedAt = :finishedAt
        WHERE id = :matchId AND status = 'IN_PROGRESS'
        """
    )
    suspend fun markAbandoned(matchId: Long, finishedAt: Long): Int
}
