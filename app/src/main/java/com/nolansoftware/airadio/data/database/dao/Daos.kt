package com.nolansoftware.airadio.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nolansoftware.airadio.data.database.entity.CountryEntity
import com.nolansoftware.airadio.data.database.entity.FavoriteEntity
import com.nolansoftware.airadio.data.database.entity.LanguageEntity
import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import com.nolansoftware.airadio.data.database.entity.StationEntity
import com.nolansoftware.airadio.data.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {

    @Query("SELECT * FROM stations ORDER BY votes DESC LIMIT 50")
    fun getPopularStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE country = :country ORDER BY votes DESC LIMIT 50")
    fun getStationsByCountry(country: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE language = :language ORDER BY votes DESC LIMIT 50")
    fun getStationsByLanguage(language: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE tags LIKE '%' || :tag || '%' ORDER BY votes DESC LIMIT 50")
    fun getStationsByTag(tag: String): Flow<List<StationEntity>>

    @Query("""
        SELECT s.* FROM stations s
        INNER JOIN recently_played rp ON s.stationuuid = rp.stationuuid
        ORDER BY rp.played_time DESC LIMIT 20
    """)
    fun getRecentlyPlayedStations(): Flow<List<StationEntity>>

    @Query("""
        SELECT s.* FROM stations s
        INNER JOIN favorites f ON s.stationuuid = f.stationuuid
        ORDER BY f.added_time DESC
    """)
    fun getFavoriteStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE stationuuid = :stationId")
    suspend fun getStationById(stationId: String): StationEntity?

    @Query("""
        SELECT * FROM stations
        WHERE name LIKE '%' || :query || '%'
        OR country LIKE '%' || :query || '%'
        OR tags LIKE '%' || :query || '%'
        ORDER BY votes DESC LIMIT 100
    """)
    fun searchStations(query: String): Flow<List<StationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationEntity>)

    @Query("DELETE FROM stations")
    suspend fun clearAllStations()
}

@Dao
interface CountryDao {

    @Query("SELECT * FROM countries ORDER BY name ASC")
    fun getAllCountries(): Flow<List<CountryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCountries(countries: List<CountryEntity>)

    @Query("DELETE FROM countries")
    suspend fun clearAllCountries()
}

@Dao
interface LanguageDao {

    @Query("SELECT * FROM languages ORDER BY name ASC")
    fun getAllLanguages(): Flow<List<LanguageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLanguages(languages: List<LanguageEntity>)

    @Query("DELETE FROM languages")
    suspend fun clearAllLanguages()
}

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY stationcount DESC LIMIT 100")
    fun getPopularTags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Query("DELETE FROM tags")
    suspend fun clearAllTags()
}

@Dao
interface FavoritesDao {

    @Query("SELECT EXISTS(SELECT * FROM favorites WHERE stationuuid = :stationId)")
    fun isFavorite(stationId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToFavorites(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE stationuuid = :stationId")
    suspend fun removeFromFavorites(stationId: String)
}

@Dao
interface RecentlyPlayedDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToRecentlyPlayed(recentlyPlayed: RecentlyPlayedEntity)

    @Query("DELETE FROM recently_played WHERE played_time < :cutoffTime")
    suspend fun cleanupOldRecentlyPlayed(cutoffTime: Long)

    @Query("DELETE FROM recently_played")
    suspend fun clearAllRecentlyPlayed()
}