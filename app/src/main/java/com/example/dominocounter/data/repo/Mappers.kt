package com.example.dominocounter.data.repo

import com.example.dominocounter.data.db.entity.PlayerEntity
import com.example.dominocounter.data.db.entity.RoundEntity
import com.example.dominocounter.data.db.relation.MatchDetail
import com.example.dominocounter.data.db.relation.ParticipantWithPlayer
import com.example.dominocounter.domain.model.MatchSnapshot
import com.example.dominocounter.domain.model.Player
import com.example.dominocounter.domain.model.Round
import com.example.dominocounter.domain.model.ScanAudit
import com.example.dominocounter.domain.model.Seat

/**
 * Room types stop here. Nothing above the repository layer imports an *Entity.
 */

fun PlayerEntity.toDomain() = Player(
    id = id,
    displayName = displayName,
    colorSeed = colorSeed
)

fun ParticipantWithPlayer.toSeat() = Seat(
    player = player.toDomain(),
    side = participant.side,
    seatIndex = participant.seatIndex
)

fun RoundEntity.toDomain() = Round(
    id = id,
    sequence = sequence,
    side = side,
    points = points,
    source = source,
    createdAt = createdAt,
    editedAt = editedAt,
    scan = if (detectedTileCount != null && rawPipTotal != null) {
        ScanAudit(detectedTileCount, rawPipTotal)
    } else {
        null
    }
)

fun MatchDetail.toSnapshot() = MatchSnapshot(
    id = match.id,
    format = match.format,
    status = match.status,
    targetScore = match.targetScore,
    startedAt = match.startedAt,
    finishedAt = match.finishedAt,
    winningSide = match.winningSide,
    lineup = participants.map { it.toSeat() }.sortedWith(compareBy({ it.side }, { it.seatIndex })),
    rounds = rounds.map { it.toDomain() }.sortedBy { it.sequence }
)
