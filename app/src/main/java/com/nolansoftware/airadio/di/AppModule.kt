package com.nolansoftware.airadio.di

import android.content.Context
import androidx.room.Room
import com.nolansoftware.airadio.data.api.RadioBrowserApi
import com.nolansoftware.airadio.data.database.AppDatabase
import com.nolansoftware.airadio.data.database.dao.CountryDao
import com.nolansoftware.airadio.data.database.dao.FavoritesDao
import com.nolansoftware.airadio.data.database.dao.LanguageDao
import com.nolansoftware.airadio.data.database.dao.RecentlyPlayedDao
import com.nolansoftware.airadio.data.database.dao.StationDao
import com.nolansoftware.airadio.data.database.dao.TagDao
import com.nolansoftware.airadio.data.repository.RadioRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // Default OkHttp timeouts (10s) are too short for the
        // /json/stations?limit=10000 response (often >5 MB). Bump read/write
        // timeouts to keep the cold-start sync reliable on slower networks.
        return OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(120))
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideRadioBrowserApi(okHttpClient: OkHttpClient, gson: Gson): RadioBrowserApi {
        return Retrofit.Builder()
            .baseUrl(RadioBrowserApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(RadioBrowserApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()
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
    @Singleton
    fun provideRadioRepository(
        api: RadioBrowserApi,
        stationDao: StationDao,
        countryDao: CountryDao,
        languageDao: LanguageDao,
        tagDao: TagDao,
        favoritesDao: FavoritesDao,
        recentlyPlayedDao: RecentlyPlayedDao
    ): RadioRepository {
        return RadioRepository(
            api,
            stationDao,
            countryDao,
            languageDao,
            tagDao,
            favoritesDao,
            recentlyPlayedDao
        )
    }
}