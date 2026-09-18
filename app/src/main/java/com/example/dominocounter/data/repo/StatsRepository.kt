package com.example.dominocounter.data.repo

import com.example.dominocounter.data.db.dao.StatsDao
import com.example.dominocounter.data.db.relation.PlayerPairing
import com.example.dominocounter.data.db.relation.PlayerRecord
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val statsDao: StatsDao
) {
    fun observeLeaderboard(minimumMatches: Int = 1): Flow<List<PlayerPairing>> =
        statsDao.observeLeaderboard(minimumMatches)

    fun observeRecord(playerId: Long): Flow<List<PlayerRecord>> = statsDao.observeRecord(playerId)

    fun observeSynergy(playerId: Long): Flow<List<PlayerPairing>> = statsDao.observeSynergy(playerId)

    fun observeHeadToHead(playerId: Long): Flow<List<PlayerPairing>> = statsDao.observeHeadToHead(playerId)
}
