package com.lensqi.fieldops.di

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** Unknown members are ignored so a newer server stays readable; the contract tests pin the fields we rely on. */
    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Provides @Singleton
    fun okHttp(settings: SettingsStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor(settings::currentBase))
        .addNetworkInterceptor(com.lensqi.fieldops.data.api.ClockSkew.interceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        // Muse and SAM calls take 5–20 s; the server cuts a model request at 90 s.
        .readTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Each screen gets a client pinned to the selected server. Existing requests cannot change destinations.
    @Provides
    fun api(client: OkHttpClient, json: Json, settings: SettingsStore): FieldOpsApi =
        retrofit(client, json, settings.currentBase().toString()).create(FieldOpsApi::class.java)

    fun retrofit(client: OkHttpClient, json: Json, base: String = PLACEHOLDER_BASE): Retrofit = Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /** Retrofit needs a fixed base; requests are re-pointed at the configured API base at send time. */
    fun baseUrlInterceptor(base: () -> okhttp3.HttpUrl) = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != PLACEHOLDER_HOST) return@Interceptor chain.proceed(request)
        val target = base()
        val relative = request.url.encodedPath.removePrefix("/") + (request.url.encodedQuery?.let { "?$it" } ?: "")
        chain.proceed(request.newBuilder().url(target.resolve(relative)!!).build())
    }

    private const val PLACEHOLDER_HOST = "fieldops.invalid"
    private const val PLACEHOLDER_BASE = "http://$PLACEHOLDER_HOST/"
}

/** Queued commands carry their own server; they never inherit a later Settings change. */
class ApiFactory @javax.inject.Inject constructor(private val client: OkHttpClient, private val json: Json) {
    fun forServer(base: String): FieldOpsApi = NetworkModule.retrofit(client, json, base).create(FieldOpsApi::class.java)
}
