// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val stationuuid: String,
    val name: String,
    val url: String,
    val url_resolved: String,
    val favicon: String,
    val country: String,
    val countrycode: String,
    val language: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
    val votes: Int,
    val lastchecktime: Long
)

@Entity(tableName = "countries")
data class CountryEntity(
    @PrimaryKey val name: String,
    val stationcount: Int
)

@Entity(tableName = "languages")
data class LanguageEntity(
    @PrimaryKey val name: String,
    val stationcount: Int
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
    val stationcount: Int
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val stationuuid: String,
    val added_time: Long,
    // Denormalized station snapshot — captured at toggle-favorite time so the
    // FavoritesScreen list survives `StationDao.clearAllStations()` (which
    // runSync runs on every daily sync to refill the popular top-N). Without
    // these fields, a favorited station that drops out of the popular top
    // between syncs becomes an orphan row in `favorites` — `isFavorite()` still
    // returns true on it but `getFavoriteStations()` (the old INNER JOIN
    // against `stations`) silently drops it, so the heart icon shows but the
    // station is missing from FavoritesScreen.
    //
    // Storing the full snapshot makes favorites self-contained: the favorites
    // table no longer depends on whether the corresponding row in `stations`
    // survives the next sync. New toggles write the snapshot at toggle time;
    // MIGRATION_2_3 backfills these columns from `stations` / `paged_station_cache`
    // for existing favorites and drops any that have no live copy anywhere.
    val name: String,
    val url: String,
    val url_resolved: String,
    val favicon: String,
    val country: String,
    val countrycode: String,
    val language: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
    val votes: Int,
    val lastchecktime: Long
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val stationuuid: String,
    val played_time: Long
)