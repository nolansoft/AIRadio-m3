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
    version = 3,
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

        // v2 → v3: denormalize station snapshot into favorites so the favorites
        // table becomes self-contained. After this migration, runSync's
        // clearAllStations() no longer orphans favorites — the snapshot was
        // captured at toggle time and lives in the favorites row itself.
        //
        // Existing favorites get backfilled from `stations` (and a second pass
        // from `paged_station_cache` for stations that came from Browse > Tags
        // paging and never landed in the popular top-N). Any favorite that
        // matches neither table is deleted — it was already invisible to the
        // user under the old INNER JOIN, so dropping it is consistent with the
        // pre-migration UX.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add snapshot columns with empty defaults so the migration
                // doesn't fail on existing rows.
                db.execSQL("ALTER TABLE favorites ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN url TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN url_resolved TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN favicon TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN country TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN countrycode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN language TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN codec TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN bitrate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE favorites ADD COLUMN votes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE favorites ADD COLUMN lastchecktime INTEGER NOT NULL DEFAULT 0")

                // Backfill pass 1: from the popular `stations` table.
                db.execSQL("""
                    UPDATE favorites SET
                        name = COALESCE((SELECT s.name FROM stations s WHERE s.stationuuid = favorites.stationuuid), name),
                        url = COALESCE((SELECT s.url FROM stations s WHERE s.stationuuid = favorites.stationuuid), url),
                        url_resolved = COALESCE((SELECT s.url_resolved FROM stations s WHERE s.stationuuid = favorites.stationuuid), url_resolved),
                        favicon = COALESCE((SELECT s.favicon FROM stations s WHERE s.stationuuid = favorites.stationuuid), favicon),
                        country = COALESCE((SELECT s.country FROM stations s WHERE s.stationuuid = favorites.stationuuid), country),
                        countrycode = COALESCE((SELECT s.countrycode FROM stations s WHERE s.stationuuid = favorites.stationuuid), countrycode),
                        language = COALESCE((SELECT s.language FROM stations s WHERE s.stationuuid = favorites.stationuuid), language),
                        tags = COALESCE((SELECT s.tags FROM stations s WHERE s.stationuuid = favorites.stationuuid), tags),
                        codec = COALESCE((SELECT s.codec FROM stations s WHERE s.stationuuid = favorites.stationuuid), codec),
                        bitrate = COALESCE((SELECT s.bitrate FROM stations s WHERE s.stationuuid = favorites.stationuuid), bitrate),
                        votes = COALESCE((SELECT s.votes FROM stations s WHERE s.stationuuid = favorites.stationuuid), votes),
                        lastchecktime = COALESCE((SELECT s.lastchecktime FROM stations s WHERE s.stationuuid = favorites.stationuuid), lastchecktime)
                """.trimIndent())

                // Backfill pass 2: from `paged_station_cache` (Browse > paging),
                // for any row still empty after pass 1. paged_station_cache has
                // composite PK (queryType, queryValue, pageOffset, sortPosition),
                // so LIMIT 1 keeps the migration idempotent if a station was
                // paged into multiple queries.
                db.execSQL("""
                    UPDATE favorites SET
                        name = COALESCE(NULLIF(name, ''), (SELECT p.name FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), name),
                        url = COALESCE(NULLIF(url, ''), (SELECT p.url FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), url),
                        url_resolved = COALESCE(NULLIF(url_resolved, ''), (SELECT p.url_resolved FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), url_resolved),
                        favicon = COALESCE(NULLIF(favicon, ''), (SELECT p.favicon FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), favicon),
                        country = COALESCE(NULLIF(country, ''), (SELECT p.country FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), country),
                        countrycode = COALESCE(NULLIF(countrycode, ''), (SELECT p.countrycode FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), countrycode),
                        language = COALESCE(NULLIF(language, ''), (SELECT p.language FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), language),
                        tags = COALESCE(NULLIF(tags, ''), (SELECT p.tags FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), tags),
                        codec = COALESCE(NULLIF(codec, ''), (SELECT p.codec FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), codec),
                        bitrate = COALESCE(NULLIF(bitrate, 0), (SELECT p.bitrate FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), bitrate),
                        votes = COALESCE(NULLIF(votes, 0), (SELECT p.votes FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), votes),
                        lastchecktime = COALESCE(NULLIF(lastchecktime, 0), (SELECT p.lastchecktime FROM paged_station_cache p WHERE p.stationuuid = favorites.stationuuid LIMIT 1), lastchecktime)
                """.trimIndent())

                // Drop orphans. After both backfill passes, name still being ''
                // means no live copy of the station exists anywhere — the user
                // couldn't see this favorite under the old INNER JOIN, so
                // deleting it preserves the pre-migration UX rather than
                // resurrecting broken/empty rows.
                db.execSQL("DELETE FROM favorites WHERE name = ''")
            }
        }
    }
}
