package com.example.dominocounter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.dominocounter.data.db.relation.PlayerPairing
import com.example.dominocounter.data.db.relation.PlayerRecord
import kotlinx.coroutines.flow.Flow

/**
 * Every query here filters `status = 'COMPLETED'`: abandoned and in-progress matches
 * must never move anyone's win rate.
 */
@Dao
interface StatsDao {

    /** Segmented by format — a 1-in-3 free-for-all baseline can't be averaged with 2v2. */
    @Query(
        """
        SELECT m.format                                                 AS format,
               COUNT(*)                                                 AS played,
               SUM(CASE WHEN m.winningSide = mp.side THEN 1 ELSE 0 END) AS won
        FROM match_participants mp
        JOIN matches m ON m.id = mp.matchId
        WHERE mp.playerId = :playerId AND m.status = 'COMPLETED'
        GROUP BY m.format
        """
    )
    fun observeRecord(playerId: Long): Flow<List<PlayerRecord>>

    /**
     * "How often does Wael win partnered with Bilal vs with Moh."
     *
     * Self-join on the same match and the same side, different player. Needs no branch for
     * free-for-all: each FFA side holds one player, so the join yields nothing and synergy
     * is correctly empty.
     */
    @Query(
        """
        SELECT mate.id                                                  AS playerId,
               mate.displayName                                         AS displayName,
               COUNT(*)                                                 AS played,
               SUM(CASE WHEN m.winningSide = me.side THEN 1 ELSE 0 END) AS won
        FROM match_participants me
        JOIN match_participants partner
              ON  partner.matchId   = me.matchId
              AND partner.side      = me.side
              AND partner.playerId != me.playerId
        JOIN players mate ON mate.id = partner.playerId
        JOIN matches m    ON m.id    = me.matchId
        WHERE me.playerId = :playerId AND m.status = 'COMPLETED'
        GROUP BY mate.id
        ORDER BY (won * 1.0 / played) DESC, played DESC
        """
    )
    fun observeSynergy(playerId: Long): Flow<List<PlayerPairing>>

    /** Identical to synergy but for opponents — one operator different, by design. */
    @Query(
        """
        SELECT rival.id                                                 AS playerId,
               rival.displayName                                        AS displayName,
               COUNT(*)                                                 AS played,
               SUM(CASE WHEN m.winningSide = me.side THEN 1 ELSE 0 END) AS won
        FROM match_participants me
        JOIN match_participants opponent
              ON  opponent.matchId = me.matchId
              AND opponent.side   != me.side
        JOIN players rival ON rival.id = opponent.playerId
        JOIN matches m     ON m.id     = me.matchId
        WHERE me.playerId = :playerId AND m.status = 'COMPLETED'
        GROUP BY rival.id
        ORDER BY played DESC
        """
    )
    fun observeHeadToHead(playerId: Long): Flow<List<PlayerPairing>>

    @Query(
        """
        SELECT p.id                                                     AS playerId,
               p.displayName                                            AS displayName,
               COUNT(*)                                                 AS played,
               SUM(CASE WHEN m.winningSide = mp.side THEN 1 ELSE 0 END) AS won
        FROM match_participants mp
        JOIN matches m ON m.id = mp.matchId
        JOIN players p ON p.id = mp.playerId
        WHERE m.status = 'COMPLETED'
        GROUP BY p.id
        HAVING played >= :minimumMatches
        ORDER BY (won * 1.0 / played) DESC, played DESC
        """
    )
    fun observeLeaderboard(minimumMatches: Int = 1): Flow<List<PlayerPairing>>
}
