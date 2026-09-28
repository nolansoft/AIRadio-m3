// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.repository.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity
import com.nolansoftware.airadio.data.repository.RadioRepository
import com.nolansoftware.airadio.domain.usecase.GetStationsPagingUseCase
import retrofit2.HttpException
import java.io.IOException

/**
 * Coordinates the network -> Room write for the country / language / tag
 * browse list. Paging 3 invokes `load()` from the Pager's fetch coroutine;
 * we read or write the cache, then return MediatorResult.Success or Error.
 *
 * TTL behavior: only REFRESH-load consults the cache age. APPEND-load always
 * fetches (the user is scrolling forward, there's nothing to consult).
 * PREPEND-load short-circuits because browse lists never paginate backward.
 *
 * REFRESH behavior: deletes the page before inserting so a now-shorter page
 * (e.g. API returns 87 rows this time, 100 last time) does not leave
 * dangling rows. APPEND-load does NOT delete first — a forward scroll can
 * never shrink a page; either the API returns exactly pageSize and we have
 * more pages to load, or it returns fewer and we declare endOfPagination.
 */
@OptIn(ExperimentalPagingApi::class)
class StationRemoteMediator(
    private val type: String,
    private val query: String,
    private val repository: RadioRepository,
    private val dao: PagedStationCacheDao,
    private val pageSize: Int = GetStationsPagingUseCase.PAGE_SIZE,
    private val ttlMillis: Long = GetStationsPagingUseCase.TTL_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) : RemoteMediator<Int, PagedStationCacheEntity>() {

    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PagedStationCacheEntity>,
    ): MediatorResult {
        // PREPEND: browse lists only paginate forward. Paging 3 will stop
        // trying to prepend once we report endOfPaginationReached.
        if (loadType == LoadType.PREPEND) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }

        val offset: Int = when (loadType) {
            LoadType.REFRESH -> 0
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> {
                val last = state.lastItemOrNull()
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                last.pageOffset + last.sortPosition + 1
            }
        }

        // TTL guard for REFRESH: if the page is fresh, skip the network.
        // (Review Focus line 1 — must short-circuit cleanly without writing.)
        if (loadType == LoadType.REFRESH) {
            val newest = dao.newestCachedAt(type, query, offset)
            if (newest != null && now() - newest < ttlMillis) {
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }

        return try {
            val stations = repository.fetchStationsPage(
                country = type.takeIf { it == PagedStationCacheEntity.TYPE_COUNTRY }?.let { query },
                language = type.takeIf { it == PagedStationCacheEntity.TYPE_LANGUAGE }?.let { query },
                tag = type.takeIf { it == PagedStationCacheEntity.TYPE_TAG }?.let { query },
                offset = offset,
                limit = pageSize,
            )
            val nowMs = now()
            val entities = stations.mapIndexed { i, station ->
                PagedStationCacheEntity(
                    queryType = type,
                    queryValue = query,
                    pageOffset = offset,
                    sortPosition = i,
                    cachedAt = nowMs,
                    stationuuid = station.stationuuid,
                    name = station.name,
                    url = station.url,
                    url_resolved = station.urlResolved,
                    favicon = station.favicon,
                    country = station.country,
                    countrycode = station.countryCode,
                    language = station.language,
                    tags = station.tags,
                    codec = station.codec,
                    bitrate = station.bitrate,
                    votes = station.votes,
                    lastchecktime = station.lastCheckTime,
                )
            }

            // Review Focus lines 2 — REFRESH clears the page first, APPEND inserts only.
            if (loadType == LoadType.REFRESH) {
                dao.deletePage(type, query, offset)
            }
            if (entities.isNotEmpty()) {
                dao.upsertPage(entities)
            }

            MediatorResult.Success(
                endOfPaginationReached = stations.size < pageSize,
            )
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }
}
