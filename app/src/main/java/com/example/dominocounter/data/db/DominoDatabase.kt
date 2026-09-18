package com.example.dominocounter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.dominocounter.data.db.dao.MatchDao
import com.example.dominocounter.data.db.dao.PlayerDao
import com.example.dominocounter.data.db.dao.RoundDao
import com.example.dominocounter.data.db.dao.StatsDao
import com.example.dominocounter.data.db.entity.MatchEntity
import com.example.dominocounter.data.db.entity.MatchParticipantEntity
import com.example.dominocounter.data.db.entity.MatchSideEntity
import com.example.dominocounter.data.db.entity.PlayerEntity
import com.example.dominocounter.data.db.entity.RoundEntity

@Database(
    entities = [
        PlayerEntity::class,
        MatchEntity::class,
        MatchParticipantEntity::class,
        MatchSideEntity::class,
        RoundEntity::class
    ],
    version = 1,
    exportSchema = true          // schema JSON is committed from day one so migrations stay diffable
)
@TypeConverters(Converters::class)
abstract class DominoDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun matchDao(): MatchDao
    abstract fun roundDao(): RoundDao
    abstract fun statsDao(): StatsDao

    companion object {
        const val NAME = "domino.db"
    }
}
