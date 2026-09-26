package com.nolansoftware.airadio.data.repository

import com.nolansoftware.airadio.data.api.RadioBrowserApi
import com.nolansoftware.airadio.data.database.dao.CountryDao
import com.nolansoftware.airadio.data.database.dao.FavoritesDao
import com.nolansoftware.airadio.data.database.dao.LanguageDao
import com.nolansoftware.airadio.data.database.dao.RecentlyPlayedDao
import com.nolansoftware.airadio.data.database.dao.StationDao
import com.nolansoftware.airadio.data.database.dao.TagDao
import com.nolansoftware.airadio.data.database.entity.FavoriteEntity
import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import com.nolansoftware.airadio.data.repository.mapper.toCountryDomain
import com.nolansoftware.airadio.data.repository.mapper.toCountryEntities
import com.nolansoftware.airadio.data.repository.mapper.toDomain
import com.nolansoftware.airadio.data.repository.mapper.toLanguageDomain
import com.nolansoftware.airadio.data.repository.mapper.toLanguageEntities
import com.nolansoftware.airadio.data.repository.mapper.toStationDomain
import com.nolansoftware.airadio.data.repository.mapper.toStationEntities
import com.nolansoftware.airadio.data.repository.mapper.toTagDomain
import com.nolansoftware.airadio.data.repository.mapper.toTagEntities
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class RadioRepository @Inject constructor(
    private val radioBrowserApi: RadioBrowserApi,
    private val stationDao: StationDao,
    private val countryDao: CountryDao,
    private val languageDao: LanguageDao,
    private val tagDao: TagDao,
    private val favoritesDao: FavoritesDao,
    private val recentlyPlayedDao: RecentlyPlayedDao
) {

    fun getPopularStations(): Flow<List<Station>> =
        stationDao.getPopularStations().map { it.toStationDomain() }

    fun getStationsByCountry(country: String): Flow<List<Station>> =
        stationDao.getStationsByCountry(country).map { it.toStationDomain() }

    fun getStationsByLanguage(language: String): Flow<List<Station>> =
        stationDao.getStationsByLanguage(language).map { it.toStationDomain() }

    fun getStationsByTag(tag: String): Flow<List<Station>> =
        stationDao.getStationsByTag(tag).map { it.toStationDomain() }

    fun getRecentlyPlayedStations(): Flow<List<Station>> =
        stationDao.getRecentlyPlayedStations().map { it.toStationDomain() }

    fun getFavoriteStations(): Flow<List<Station>> =
        stationDao.getFavoriteStations().map { it.toStationDomain() }

    fun getAllCountries(): Flow<List<Country>> =
        countryDao.getAllCountries().map { it.toCountryDomain() }

    fun getAllLanguages(): Flow<List<Language>> =
        languageDao.getAllLanguages().map { it.toLanguageDomain() }

    fun getPopularTags(): Flow<List<Tag>> =
        tagDao.getPopularTags().map { it.toTagDomain() }

    fun searchStations(query: String): Flow<List<Station>> =
        stationDao.searchStations(query).map { it.toStationDomain() }

    fun isFavorite(stationId: String): Flow<Boolean> =
        favoritesDao.isFavorite(stationId)

    suspend fun getStationById(stationId: String): Station? =
        stationDao.getStationById(stationId)?.toDomain()

    suspend fun syncAllData() {
        val stations = radioBrowserApi.getStations()
        val countries = radioBrowserApi.getCountries()
        val languages = radioBrowserApi.getLanguages()
        val tags = radioBrowserApi.getTags()

        stationDao.clearAllStations()
        stationDao.insertStations(stations.toStationEntities())

        countryDao.clearAllCountries()
        countryDao.insertCountries(countries.toCountryEntities())

        languageDao.clearAllLanguages()
        languageDao.insertLanguages(languages.toLanguageEntities())

        tagDao.clearAllTags()
        tagDao.insertTags(tags.toTagEntities())
    }

    suspend fun addToFavorites(stationId: String) {
        favoritesDao.addToFavorites(
            FavoriteEntity(
                stationuuid = stationId,
                added_time = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeFromFavorites(stationId: String) {
        favoritesDao.removeFromFavorites(stationId)
    }

    suspend fun addToRecentlyPlayed(stationId: String) {
        recentlyPlayedDao.addToRecentlyPlayed(
            RecentlyPlayedEntity(
                stationuuid = stationId,
                played_time = System.currentTimeMillis()
            )
        )

        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        recentlyPlayedDao.cleanupOldRecentlyPlayed(cutoffTime)
    }
}