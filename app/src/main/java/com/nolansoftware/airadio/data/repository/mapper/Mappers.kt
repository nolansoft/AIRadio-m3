// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.repository.mapper

import com.nolansoftware.airadio.data.api.model.ApiCountry
import com.nolansoftware.airadio.data.api.model.ApiLanguage
import com.nolansoftware.airadio.data.api.model.ApiStation
import com.nolansoftware.airadio.data.api.model.ApiTag
import com.nolansoftware.airadio.data.database.entity.CountryEntity
import com.nolansoftware.airadio.data.database.entity.LanguageEntity
import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity
import com.nolansoftware.airadio.data.database.entity.StationEntity
import com.nolansoftware.airadio.data.database.entity.TagEntity
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.Tag
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val radioBrowserDateFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial {
        // The Radio Browser API returns timestamps in its local server timezone
        // formatted as "yyyy-MM-dd HH:mm:ss". Parse with the same pattern; if it
        // ever fails, fall back to 0L rather than crashing the whole sync.
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

private fun parseLastCheckTime(raw: String): Long =
    try {
        radioBrowserDateFormat.get()!!.parse(raw)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

fun ApiStation.toEntity(): StationEntity = StationEntity(
    stationuuid = stationuuid,
    name = name,
    url = url,
    url_resolved = url_resolved,
    favicon = favicon,
    country = country,
    countrycode = countrycode,
    language = language,
    tags = tags,
    codec = codec,
    bitrate = bitrate,
    votes = votes,
    lastchecktime = parseLastCheckTime(lastchecktime)
)

/**
 * API DTO → domain model in one step. Used by the paging path
 * ([com.nolansoftware.airadio.data.repository.RadioRepository.fetchStationsPage])
 * which never persists to Room, so going via StationEntity would be wasteful.
 * Field mapping mirrors `StationEntity.toDomain()`; the only difference is the
 * `lastchecktime` String→Long parse that `toEntity()` performs.
 */
fun ApiStation.toDomain(): Station = Station(
    stationuuid = stationuuid,
    name = name,
    url = url,
    urlResolved = url_resolved,
    favicon = favicon,
    country = country,
    countryCode = countrycode,
    language = language,
    tags = tags,
    codec = codec,
    bitrate = bitrate,
    votes = votes,
    lastCheckTime = parseLastCheckTime(lastchecktime)
)

fun List<ApiStation>.toDomainStations(): List<Station> = map { it.toDomain() }

fun ApiCountry.toEntity(): CountryEntity = CountryEntity(
    name = name,
    stationcount = stationcount
)

fun ApiLanguage.toEntity(): LanguageEntity = LanguageEntity(
    name = name,
    stationcount = stationcount
)

fun ApiTag.toEntity(): TagEntity = TagEntity(
    name = name,
    stationcount = stationcount
)

fun StationEntity.toDomain(): Station = Station(
    stationuuid = stationuuid,
    name = name,
    url = url,
    urlResolved = url_resolved,
    favicon = favicon,
    country = country,
    countryCode = countrycode,
    language = language,
    tags = tags,
    codec = codec,
    bitrate = bitrate,
    votes = votes,
    lastCheckTime = lastchecktime
)

fun CountryEntity.toDomain(): Country = Country(
    name = name,
    stationCount = stationcount
)

fun LanguageEntity.toDomain(): Language = Language(
    name = name,
    stationCount = stationcount
)

fun TagEntity.toDomain(): Tag = Tag(
    name = name,
    stationCount = stationcount
)

fun List<ApiStation>.toStationEntities(): List<StationEntity> = map { it.toEntity() }
fun List<ApiCountry>.toCountryEntities(): List<CountryEntity> = map { it.toEntity() }
fun List<ApiLanguage>.toLanguageEntities(): List<LanguageEntity> = map { it.toEntity() }
fun List<ApiTag>.toTagEntities(): List<TagEntity> = map { it.toEntity() }

fun List<StationEntity>.toStationDomain(): List<Station> = map { it.toDomain() }
fun List<CountryEntity>.toCountryDomain(): List<Country> = map { it.toDomain() }
fun List<LanguageEntity>.toLanguageDomain(): List<Language> = map { it.toDomain() }
fun List<TagEntity>.toTagDomain(): List<Tag> = map { it.toDomain() }

/**
 * Cache entity -> domain. Field-for-field identical to
 * [StationEntity.toDomain] (the global-stations variant above). The
 * substantive difference from [ApiStation.toDomain] (the API-DTO
 * variant further up) is that [lastchecktime] is already a Long on
 * the cache entity — no String->Long parse needed — because the
 * RemoteMediator parsed it when writing the row in the first place.
 */
fun PagedStationCacheEntity.toStationDomain(): Station = Station(
    stationuuid = stationuuid,
    name = name,
    url = url,
    urlResolved = url_resolved,
    favicon = favicon,
    country = country,
    countryCode = countrycode,
    language = language,
    tags = tags,
    codec = codec,
    bitrate = bitrate,
    votes = votes,
    lastCheckTime = lastchecktime,
)

