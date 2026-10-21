package com.example.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

object ControllerDataStore {
    private val CONTROLLER_LAYOUT = stringPreferencesKey("controller_layout")
    private val CONTROLLER_SCALE = stringPreferencesKey("controller_scale")

    fun getLayout(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[CONTROLLER_LAYOUT]
        }
    }

    suspend fun saveLayout(context: Context, layoutString: String) {
        context.dataStore.edit { preferences ->
            preferences[CONTROLLER_LAYOUT] = layoutString
        }
    }
    
    fun getScale(context: Context): Flow<Float> {
        return context.dataStore.data.map { preferences ->
            preferences[CONTROLLER_SCALE]?.toFloatOrNull() ?: 1.0f
        }
    }

    suspend fun saveScale(context: Context, scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[CONTROLLER_SCALE] = scale.toString()
        }
    }
}
