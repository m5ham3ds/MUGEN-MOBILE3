package com.example.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object SettingsDataStore {
    private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val CUSTOM_GAME_PATH = stringPreferencesKey("custom_game_path")
    private val CUSTOM_GAME_URI = stringPreferencesKey("custom_game_uri")
    private val LANGUAGE = stringPreferencesKey("language")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val PRIMARY_COLOR = stringPreferencesKey("primary_color")

    fun getLanguage(context: Context): Flow<String> = context.dataStore.data.map { it[LANGUAGE] ?: "en" }
    suspend fun setLanguage(context: Context, lang: String) { context.dataStore.edit { it[LANGUAGE] = lang } }

    fun getThemeMode(context: Context): Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "dark" }
    suspend fun setThemeMode(context: Context, mode: String) { context.dataStore.edit { it[THEME_MODE] = mode } }

    fun getPrimaryColor(context: Context): Flow<String> = context.dataStore.data.map { it[PRIMARY_COLOR] ?: "red" }
    suspend fun setPrimaryColor(context: Context, color: String) { context.dataStore.edit { it[PRIMARY_COLOR] = color } }

    fun getCustomGamePath(context: Context): Flow<String?> = context.dataStore.data.map { it[CUSTOM_GAME_PATH] }
    suspend fun setCustomGamePath(context: Context, path: String?) {
        context.dataStore.edit { if (path == null) it.remove(CUSTOM_GAME_PATH) else it[CUSTOM_GAME_PATH] = path }
    }

    fun getCustomGameUri(context: Context): Flow<String?> = context.dataStore.data.map { it[CUSTOM_GAME_URI] }
    suspend fun setCustomGameUri(context: Context, uri: String?) {
        context.dataStore.edit { if (uri == null) it.remove(CUSTOM_GAME_URI) else it[CUSTOM_GAME_URI] = uri }
    }

    fun isOnboardingCompleted(context: Context): Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    suspend fun setOnboardingCompleted(context: Context, completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }
}
