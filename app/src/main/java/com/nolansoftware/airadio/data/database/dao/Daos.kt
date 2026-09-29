// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nolansoftware.airadio.data.database.entity.CountryEntity
import com.nolansoftware.airadio.data.database.entity.FavoriteEntity
import com.nolansoftware.airadio.data.database.entity.LanguageEntity
import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity
import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import com.nolansoftware.airadio.data.database.entity.StationEntity
import com.nolansoftware.airadio.data.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {

    @Query("SELECT * FROM stations ORDER BY votes DESC LIMIT 50")
    fun getPopularStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE country = :country ORDER BY votes DESC LIMIT 100")
    fun getStationsByCountry(country: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE language = :language ORDER BY votes DESC LIMIT 100")
    fun getStationsByLanguage(language: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE tags LIKE '%' || :tag || '%' ORDER BY votes DESC LIMIT 100")
    fun getStationsByTag(tag: String): Flow<List<StationEntity>>

    @Query("""
        SELECT s.* FROM stations s
        INNER JOIN recently_played rp ON s.stationuuid = rp.stationuuid
        ORDER BY rp.played_time DESC LIMIT 20
    """)
    fun getRecentlyPlayedStations(): Flow<List<StationEntity>>

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

    /**
     * Reads favorites directly — no JOIN against `stations`. The favorites
     * table is now self-contained (FavoriteEntity stores the full station
     * snapshot, see AppDatabase.MIGRATION_2_3), so the daily
     * `StationDao.clearAllStations()` cycle in `runSync` cannot orphan a
     * favorite: the snapshot lives in the favorites row regardless of
     * whether the corresponding `stations` row survives the next sync.
     *
     * Returned as [FavoriteEntity] (not [StationEntity]) — callers map to
     * domain via `Mappers.FavoriteEntity.toStationDomain()`.
     */
    @Query("SELECT * FROM favorites ORDER BY added_time DESC")
    fun getFavoriteEntities(): Flow<List<FavoriteEntity>>

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

@Dao
interface PagedStationCacheDao {

    /**
     * Backing PagingSource for the Browse country / language / tag list.
     * Room's InvalidationTracker fires automatically when any row matching
     * (queryType, queryValue) is inserted or deleted, so writes from
     * StationRemoteMediator appear in the UI without manual notification.
     */
    @Query("""
        SELECT * FROM paged_station_cache
        WHERE queryType = :queryType AND queryValue = :queryValue
        ORDER BY pageOffset ASC, sortPosition ASC
    """)
    fun pagingSource(queryType: String, queryValue: String): PagingSource<Int, PagedStationCacheEntity>

    /**
     * Bulk upsert for a freshly fetched page. INSERT OR REPLACE handles the
     * case where we're refreshing an already-cached page.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(rows: List<PagedStationCacheEntity>)

    /**
     * Drop a single page. Called only from REFRESH-load, never APPEND.
     * APPEND loads preserve existing rows because they can never shrink the
     * page (a page either returns <pageSize rows and triggers endOfPaginationReached,
     * or it returns exactly pageSize).
     */
    @Query("""
        DELETE FROM paged_station_cache
        WHERE queryType = :queryType
          AND queryValue = :queryValue
          AND pageOffset = :pageOffset
    """)
    suspend fun deletePage(queryType: String, queryValue: String, pageOffset: Int)

    /**
     * TTL check used by StationRemoteMediator on REFRESH. Returns the
     * newest cached_at among rows at (queryType, queryValue, pageOffset),
     * or null if nothing is cached at that offset.
     */
    @Query("""
        SELECT MAX(cachedAt) FROM paged_station_cache
        WHERE queryType = :queryType
          AND queryValue = :queryValue
          AND pageOffset = :pageOffset
    """)
    suspend fun newestCachedAt(queryType: String, queryValue: String, pageOffset: Int): Long?

    /**
     * Background housekeeping — drops cache rows older than cutoffMillis.
     * Called from a periodic WorkManager job (separate ticket) or eagerly
     * during RemoteMediator.initialize.
     */
    @Query("DELETE FROM paged_station_cache WHERE cachedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /**
     * Lookup a single cached row by its stationuuid. Used as a fallback
     * when the global `stations` table doesn't have the row — Browse >
     * Countries / Languages / Tags stations live only in this cache after
     * the paging change. Returns the most recently cached match (the
     * composite PK would otherwise allow multiple rows for the same
     * station cached under different browse dimensions, e.g. China and
     * english).
     */
    @Query("""
        SELECT * FROM paged_station_cache
        WHERE stationuuid = :stationId
        ORDER BY cachedAt DESC
        LIMIT 1
    """)
    suspend fun findByStationId(stationId: String): com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity?
}