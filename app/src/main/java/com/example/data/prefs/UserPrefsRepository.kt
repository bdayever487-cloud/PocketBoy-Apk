package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketboy_prefs")

data class EmulatorSettings(
    val romFolderUri: String? = null,
    val romFolderLabel: String? = null,
    val lastScanTimestamp: Long = 0L,
    val soundEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val fastForwardMultiplier: Int = 2,
    val gbPalettePreset: Int = 0, // 0=Classic DMG Olive, 1=Pocket Gray, 2=Crisp LCD
    val controllerOpacity: Float = 0.85f,
    val showFps: Boolean = false,
    val pixelFilter: Boolean = true
)

class UserPrefsRepository(private val context: Context) {
    private object Keys {
        val ROM_FOLDER_URI = stringPreferencesKey("rom_folder_uri")
        val ROM_FOLDER_LABEL = stringPreferencesKey("rom_folder_label")
        val LAST_SCAN_TS = longPreferencesKey("last_scan_ts")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val FAST_FORWARD_MULT = intPreferencesKey("fast_forward_mult")
        val GB_PALETTE_PRESET = intPreferencesKey("gb_palette_preset")
        val CONTROLLER_OPACITY = floatPreferencesKey("controller_opacity")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val PIXEL_FILTER = booleanPreferencesKey("pixel_filter")
    }

    val settings: Flow<EmulatorSettings> = context.dataStore.data.map { prefs ->
        EmulatorSettings(
            romFolderUri = prefs[Keys.ROM_FOLDER_URI],
            romFolderLabel = prefs[Keys.ROM_FOLDER_LABEL],
            lastScanTimestamp = prefs[Keys.LAST_SCAN_TS] ?: 0L,
            soundEnabled = prefs[Keys.SOUND_ENABLED] ?: true,
            hapticEnabled = prefs[Keys.HAPTIC_ENABLED] ?: true,
            fastForwardMultiplier = prefs[Keys.FAST_FORWARD_MULT] ?: 2,
            gbPalettePreset = prefs[Keys.GB_PALETTE_PRESET] ?: 0,
            controllerOpacity = prefs[Keys.CONTROLLER_OPACITY] ?: 0.85f,
            showFps = prefs[Keys.SHOW_FPS] ?: false,
            pixelFilter = prefs[Keys.PIXEL_FILTER] ?: true
        )
    }

    suspend fun setRomFolder(uriString: String, label: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROM_FOLDER_URI] = uriString
            prefs[Keys.ROM_FOLDER_LABEL] = label
        }
    }

    suspend fun setLastScanTimestamp(ts: Long) {
        context.dataStore.edit { it[Keys.LAST_SCAN_TS] = ts }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTIC_ENABLED] = enabled }
    }

    suspend fun setFastForwardMultiplier(mult: Int) {
        context.dataStore.edit { it[Keys.FAST_FORWARD_MULT] = mult.coerceIn(2, 3) }
    }

    suspend fun setGbPalettePreset(preset: Int) {
        context.dataStore.edit { it[Keys.GB_PALETTE_PRESET] = preset.coerceIn(0, 2) }
    }

    suspend fun setControllerOpacity(opacity: Float) {
        context.dataStore.edit { it[Keys.CONTROLLER_OPACITY] = opacity.coerceIn(0.35f, 1.0f) }
    }

    suspend fun setShowFps(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_FPS] = show }
    }

    suspend fun setPixelFilter(crisp: Boolean) {
        context.dataStore.edit { it[Keys.PIXEL_FILTER] = crisp }
    }
}
