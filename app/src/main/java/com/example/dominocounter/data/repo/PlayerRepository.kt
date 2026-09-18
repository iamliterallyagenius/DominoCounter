package com.example.dominocounter.data.repo

import com.example.dominocounter.data.db.dao.PlayerDao
import com.example.dominocounter.data.db.entity.PlayerEntity
import com.example.dominocounter.domain.model.Player
import com.example.dominocounter.util.AppClock
import com.example.dominocounter.util.NameNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PlayerRepository @Inject constructor(
    private val playerDao: PlayerDao,
    private val clock: AppClock
) {

    fun observeRecent(limit: Int = 20): Flow<List<Player>> =
        playerDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    /** Backs the setup screen's autocomplete. Empty query falls back to recency. */
    fun observeSuggestions(query: String): Flow<List<Player>> {
        val normalized = NameNormalizer.normalize(query)
        return if (normalized.isEmpty()) {
            observeRecent()
        } else {
            playerDao.observeMatching(normalized).map { list -> list.map { it.toDomain() } }
        }
    }

    /**
     * Resolves a typed name to a player row, creating it only if the normalized key is new.
     *
     * The insert-then-reread shape (rather than read-then-insert) closes the race where two
     * seats in the same setup screen resolve the same new name at once: the unique index
     * rejects the second insert, and we read back the winner.
     */
    suspend fun getOrCreate(rawName: String): Player {
        val displayName = NameNormalizer.displayForm(rawName)
        require(displayName.isNotEmpty()) { "Player name cannot be blank" }
        val normalized = NameNormalizer.normalize(displayName)

        val candidate = PlayerEntity(
            displayName = displayName,
            normalizedName = normalized,
            colorSeed = Random.nextInt(),
            createdAt = clock.now()
        )

        val insertedId = playerDao.insertIgnoringDuplicates(candidate)
        if (insertedId > 0) return candidate.copy(id = insertedId).toDomain()

        return checkNotNull(playerDao.findByNormalizedName(normalized)) {
            "Insert was rejected as a duplicate but no existing row matched '$normalized'"
        }.toDomain()
    }

    suspend fun markPlayed(playerIds: List<Long>) {
        if (playerIds.isNotEmpty()) playerDao.touchLastPlayed(playerIds, clock.now())
    }

    suspend fun rename(playerId: Long, rawName: String) {
        val displayName = NameNormalizer.displayForm(rawName)
        require(displayName.isNotEmpty()) { "Player name cannot be blank" }
        playerDao.rename(playerId, displayName, NameNormalizer.normalize(displayName))
    }

    suspend fun archive(playerId: Long) = playerDao.archive(playerId)
}
