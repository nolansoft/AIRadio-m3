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
    val added_time: Long
)

@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val stationuuid: String,
    val played_time: Long
)