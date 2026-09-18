package com.example.dominocounter.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.dominocounter.domain.model.Rules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Key-value config with no queries — kept out of Room entirely so a new toggle never
 * needs a schema migration.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val TARGET_SCORE = intPreferencesKey("target_score")
        val TORCH_DEFAULT_ON = booleanPreferencesKey("torch_default_on")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val SCANNER_DEBUG_ENABLED = booleanPreferencesKey("scanner_debug_enabled")
    }

    /** R3's default — snapshotted per match at creation, never re-read from here afterwards. */
    val targetScore: Flow<Int> = context.settingsDataStore.data
        .map { it[Keys.TARGET_SCORE] ?: Rules.DEFAULT_TARGET_SCORE }

    val torchDefaultOn: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.TORCH_DEFAULT_ON] ?: false }

    val hapticsEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.HAPTICS_ENABLED] ?: true }

    /** Off by default: the mask/detections overlay is a diagnostic tool, not a player-facing feature. */
    val scannerDebugEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[Keys.SCANNER_DEBUG_ENABLED] ?: false }

    suspend fun setTargetScore(value: Int) {
        context.settingsDataStore.edit { it[Keys.TARGET_SCORE] = value }
    }

    suspend fun setTorchDefaultOn(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TORCH_DEFAULT_ON] = value }
    }

    suspend fun setHapticsEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.HAPTICS_ENABLED] = value }
    }

    suspend fun setScannerDebugEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SCANNER_DEBUG_ENABLED] = value }
    }
}
