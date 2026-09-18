package com.example.dominocounter.data.repo

import androidx.room.withTransaction
import com.example.dominocounter.data.db.DominoDatabase
import com.example.dominocounter.data.db.dao.MatchDao
import com.example.dominocounter.data.db.dao.RoundDao
import com.example.dominocounter.data.db.entity.MatchEntity
import com.example.dominocounter.data.db.entity.MatchParticipantEntity
import com.example.dominocounter.data.db.entity.MatchSideEntity
import com.example.dominocounter.data.db.entity.RoundEntity
import com.example.dominocounter.data.db.relation.MatchSummary
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.MatchSnapshot
import com.example.dominocounter.domain.model.MatchStatus
import com.example.dominocounter.domain.model.Outcome
import com.example.dominocounter.domain.model.Rules
import com.example.dominocounter.domain.model.ScanAudit
import com.example.dominocounter.domain.model.ScoreSource
import com.example.dominocounter.util.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a write targets a match that is no longer IN_PROGRESS (R5). */
class MatchNotEditableException(matchId: Long) :
    IllegalStateException("Match $matchId is finished and can no longer be modified")

/** One seat as chosen on the setup screen. */
data class SeatAssignment(
    val playerId: Long,
    val side: Int,
    val seatIndex: Int
)

data class NewMatch(
    val format: MatchFormat,
    val seats: List<SeatAssignment>,
    val targetScore: Int = Rules.DEFAULT_TARGET_SCORE
)

@Singleton
class MatchRepository @Inject constructor(
    private val db: DominoDatabase,
    private val matchDao: MatchDao,
    private val roundDao: RoundDao,
    private val clock: AppClock
) {

    // ------------------------------------------------------------------ reads

    fun observeMatch(matchId: Long): Flow<MatchSnapshot?> =
        matchDao.observeDetail(matchId)
            .map { it?.toSnapshot() }
            .distinctUntilChanged()

    suspend fun getMatch(matchId: Long): MatchSnapshot? = matchDao.getDetail(matchId)?.toSnapshot()

    /** R6: powers the main menu's Resume card. */
    fun observeResumable(): Flow<MatchSummary?> = matchDao.observeResumable().distinctUntilChanged()

    /** Setup screen prefill: same lineup as last time, in seat order, until edited. */
    suspend fun getLastLineupNames(): List<String> = matchDao.lastLineupNames()

    /** Game history: every match, unfinished ones first, then most recent first. */
    fun observeHistory(): Flow<List<MatchSnapshot>> =
        matchDao.observeHistory().map { list -> list.map { it.toSnapshot() } }

    // ------------------------------------------------------------------ writes

    /**
     * Creates the match at the END of setup, so the scoreboard always receives a real id.
     * That is what makes a match survive process death and stay resumable (R6).
     */
    suspend fun createMatch(request: NewMatch): Long {
        validate(request)
        return db.withTransaction {
            val matchId = matchDao.insertMatch(
                MatchEntity(
                    startedAt = clock.now(),
                    status = MatchStatus.IN_PROGRESS,
                    format = request.format,
                    targetScore = request.targetScore
                )
            )
            matchDao.insertParticipants(
                request.seats.map {
                    MatchParticipantEntity(
                        matchId = matchId,
                        playerId = it.playerId,
                        side = it.side,
                        seatIndex = it.seatIndex
                    )
                }
            )
            matchId
        }
    }

    suspend fun addRound(
        matchId: Long,
        side: Int,
        points: Int,
        source: ScoreSource,
        scan: ScanAudit? = null
    ): Long = db.withTransaction {
        // An INSERT can't carry the status guard in its own WHERE clause, so it is checked
        // here — inside the same transaction, which is what makes the check meaningful.
        requireEditable(matchId)

        roundDao.insert(
            RoundEntity(
                matchId = matchId,
                sequence = roundDao.nextSequence(matchId),
                side = side,
                points = points,
                source = source,
                createdAt = clock.now(),
                detectedTileCount = scan?.detectedTileCount,
                rawPipTotal = scan?.rawPipTotal
            )
        )
    }

    /** R1: a foul is an ordinary round crediting the side whose button was tapped. */
    suspend fun addFoul(matchId: Long, side: Int): Long =
        addRound(matchId, side, Rules.FOUL_POINTS, ScoreSource.FOUL)

    suspend fun editRound(roundId: Long, newPoints: Int) {
        val affected = roundDao.updatePoints(roundId, newPoints, clock.now())
        if (affected == 0) throw failureFor(roundId)
    }

    suspend fun deleteRound(roundId: Long) {
        val affected = roundDao.delete(roundId)
        if (affected == 0) throw failureFor(roundId)
    }

    /**
     * R3 + R5. Freezes the final scores and closes the match.
     *
     * Idempotent: `markCompleted` only touches an IN_PROGRESS row, so a duplicate call
     * (rotation, a re-collected flow) affects 0 rows and returns without writing a second,
     * conflicting set of frozen scores.
     */
    suspend fun completeMatch(matchId: Long, outcome: Outcome) {
        db.withTransaction {
            val affected = matchDao.markCompleted(matchId, outcome.winningSide, clock.now())
            if (affected == 0) return@withTransaction

            matchDao.insertSides(
                outcome.finalScores.mapIndexed { side, score ->
                    MatchSideEntity(matchId = matchId, side = side, finalScore = score)
                }
            )
        }
    }

    /** R6: explicit user action, never automatic. */
    suspend fun abandonMatch(matchId: Long) {
        matchDao.markAbandoned(matchId, clock.now())
    }

    // ------------------------------------------------------------------ internals

    private suspend fun requireEditable(matchId: Long) {
        val status = matchDao.statusOf(matchId) ?: throw MatchNotEditableException(matchId)
        if (status != MatchStatus.IN_PROGRESS) throw MatchNotEditableException(matchId)
    }

    /** 0 rows affected means either "no such round" or "match finished" — distinguish them. */
    private suspend fun failureFor(roundId: Long): Exception {
        val round = roundDao.findById(roundId)
            ?: return NoSuchElementException("No round with id $roundId")
        return MatchNotEditableException(round.matchId)
    }

    private fun validate(request: NewMatch) {
        require(request.seats.size == request.format.playerCount) {
            "${request.format} needs ${request.format.playerCount} players, got ${request.seats.size}"
        }
        require(request.seats.map { it.playerId }.toSet().size == request.seats.size) {
            "The same player cannot occupy two seats"
        }
        require(request.seats.all { it.side in 0 until request.format.sideCount }) {
            "Seat assigned to a side outside ${request.format}"
        }
        require(request.targetScore > 0) { "Target score must be positive" }
    }
}
