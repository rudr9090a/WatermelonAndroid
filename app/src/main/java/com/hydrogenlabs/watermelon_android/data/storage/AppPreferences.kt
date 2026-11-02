package com.hydrogenlabs.watermelon_android.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("watermelon_settings")

class AppPreferences(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val usernameKey = stringPreferencesKey("username")
    private val sessionKey = stringPreferencesKey("session_token")
    private val metadataKey = stringPreferencesKey("song_metadata")

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[serverUrlKey] }
    val username: Flow<String?> = context.dataStore.data.map { it[usernameKey] }
    val session: Flow<String?> = context.dataStore.data.map { it[sessionKey] }
    val metadata: Flow<String?> = context.dataStore.data.map { it[metadataKey] }

    suspend fun saveServerUrl(value: String) = context.dataStore.edit { it[serverUrlKey] = value.trim().trimEnd('/') }
    suspend fun saveUsername(value: String) = context.dataStore.edit { it[usernameKey] = value }
    suspend fun saveSession(value: String) = context.dataStore.edit { it[sessionKey] = value }
    suspend fun saveMetadata(value: String) = context.dataStore.edit { it[metadataKey] = value }
    suspend fun clear() = context.dataStore.edit { it.clear() }
}
