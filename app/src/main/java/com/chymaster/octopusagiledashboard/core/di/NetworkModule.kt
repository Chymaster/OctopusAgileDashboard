package com.chymaster.octopusagiledashboard.core.di

import android.util.Log
import com.chymaster.octopusagiledashboard.BuildConfig
import com.chymaster.octopusagiledashboard.core.network.AuthInterceptor
import com.chymaster.octopusagiledashboard.core.network.LogRedactor
import com.chymaster.octopusagiledashboard.core.network.RetryInterceptor
import com.chymaster.octopusagiledashboard.core.util.Constants
import com.chymaster.octopusagiledashboard.data.remote.api.CarbonIntensityApiService
import com.chymaster.octopusagiledashboard.data.remote.api.OctopusApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Shared HTTP logger.
     *
     * `BODY` logging writes request/response bodies to logcat, which for this
     * app includes the API key (inside the Kraken token mutation) and the
     * access/refresh tokens. So it is:
     *  - restricted to debug builds, and
     *  - scrubbed line by line through [LogRedactor].
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        val interceptor = HttpLoggingInterceptor { message ->
            Log.d(LOG_TAG, LogRedactor.redact(message))
        }
        interceptor.level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        interceptor.redactHeader("Authorization")
        return interceptor
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        return Retrofit.Builder()
            .baseUrl(Constants.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideOctopusApiService(retrofit: Retrofit): OctopusApiService {
        return retrofit.create(OctopusApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideCarbonIntensityApiService(
        json: Json,
        loggingInterceptor: HttpLoggingInterceptor
    ): CarbonIntensityApiService {
        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.CARBON_INTENSITY_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(CarbonIntensityApiService::class.java)
    }

    // ── GraphQL (Kraken) ────────────────────────────────────────────

    @Provides
    @Singleton
    @Named("graphql")
    fun provideGraphQLOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val LOG_TAG = "OkHttp"
}
