package com.lensqi.fieldops.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lensqi.fieldops.BuildConfig
import com.lensqi.fieldops.ui.theme.Appearance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

const val DIAGNOSTICS_DEFAULT = false

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Device preferences: the API base URL and the appearance. Never something the server or a model supplies. */
@Singleton
class SettingsStore internal constructor(private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.dataStore)
    private val key = stringPreferencesKey("api_base")

    val apiBase: Flow<String> = store.data.map { it[key] ?: DEFAULT_BASE }

    private val diagnosticsKey = booleanPreferencesKey("diagnostic_overlays")
    val diagnostics: Flow<Boolean> = store.data.map { it[diagnosticsKey] ?: DIAGNOSTICS_DEFAULT }
    suspend fun setDiagnostics(enabled: Boolean) { store.edit { it[diagnosticsKey] = enabled } }

    private val appearanceKey = stringPreferencesKey("appearance")
    val appearance: Flow<Appearance> = store.data.map { p -> Appearance.entries.firstOrNull { it.name == p[appearanceKey] } ?: Appearance.System }
    suspend fun setAppearance(value: Appearance) { store.edit { it[appearanceKey] = value.name } }

    private val heightKey = stringPreferencesKey("height_cm")
    private val toolsKey = stringSetPreferencesKey("tools")
    /** Height and tools the person gave in Profile; sent with each new task. */
    val person: Flow<PersonProfile> = store.data.map { p -> PersonProfile(p[heightKey]?.toIntOrNull(), p[toolsKey].orEmpty()) }
    suspend fun setPerson(value: PersonProfile) {
        store.edit { p ->
            value.heightCm?.let { p[heightKey] = it.toString() } ?: p.remove(heightKey)
            p[toolsKey] = value.tools
        }
    }

    /** Read the persisted selection before creating any server-bound client, including on cold start. */
    fun currentBase(): HttpUrl = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
        normalize(apiBase.first()) ?: DEFAULT_BASE.toHttpUrlOrNull()!!
    }

    suspend fun setApiBase(value: String): Boolean {
        val url = normalize(value) ?: return false
        store.edit { it[key] = url.toString() }
        return true
    }

    companion object {
        const val DEFAULT_BASE = BuildConfig.DEFAULT_API_BASE

        fun normalize(value: String): HttpUrl? {
            val trimmed = value.trim().let { if (it.endsWith("/")) it else "$it/" }
            return trimmed.toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
        }
    }
}
