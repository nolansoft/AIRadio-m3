// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nolansoftware.airadio.data.database.dao.CountryDao
import com.nolansoftware.airadio.data.database.dao.FavoritesDao
import com.nolansoftware.airadio.data.database.dao.LanguageDao
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import com.nolansoftware.airadio.data.database.dao.RecentlyPlayedDao
import com.nolansoftware.airadio.data.database.dao.StationDao
import com.nolansoftware.airadio.data.database.dao.TagDao
import com.nolansoftware.airadio.data.database.entity.CountryEntity
import com.nolansoftware.airadio.data.database.entity.FavoriteEntity
import com.nolansoftware.airadio.data.database.entity.LanguageEntity
import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity
import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import com.nolansoftware.airadio.data.database.entity.StationEntity
import com.nolansoftware.airadio.data.database.entity.TagEntity

@Database(
    entities = [
        StationEntity::class,
        CountryEntity::class,
        LanguageEntity::class,
        TagEntity::class,
        FavoriteEntity::class,
        RecentlyPlayedEntity::class,
        PagedStationCacheEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
    abstract fun countryDao(): CountryDao
    abstract fun languageDao(): LanguageDao
    abstract fun tagDao(): TagDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun pagedStationCacheDao(): PagedStationCacheDao

    companion object {
        const val DATABASE_NAME = "airadio_database"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS paged_station_cache (
                      queryType TEXT NOT NULL,
                      queryValue TEXT NOT NULL,
                      pageOffset INTEGER NOT NULL,
                      sortPosition INTEGER NOT NULL,
                      cachedAt INTEGER NOT NULL,
                      stationuuid TEXT NOT NULL,
                      name TEXT NOT NULL,
                      url TEXT NOT NULL,
                      url_resolved TEXT NOT NULL,
                      favicon TEXT NOT NULL,
                      country TEXT NOT NULL,
                      countrycode TEXT NOT NULL,
                      language TEXT NOT NULL,
                      tags TEXT NOT NULL,
                      codec TEXT NOT NULL,
                      bitrate INTEGER NOT NULL,
                      votes INTEGER NOT NULL,
                      lastchecktime INTEGER NOT NULL,
                      PRIMARY KEY (queryType, queryValue, pageOffset, sortPosition)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS idx_paged_cache_query
                    ON paged_station_cache (queryType, queryValue)
                """.trimIndent())
            }
        }
    }
}
