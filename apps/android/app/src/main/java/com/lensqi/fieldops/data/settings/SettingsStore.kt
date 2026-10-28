package com.lensqi.fieldops.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lensqi.fieldops.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** The API base URL is the only setting. It is a device preference, never something the server or a model supplies. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("api_base")
    private val current = AtomicReference(DEFAULT_BASE.toHttpUrlOrNull()!!)

    val apiBase: Flow<String> = context.dataStore.data.map { it[key] ?: DEFAULT_BASE }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            apiBase.collect { value -> normalize(value)?.let(current::set) }
        }
    }

    /** Read synchronously by the OkHttp interceptor on every request. */
    fun currentBase(): HttpUrl = current.get()

    suspend fun setApiBase(value: String): Boolean {
        val url = normalize(value) ?: return false
        current.set(url)
        context.dataStore.edit { it[key] = url.toString() }
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
