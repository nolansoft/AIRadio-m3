# Design: Paging 3 RemoteMediator + Room Offline Cache for Browse Lists

**Date**: 2026-09-27
**Status**: Approved approach, awaiting spec review
**Owners**: nolan

## Context

`StationListScreen` (reached from Browse > Countries / Languages / Tags) currently uses a remote-only Paging 3 PagingSource (`StationPagingSource`, added in commit `c008a5d`). Each page is fetched live from `https://de1.api.radio-browser.info/json/stations/search`. When the user is offline:

- First load → error panel with a Retry button.
- Subsequent loads after a prior session → no offline data is rendered at all.

We want users who have previously scrolled through China (say) to be able to scroll back through those pages when they are on a plane / in a tunnel / without signal. Cached pages should also silently refresh in the background when the network returns, so the user eventually sees fresh data without taking an explicit action.

## Goals

1. **Offline read of previously-browsed pages.** A user who scrolled to page 5 of China last week should be able to scroll back to page 5 of China this week with no network.
2. **Background TTL refresh.** Pages older than a TTL should be re-fetched the next time the user enters that browse dimension, silently, without blocking the UI.
3. **Lazy caching.** Only pages the user actually scrolls to are stored. No eager prefetch of the first N pages or the full country listing.
4. **No conflict with the global stations sync.** `SyncWorker.clearAllStations()` must not wipe the paginated cache, and the cache must not be re-populated by the daily popular-stations sync.

## Non-goals

- Replacing the existing global `stations` table or its sync flow.
- Cache invalidation on station metadata change (e.g. station renamed, codec changed). Stations are referenced by `stationuuid` only; stale fields are accepted as a known limitation of offline caches.
- A user-facing manual "Refresh" button. Refresh is driven by TTL + network state, not by a swipe gesture.
- Pagination across more than ~20 pages of cache per dimension. Even popular countries top out at ~30 pages; we don't expect power users to scroll that deep.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│  StationListViewModel                                                     │
│  └─ val stations: Flow<PagingData<Station>>                              │
│     └─ GetStationsPagingUseCase(type, query, viewModelScope)            │
│        └─ Pager(                                                          │
│             config    = PagingConfig(pageSize=100, prefetch=100),        │
│             remoteMediator = StationRemoteMediator(type, query, ...),     │
│             pagingSourceFactory = { dao.pagingSource(type, query) }      │
│           )                                                               │
└──────────────────────────────────────────────────────────────────────────┘
        │                                  │
        │ on bind/refresh                   │ on REFRESH / APPEND
        ▼                                  ▼
┌─────────────────────────────┐  ┌─────────────────────────────────────┐
│ Room:                       │  │ StationRemoteMediator                │
│   paged_station_cache       │  │   - looks at state to compute offset │
│   (queried via Room         │  │   - calls api.searchStations(...)    │
│    PagingSource)            │  │   - writes rows into                 │
│                             │  │     paged_station_cache              │
└─────────────────────────────┘  └─────────────────────────────────────┘
```

The Paging 3 library coordinates: it asks the Room PagingSource for cached rows first, and concurrently asks the RemoteMediator to validate / extend / refresh. When both are satisfied, the user sees a coherent `PagingData<Station>` stream.

## Storage

### New Room table: `paged_station_cache`

A denormalized cache: every row carries the full `Station` payload plus the cache-metadata that ties it to a particular browse query. The full payload is stored (not just a FK to the existing `stations` table) so the cache survives `SyncWorker.clearAllStations()` and so the PagingSource doesn't need a JOIN.

```sql
CREATE TABLE paged_station_cache (
  query_type     TEXT    NOT NULL,   -- "country" | "language" | "tag"
  query_value    TEXT    NOT NULL,   -- "China"     | "english" | "rock"
  page_offset    INTEGER NOT NULL,   -- first-row offset this page was fetched at (0, 100, 200, ...)
  sort_position  INTEGER NOT NULL,   -- 0..99 within the page (matches API's order)
  stationuuid    TEXT    NOT NULL,   -- not a FK; station data is fully denormalized below
  name           TEXT    NOT NULL,
  url            TEXT    NOT NULL,
  url_resolved   TEXT    NOT NULL,
  favicon        TEXT    NOT NULL,
  country        TEXT    NOT NULL,
  countrycode    TEXT    NOT NULL,
  language       TEXT    NOT NULL,
  tags           TEXT    NOT NULL,
  codec          TEXT    NOT NULL,
  bitrate        INTEGER NOT NULL,
  votes          INTEGER NOT NULL,
  lastchecktime  INTEGER NOT NULL,   -- parsed Long, same as stations.lastchecktime
  cached_at      INTEGER NOT NULL,   -- System.currentTimeMillis() when this row was written
  PRIMARY KEY (query_type, query_value, page_offset, sort_position)
);
CREATE INDEX idx_paged_cache_query ON paged_station_cache(query_type, query_value);
```

The composite primary key gives us idempotent `INSERT OR REPLACE`: when the RemoteMediator re-fetches page 5 for China, the new rows blow away the old rows at the same `(query_type='country', query_value='China', page_offset=500)` keys.

### New DAO: `PagedStationCacheDao`

```kotlin
@Dao
interface PagedStationCacheDao {
    // Used as the Pager's pagingSourceFactory. The returned PagingSource is
    // invalidated automatically when paged_station_cache changes for this
    // (query_type, query_value) pair, so writes from StationRemoteMediator
    // show up immediately.
    @Query("""
        SELECT * FROM paged_station_cache
        WHERE query_type = :queryType AND query_value = :queryValue
        ORDER BY page_offset ASC, sort_position ASC
    """)
    fun pagingSource(queryType: String, queryValue: String): PagingSource<Int, PagedStationCacheEntity>

    // Bulk insert for a fetched page. INSERT OR REPLACE handles overwriting
    // existing rows at the same (type, query, page_offset, sort_position)
    // composite key, so refreshes are idempotent.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(rows: List<PagedStationCacheEntity>)

    // Drop a single page; used by REFRESH-load to make sure no stale rows
    // for the same offset survive when the page shrinks (e.g. API now
    // returns 87 rows instead of 100).
    @Query("""
        DELETE FROM paged_station_cache
        WHERE query_type = :queryType
          AND query_value = :queryValue
          AND page_offset = :pageOffset
    """)
    suspend fun deletePage(queryType: String, queryValue: String, pageOffset: Int)

    // Background housekeeping. Called from a WorkManager periodic worker
    // (or eagerly by RemoteMediator.initialize) to drop rows that haven't
    // been touched in N days. Bounded cache size.
    @Query("DELETE FROM paged_station_cache WHERE cached_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
```

### Database version bump: 1 → 2

`AppDatabase` moves to `version = 2` and registers a single migration:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS paged_station_cache (
              query_type TEXT NOT NULL,
              query_value TEXT NOT NULL,
              page_offset INTEGER NOT NULL,
              sort_position INTEGER NOT NULL,
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
              cached_at INTEGER NOT NULL,
              PRIMARY KEY (query_type, query_value, page_offset, sort_position)
            )
        """)
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_paged_cache_query
            ON paged_station_cache (query_type, query_value)
        """)
    }
}
```

This is purely additive — no `DROP`, no `ALTER`, no rewriting of existing rows. Existing users get the cache table on first launch after the upgrade; their `stations` table is untouched.

## Paging 3 wiring

### Dependencies

Add to `app/build.gradle.kts`:

```kotlin
implementation("androidx.paging:paging-room:3.2.1")
```

`paging-room` provides `Room`'s ability to return a `PagingSource<Int, T>` directly from a `@Query`-annotated DAO function. (`paging-runtime-ktx` is already in the build from commit `c008a5d`.)

### Updated `GetStationsPagingUseCase`

Replaces the existing remote-only Pager with a `Pager` that has both a `remoteMediator` and a Room-backed `pagingSourceFactory`.

```kotlin
class GetStationsPagingUseCase @Inject constructor(
    private val radioRepository: RadioRepository,
    private val database: AppDatabase,         // new dep
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    operator fun invoke(
        type: String,
        query: String,
        scope: CoroutineScope,
    ): Flow<PagingData<Station>> {
        val pagingSourceFactory: () -> PagingSource<Int, PagedStationCacheEntity> = {
            database.pagedStationCacheDao().pagingSource(type, query)
        }
        val remoteMediator = StationRemoteMediator(
            type = type,
            query = query,
            api = radioRepository.radioBrowserApi,  // exposed accessor (see below)
            dao = database.pagedStationCacheDao(),
            pageSize = PAGE_SIZE,
            ttlMillis = TTL_MILLIS,
        )
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                enablePlaceholders = false,
            ),
            remoteMediator = remoteMediator,
            pagingSourceFactory = pagingSourceFactory,
        )
            .flow
            .map { pagingData ->
                pagingData.map { entity -> entity.toStationDomain() }
            }
            .cachedIn(scope)
    }

    companion object {
        const val PAGE_SIZE = 100
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000   // 7 days
    }
}
```

`Station` is the domain model the screen already consumes; `PagedStationCacheEntity.toStationDomain()` is a new mapper that mirrors the existing `StationEntity.toDomain()`.

### Repository accessor

`RadioRepository` already holds the `RadioBrowserApi` field privately. We need to expose it (or expose a `searchStationsPage`-shaped method) so `StationRemoteMediator` can call it without re-injecting Retrofit. Cleanest option: add a `RadioRepository.fetchStationsPage(...)` method that the RemoteMediator calls (delegating to the API). We already have that method from `c008a5d`. The RemoteMediator takes `RadioRepository` (not the API) as a dependency, which keeps the RemoteMediator testable via a fake repository.

### RemoteMediator

```kotlin
@OptIn(ExperimentalPagingApi::class)
class StationRemoteMediator @Inject constructor(
    private val type: String,
    private val query: String,
    private val repository: RadioRepository,
    private val dao: PagedStationCacheDao,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pageSize: Int = GetStationsPagingUseCase.PAGE_SIZE,
    private val ttlMillis: Long = GetStationsPagingUseCase.TTL_MILLIS,
) : RemoteMediator<Int, PagedStationCacheEntity>() {

    override suspend fun initialize(): InitializeAction = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PagedStationCacheEntity>,
    ): MediatorResult {
        val offset: Int = when (loadType) {
            LoadType.REFRESH  -> 0
            LoadType.PREPEND  -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND   -> {
                val last = state.lastItemOrNull()
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                // Use the page_offset of the last cached row to derive the
                // next-page offset; sort_position within the page confirms
                // the count.
                last.pageOffset + last.sortPosition + 1
            }
        }

        // TTL guard: if the most recent row at this offset is fresher than
        // ttlMillis, skip the network call and report success. This is what
        // makes a second visit to the same country "free" until the cache
        // ages out.
        if (loadType == LoadType.REFRESH) {
            val newestCachedAt = dao.newestCachedAt(type, query, offset)
            if (newestCachedAt != null && now() - newestCachedAt < ttlMillis) {
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }

        return try {
            val stations = repository.fetchStationsPage(
                country = type.takeIf { it == "country" }?.let { query },
                language = type.takeIf { it == "language" }?.let { query },
                tag = type.takeIf { it == "tag" }?.let { query },
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
                    cached_at = nowMs,
                )
            }

            if (loadType == LoadType.APPEND) {
                // APPEND can never shrink an existing page; only insert.
                dao.upsertPage(entities)
            } else {
                // REFRESH: clear the page first so a now-shorter page (87
                // rows instead of 100) doesn't leave dangling rows.
                dao.deletePage(type, query, offset)
                if (entities.isNotEmpty()) dao.upsertPage(entities)
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
```

`StationRemoteMediator` is `@Inject`-able per `(type, query)` pair, but `@AssistedInject` would be cleaner here. Since Hilt already has us wired up via `GetStationsPagingUseCase`, the use case constructs the mediator with the captured `type` / `query`. That keeps the Mediator a plain class without needing Hilt's assisted-injection plumbing.

A small DAO addition is needed for the TTL check:

```kotlin
@Query("""
    SELECT MAX(cached_at) FROM paged_station_cache
    WHERE query_type = :queryType
      AND query_value = :queryValue
      AND page_offset = :pageOffset
""")
suspend fun newestCachedAt(queryType: String, queryValue: String, pageOffset: Int): Long?
```

### StationListScreen

No changes. The screen already consumes `Flow<PagingData<Station>>` and renders skeleton / error / list / append-error exactly as today; the difference is that on a cold offline launch of a previously-browsed country the user sees real rows instead of the error panel.

## Lifecycle and threading

- `pagingSourceFactory` is invoked once by Paging 3 (on first collect) and the resulting `PagingSource` is invalidated automatically by Room's `InvalidationTracker` whenever `paged_station_cache` changes. The same Room instance is used by the screen and by the RemoteMediator (via `database.pagedStationCacheDao()`), so writes from one are visible to the other.
- `StationRemoteMediator.load` is called by Paging 3 from a coroutine on the Pager's `fetchDispatcher`. We don't need to switch to `Dispatchers.IO` ourselves because Retrofit suspend functions already hop there internally.
- The cache `DELETE` calls in `deletePage` and `upsertPage` are `suspend`, so Room schedules them on its own background executor.

## Refresh / staleness rules

| Scenario | Behavior |
| --- | --- |
| First time the user opens a country | RemoteMediator fires REFRESH, hits network, writes page 0; user sees real data once the response lands. |
| User opens the same country again within 7 days, online | TTL check passes (cached_at is fresh), RemoteMediator returns `Success(endOfPaginationReached=false)` immediately, no network. |
| User opens the same country again after 7 days, online | TTL check fails, REFRESH re-fetches page 0; further APPEND loads run as the user scrolls. |
| User opens the country offline (any age) | Room PagingSource serves whatever rows are cached; RemoteMediator's network call fails with `IOException` and is reported as a non-fatal append-load error (refresh error is suppressed because the cache itself is good). |
| User scrolls past the end of cached rows while online | RemoteMediator handles APPEND, fetches the next 100 rows, writes them; user sees new rows. |
| User scrolls past the end of cached rows while offline | RemoteMediator fails the APPEND with `MediatorResult.Error`; screen renders the "Retry loading more" footer; user can wait for connectivity and retry. |
| Daily `SyncWorker.clearAllStations()` runs | Only the global `stations` table is cleared; `paged_station_cache` is untouched. |

The "refresh error suppressed when cache has data" is the trickiest part. `RemoteMediator.load` returns `MediatorResult.Error` on network failure during REFRESH; Paging 3 surfaces that as `loadState.refresh is LoadState.Error`. The screen's existing logic shows the error panel only when `itemCount == 0`. With cached data present, `itemCount > 0`, so the error is silently absorbed and the user keeps seeing cached rows. The append-error footer for subsequent failed loads still shows. This matches the user-visible behavior we want: cached rows are visible, network errors are background.

## SyncWorker interaction

`SyncWorker.runSync` does:
1. `stationDao.clearAllStations()` — wipes the global `stations` table (does NOT touch `paged_station_cache`).
2. `stationDao.insertStations(...)` — repopulates with the top 1500 most-voted stations worldwide.

We add no logic here. The paged cache is in a separate table and is independent.

## Files to add / modify

### New

- `app/src/main/java/com/nolansoftware/airadio/data/database/entity/PagedStationCacheEntity.kt`
- `app/src/main/java/com/nolansoftware/airadio/data/repository/paging/StationRemoteMediator.kt`

### Modified

- `app/build.gradle.kts` — add `androidx.paging:paging-room:3.2.1`
- `app/src/main/java/com/nolansoftware/airadio/data/database/dao/Daos.kt` — add `PagedStationCacheDao` interface and `newestCachedAt` query
- `app/src/main/java/com/nolansoftware/airadio/data/database/AppDatabase.kt` — register `PagedStationCacheEntity::class` in `entities`, bump `version = 2`, add `abstract fun pagedStationCacheDao(): PagedStationCacheDao`, register `MIGRATION_1_2` in the Room builder
- `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt` — add `@Provides fun providePagedStationCacheDao(database: AppDatabase): PagedStationCacheDao = database.pagedStationCacheDao()` (mirrors the existing DAO providers; `provideAppDatabase` already exists)
- `app/src/main/java/com/nolansoftware/airadio/domain/usecase/UseCases.kt` — `GetStationsPagingUseCase` constructor changes (takes `AppDatabase`), body switches to Pager + RemoteMediator, map to `Station`
- `app/src/main/java/com/nolansoftware/airadio/data/repository/mapper/Mappers.kt` — add `PagedStationCacheEntity.toStationDomain()` (mirrors existing `StationEntity.toDomain()`)
- `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt` — Hilt provides `AppDatabase` (already provided? verify) and `PagedStationCacheDao`. May need a `pagingSourceFactory` provider if Hilt's graph needs explicit binding.

### Untouched

- `StationListViewModel.kt` — `val stations: Flow<PagingData<Station>>` signature unchanged.
- `StationListScreen.kt` — UI logic unchanged; the loadState handling added in `c008a5d` already does the right thing for cached-but-offline.
- `RadioBrowserApi.kt`, `RadioRepository.kt` (the `fetchStationsPage` method from `c008a5d` is reused as-is).

## Risks and trade-offs

| Risk | Mitigation |
| --- | --- |
| Cache grows unbounded over months of use | Add a periodic `WorkManager` job (separate ticket) that runs `dao.deleteOlderThan(now - 30 days)`. Out of scope for this change; tracked as follow-up. |
| Migration fails on real devices with v1 databases | Migration is purely additive (CREATE TABLE, CREATE INDEX). Room's `MigrationTestHelper` can be used to verify offline. |
| Hilt graph: `AppDatabase` provider not currently set up | Verified at `AppModule.kt:67-69`: `provideAppDatabase` already exists. We only need to add `providePagedStationCacheDao` alongside the existing DAO providers. |
| Paging 3's `Refresh` error overlays cached data with empty state when an inline refresh fails | Acceptable: if `itemCount > 0`, the screen shows cached rows; the error panel only renders when `itemCount == 0`. |
| Stations duplicate in `paged_station_cache` and `stations` table | Intentional and contained. The two tables serve different consumers. Disk cost: ~1 KB per cached station; even 5000 cached stations is ~5 MB. |
| Multiple devices with same DB version (not relevant here — Android is single-device per install) | N/A |

## Verification

### Build

```bash
./gradlew assembleDebug
```

Expect: success. Warnings only for pre-existing items (`ArrowBack` deprecation, `runSync` unused variables).

### Manual device testing

1. **Fresh offline read of cached pages.**
   1. Online, open `Browse > Countries > China`. Scroll down to ~500 rows.
   2. Turn on airplane mode.
   3. Force-stop and reopen the app.
   4. Open `Browse > Countries > China` again.
   5. Expected: real rows visible from index 0, able to scroll back through the rows that were loaded in step 1.1.
   6. Scrolling past the cached rows should show the "Retry loading more" footer (network is down).

2. **TTL refresh.**
   1. Repeat 1.1–1.4 but stay online.
   2. Expected: rows appear immediately (from cache); no network spinner; the RemoteMediator's REFRESH returns `Success` without a call because TTL hasn't expired.

3. **TTL expiry.**
   1. Manually edit `cached_at` on one page's rows via `adb shell sqlite3` (or temporarily set TTL to 1 second in code) so the page is "stale".
   2. Online, re-open the country.
   3. Expected: rows appear from cache; RemoteMediator silently re-fetches the stale page in the background; a logcat tag confirms the network call landed.

4. **Refresh error suppression.**
   1. Repeat 1.1 to populate cache.
   2. Disable the network (airplane mode) and disable any retry.
   3. Force-stop, reopen, go to the same country.
   4. Expected: cached rows render. No error panel. Bottom shows nothing more.

5. **Daily sync coexistence.**
   1. Populate China cache.
   2. Force a sync (kill + relaunch app, or `adb shell cmd jobscheduler run -f com.nolansoftware.airadio 999` for the daily periodic job).
   3. Open China offline.
   4. Expected: cached rows still visible — `clearAllStations()` did not touch `paged_station_cache`.

6. **Migration.**
   1. Build & install the previous version (commit `c008a5d`), open the app once to ensure the v1 schema is created.
   2. Build & install the new version on top.
   3. Open `Browse > Countries > China`.
   4. Expected: no crash, cache table created, fresh remote loads populate it.

### What we are explicitly NOT testing in this change

- Cache-size-bounded cleanup (deferred to a follow-up ticket).
- Swipe-to-refresh UI (intentionally not added per non-goals).
- Cross-dimension deduplication (intentional, see risks).
