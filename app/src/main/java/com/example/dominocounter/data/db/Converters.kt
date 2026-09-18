package com.example.dominocounter.data.db

import androidx.room.TypeConverter
import com.example.dominocounter.domain.model.MatchFormat
import com.example.dominocounter.domain.model.MatchStatus
import com.example.dominocounter.domain.model.ScoreSource

/**
 * Enums are stored as names, never ordinals: an ordinal silently breaks the day someone
 * reorders the enum, and a name stays readable in a DB inspector and in the exported
 * schema JSON. The raw SQL guards in RoundDao depend on this too.
 */
class Converters {

    @TypeConverter fun statusToString(value: MatchStatus): String = value.name
    @TypeConverter fun statusFromString(value: String): MatchStatus = MatchStatus.valueOf(value)

    @TypeConverter fun formatToString(value: MatchFormat): String = value.name
    @TypeConverter fun formatFromString(value: String): MatchFormat = MatchFormat.valueOf(value)

    @TypeConverter fun sourceToString(value: ScoreSource): String = value.name
    @TypeConverter fun sourceFromString(value: String): ScoreSource = ScoreSource.valueOf(value)
}
