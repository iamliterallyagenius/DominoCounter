package com.example.dominocounter.di

import android.content.Context
import androidx.room.Room
import com.example.dominocounter.data.db.DominoDatabase
import com.example.dominocounter.data.db.dao.MatchDao
import com.example.dominocounter.data.db.dao.PlayerDao
import com.example.dominocounter.data.db.dao.RoundDao
import com.example.dominocounter.data.db.dao.StatsDao
import com.example.dominocounter.util.AppClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DominoDatabase =
        Room.databaseBuilder(context, DominoDatabase::class.java, DominoDatabase.NAME)
            // No fallbackToDestructiveMigration: match history is not disposable.
            // Version 2 gets a real Migration.
            .build()

    @Provides fun providePlayerDao(db: DominoDatabase): PlayerDao = db.playerDao()
    @Provides fun provideMatchDao(db: DominoDatabase): MatchDao = db.matchDao()
    @Provides fun provideRoundDao(db: DominoDatabase): RoundDao = db.roundDao()
    @Provides fun provideStatsDao(db: DominoDatabase): StatsDao = db.statsDao()

    @Provides
    @Singleton
    fun provideClock(): AppClock = AppClock.System
}
