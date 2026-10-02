// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.di

import android.content.Context
import androidx.room.Room
import com.nolansoftware.airadio.data.api.MirrorEntry
import com.nolansoftware.airadio.data.api.MirrorRegistry
import com.nolansoftware.airadio.data.api.MirrorRegistryApi
import com.nolansoftware.airadio.data.api.RadioBrowserApi
import com.nolansoftware.airadio.data.api.RadioBrowserApiFactory
import com.nolansoftware.airadio.data.api.SharedPrefsMirrorCacheStore
import com.nolansoftware.airadio.data.api.SharedPrefsRegionStore
import com.nolansoftware.airadio.data.api.RegionStore
import com.nolansoftware.airadio.data.api.UserAgentInterceptor
import com.nolansoftware.airadio.data.database.AppDatabase
import com.nolansoftware.airadio.data.database.dao.CountryDao
import com.nolansoftware.airadio.data.database.dao.FavoritesDao
import com.nolansoftware.airadio.data.database.dao.LanguageDao
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import com.nolansoftware.airadio.data.database.dao.RecentlyPlayedDao
import com.nolansoftware.airadio.data.database.dao.StationDao
import com.nolansoftware.airadio.data.database.dao.TagDao
import com.nolansoftware.airadio.data.repository.RadioRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nolansoftware.airadio.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // Log URLs/methods/status in debug builds only. In release builds,
            // logging is silent (Level.NONE) so we never accidentally log
            // auth headers or PII if a future contributor adds them.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }
        // Default OkHttp timeouts (10s) are too short for the
        // /json/stations?limit=10000 response (often >5 MB). Bump read/write
        // timeouts to keep the cold-start sync reliable on slower networks.
        return OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(120))
            // RB-002: identify AIRadio to the Radio Browser operators. The
            // header is set last so it overrides any caller-attached UA.
            .addInterceptor(UserAgentInterceptor(BuildConfig.VERSION_NAME))
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideMirrorRegistryApi(
        okHttpClient: OkHttpClient,
        gson: Gson,
    ): MirrorRegistryApi {
        // Canonical entry point per the project docs. `all.` is supposed
        // to DNS-round-robin to a healthy backend; the user has confirmed
        // it works in their browser, so trust that signal over our CI
        // environment's connectivity (which can't reach `all.` over TLS).
        // If the user's network later loses `all.`, the executor's
        // round-robin + OkHttp's error handling cover the fallback to a
        // discovered mirror returned in the response body.
        return Retrofit.Builder()
            .baseUrl("https://all.api.radio-browser.info/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(MirrorRegistryApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMirrorRegistry(
        registryApi: MirrorRegistryApi,
        cacheStore: SharedPrefsMirrorCacheStore,
    ): MirrorRegistry = MirrorRegistry(
        fetcher = { registryApi.getServers() },
        cacheStore = cacheStore,
    )

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
            )
            .build()
    }

    @Provides
    fun provideStationDao(database: AppDatabase): StationDao = database.stationDao()

    @Provides
    fun provideCountryDao(database: AppDatabase): CountryDao = database.countryDao()

    @Provides
    fun provideLanguageDao(database: AppDatabase): LanguageDao = database.languageDao()

    @Provides
    fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()

    @Provides
    fun provideFavoritesDao(database: AppDatabase): FavoritesDao = database.favoritesDao()

    @Provides
    fun provideRecentlyPlayedDao(database: AppDatabase): RecentlyPlayedDao =
        database.recentlyPlayedDao()

    @Provides
    fun providePagedStationCacheDao(database: AppDatabase): PagedStationCacheDao =
        database.pagedStationCacheDao()

    @Provides
    @Singleton
    fun provideRadioRepository(
        apiFactory: RadioBrowserApiFactory,
        mirrorRegistry: MirrorRegistry,
        regionStore: RegionStore,
        stationDao: StationDao,
        countryDao: CountryDao,
        languageDao: LanguageDao,
        tagDao: TagDao,
        favoritesDao: FavoritesDao,
        recentlyPlayedDao: RecentlyPlayedDao,
        pagedStationCacheDao: PagedStationCacheDao,
    ): RadioRepository {
        return RadioRepository(
            apiFactory,
            mirrorRegistry,
            regionStore,
            stationDao,
            countryDao,
            languageDao,
            tagDao,
            favoritesDao,
            recentlyPlayedDao,
            pagedStationCacheDao,
        )
    }
}

/**
 * Binds the SharedPreferences-backed [RegionStore] implementation as the
 * singleton [RegionStore] consumed by `RadioRepository`. Kept in a separate
 * abstract module because `@Binds` requires an abstract class while
 * `AppModule` is an object with `@Provides` methods.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RegionStoreModule {
    @Binds
    @Singleton
    abstract fun bindRegionStore(impl: SharedPrefsRegionStore): RegionStore

    @Binds
    @Singleton
    abstract fun bindRadioBrowserApiFactory(
        impl: com.nolansoftware.airadio.data.api.DefaultRadioBrowserApiFactory,
    ): RadioBrowserApiFactory

    @Binds
    @Singleton
    abstract fun bindMirrorCacheStore(
        impl: SharedPrefsMirrorCacheStore,
    ): com.nolansoftware.airadio.data.api.MirrorCacheStore
}