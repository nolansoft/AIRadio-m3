package com.nolansoftware.airadio.data.repository.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.nolansoftware.airadio.domain.model.Station

/**
 * Paging 3 source for the country/language/tag station list.
 *
 * Pages are fetched on-demand from the Radio Browser API via the
 * injected [fetch] lambda. The source is intentionally remote-only:
 * we don't cache paginated pages into Room here, so a previously
 * browsed country is not available offline. (Initial popular stations
 * are still served from Room by `getPopularStations`.) Switching to a
 * `RemoteMediator`-backed Pager to add offline paging is a separate
 * ticket.
 *
 * Page keys are integer offsets into the filtered result set. On a
 * successful load the next key is `currentOffset + items.size`; when
 * the API returns fewer items than requested (or zero) we declare the
 * stream ended and the Pager stops triggering loads.
 */
class StationPagingSource(
    private val fetch: suspend (offset: Int, limit: Int) -> List<Station>
) : PagingSource<Int, Station>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Station> {
        val offset = params.key ?: 0
        return try {
            val items = fetch(offset, params.loadSize)
            LoadResult.Page(
                data = items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (items.size < params.loadSize) null else offset + items.size
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * Returning null means a refresh (e.g. swipe-to-refresh) restarts
     * from offset 0. Preserving the scroll position across a refresh
     * can be added later by computing the anchor offset from
     * `state.anchorPosition`.
     */
    override fun getRefreshKey(state: PagingState<Int, Station>): Int? = null
}
