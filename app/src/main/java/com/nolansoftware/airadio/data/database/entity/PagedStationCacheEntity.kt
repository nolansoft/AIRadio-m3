package com.nolansoftware.airadio.data.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Denormalized cache for paginated browse lists (country / language / tag).
 * Every row carries the full Station payload so the PagingSource doesn't need
 * a JOIN with the global `stations` table — and so the cache survives
 * SyncWorker.clearAllStations().
 *
 * Composite primary key (queryType, queryValue, pageOffset, sortPosition)
 * makes INSERT OR REPLACE idempotent: re-fetching page 5 of China overwrites
 * the same composite-key rows in place.
 */
@Entity(
    tableName = "paged_station_cache",
    primaryKeys = ["queryType", "queryValue", "pageOffset", "sortPosition"],
    indices = [Index(value = ["queryType", "queryValue"], name = "idx_paged_cache_query")]
)
data class PagedStationCacheEntity(
    // Cache metadata
    val queryType: String,
    val queryValue: String,
    val pageOffset: Int,
    val sortPosition: Int,
    val cachedAt: Long,

    // Denormalized Station payload (mirrors StationEntity)
    val stationuuid: String,
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
    val lastchecktime: Long,
) {
    companion object {
        const val TYPE_COUNTRY = "country"
        const val TYPE_LANGUAGE = "language"
        const val TYPE_TAG = "tag"
    }
}
