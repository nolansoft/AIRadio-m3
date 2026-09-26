package com.nolansoftware.airadio.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nolansoftware.airadio.data.database.dao.CountryDao
import com.nolansoftware.airadio.data.database.dao.FavoritesDao
import com.nolansoftware.airadio.data.database.dao.LanguageDao
import com.nolansoftware.airadio.data.database.dao.RecentlyPlayedDao
import com.nolansoftware.airadio.data.database.dao.StationDao
import com.nolansoftware.airadio.data.database.dao.TagDao
import com.nolansoftware.airadio.data.database.entity.CountryEntity
import com.nolansoftware.airadio.data.database.entity.FavoriteEntity
import com.nolansoftware.airadio.data.database.entity.LanguageEntity
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
        RecentlyPlayedEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao
    abstract fun countryDao(): CountryDao
    abstract fun languageDao(): LanguageDao
    abstract fun tagDao(): TagDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao

    companion object {
        const val DATABASE_NAME = "airadio_database"
    }
}