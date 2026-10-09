# Paging 3 RemoteMediator + Room Offline Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `StationListScreen` (Browse > Countries/Languages/Tags) work offline for pages the user has previously scrolled to, with TTL-based silent refresh in the background when the network returns.

**Architecture:** Introduce a separate denormalized Room table `paged_station_cache` that stores the full `Station` payload plus `(query_type, query_value, page_offset, sort_position)` metadata. Wire `GetStationsPagingUseCase` to a Pager backed by a Room `PagingSource` (cache reads) plus a `StationRemoteMediator` (network fills). Paging 3 coordinates the two; the RemoteMediator enforces the 7-day TTL on REFRESH and silently re-fetches stale pages. `SyncWorker.clearAllStations()` does not touch the cache.

**Tech Stack:** AndroidX Room 2.6.1 (entity, DAO, migration), AndroidX Paging 3.2.1 (`paging-runtime-ktx` already in build; add `paging-room`), Kotlin coroutines, Hilt for DI. Kotlin 1.9.20 / AGP 8.2.0 / JDK 17 (existing project pins, do not change).

**Spec:** `docs/superpowers/specs/2026-09-27-paging-remote-mediator-offline-cache-design.md` (commit `b75603c`)

## Global Constraints

These apply to every task. Copy-paste from spec; do not relax.

- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`, JDK 17, Kotlin 1.9.20, AGP 8.2.0, Compose Compiler 1.5.5 (per `app/build.gradle.kts`).
- Room version: `2.6.1` (existing). Paging version: `3.2.1` (existing `paging-runtime-ktx`; new dep is `paging-room`).
- `Station` domain model: `@Parcelize` — keep its annotations, do not strip.
- `SyncWorker.clearAllStations()` runs daily; the new cache MUST survive it (separate table, no schema overlap).
- The existing `RadioRepository.fetchStationsPage(country, language, tag, offset, limit): List<Station>` (added in `c008a5d`) is reused as-is by the RemoteMediator — do not re-implement or move it.
- StationListViewModel / StationListScreen are NOT modified (their `Flow<PagingData<Station>>` interface is preserved).
- `kapt { correctErrorTypes = true }` is already set — leave it.
- Project has no automated test source set (per `CLAUDE.md`); verification per task is `assembleDebug` + manual app launch. Each task's "Expected outcome" is the deliverable the next task depends on.

## Review Focus

These are the failure modes the spec implies but no task's manual check pins. The owner of each line is the task that owns the relevant code; that task's "Expected outcome" must mention it.

1. **REFRESH TTL short-circuit returns `Success` without writing rows** — owner: Task 3 (`StationRemoteMediator.load` REFRESH branch). A bug here either (a) hits the network on every REFRESH (cache useless) or (b) wrongly returns `Error` (cache invisible until next launch).
2. **APPEND preserves existing rows for the same offset** — owner: Task 3 (`StationRemoteMediator.load` APPEND branch). Must NOT call `deletePage` on APPEND, or stale rows from a shrunken page will fill in. Spec table line "APPEND inserts only".
3. **Refresh error absorbed when cache has rows** — owner: Task 4 (UseCase integration). Paging 3 surfaces `MediatorResult.Error` as `loadState.refresh is LoadState.Error`; the screen's existing `if (refresh is Error && itemCount == 0)` rule renders the error panel only when there are no rows. If this branching changes, the user sees the error panel over cached data.
4. **PREPEND returns `endOfPaginationReached = true` unconditionally** — owner: Task 3. Forward-paginating backwards makes no sense; must short-circuit to avoid a wasted API call.
5. **Migration is `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`** — owner: Task 1. Existing v1 databases must upgrade cleanly. If the migration uses `CREATE TABLE` without `IF NOT EXISTS`, the second install on the same device crashes.

---

## File Structure

| File | Status | Responsibility |
| --- | --- | --- |
| `data/database/entity/PagedStationCacheEntity.kt` | New | Room `@Entity` matching the schema in spec §Storage |
| `data/database/dao/Daos.kt` | Modify (add `PagedStationCacheDao`) | Cache DAO: `pagingSource`, `upsertPage`, `deletePage`, `deleteOlderThan`, `newestCachedAt` |
| `data/database/AppDatabase.kt` | Modify (entities[], version, abstract DAO fn) | Register entity, bump version 1→2, expose DAO |
| `di/AppModule.kt` | Modify (add `providePagedStationCacheDao`) | Hilt provider for the new DAO |
| `data/repository/RadioRepository.kt` | Modify (add `MIGRATION_1_2` builder step) | Register migration with `Room.databaseBuilder` |
| `data/repository/mapper/Mappers.kt` | Modify (add `PagedStationCacheEntity.toStationDomain()`) | Domain mapping (mirrors `StationEntity.toDomain()`) |
| `data/repository/paging/StationRemoteMediator.kt` | New | `RemoteMediator<Int, PagedStationCacheEntity>` implementation |
| `domain/usecase/UseCases.kt` | Modify (`GetStationsPagingUseCase`) | Switch from custom `StationPagingSource` to Pager+RemoteMediator+Room PagingSource |
| `app/build.gradle.kts` | Modify (add `paging-room:3.2.1`) | One new dependency line |

Files explicitly untouched (per spec): `StationListViewModel.kt`, `StationListScreen.kt`, `RadioBrowserApi.kt`, `SyncWorker.kt`, the existing `StationPagingSource.kt` (still in the repo but no longer referenced — see Task 4 step 4).

---

### Task 1: Schema — entity, DAO, AppDatabase registration, migration, Hilt provider

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/data/database/entity/PagedStationCacheEntity.kt`
- Modify: `app/src/main/java/com/nolansoftware/airadio/data/database/dao/Daos.kt` (append new DAO interface at end of file, before any existing `interface RecentlyPlayedDao`)
- Modify: `app/src/main/java/com/nolansoftware/airadio/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt`
- Modify: `app/src/main/java/com/nolansoftware/airadio/data/repository/RadioRepository.kt` (only the `Room.databaseBuilder` call, line ~68-76 of `AppModule.kt` per `provideAppDatabase` — verify it lives in `AppModule.kt` not `RadioRepository.kt`; if so this row becomes "no change needed")

**Interfaces (consumed by later tasks):**
- `PagedStationCacheEntity(stationuuid, name, url, url_resolved, favicon, country, countrycode, language, tags, codec, bitrate, votes, lastchecktime, queryType, queryValue, pageOffset, sortPosition, cachedAt)` — Kotlin `@Entity` with composite PK on (queryType, queryValue, pageOffset, sortPosition).
- `PagedStationCacheDao.pagingSource(queryType, queryValue): PagingSource<Int, PagedStationCacheEntity>` — Room auto-invalidates on writes to `paged_station_cache`.
- `PagedStationCacheDao.upsertPage(rows: List<PagedStationCacheEntity>)`
- `PagedStationCacheDao.deletePage(queryType, queryValue, pageOffset)`
- `PagedStationCacheDao.newestCachedAt(queryType, queryValue, pageOffset): Long?`
- `PagedStationCacheDao.deleteOlderThan(cutoffMillis: Long)`

- [ ] **Step 1: Create `PagedStationCacheEntity`**

Create file `app/src/main/java/com/nolansoftware/airadio/data/database/entity/PagedStationCacheEntity.kt`:

```kotlin
package com.nolansoftware.airadio.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
```

- [ ] **Step 2: Add `PagedStationCacheDao` to `Daos.kt`**

Open `app/src/main/java/com/nolansoftware/airadio/data/database/dao/Daos.kt`. Append at the end of the file:

```kotlin
@Dao
interface PagedStationCacheDao {

    /**
     * Backing PagingSource for the Browse country / language / tag list.
     * Room's InvalidationTracker fires automatically when any row matching
     * (queryType, queryValue) is inserted or deleted, so writes from
     * StationRemoteMediator appear in the UI without manual notification.
     */
    @Query("""
        SELECT * FROM paged_station_cache
        WHERE queryType = :queryType AND queryValue = :queryValue
        ORDER BY pageOffset ASC, sortPosition ASC
    """)
    fun pagingSource(queryType: String, queryValue: String): androidx.paging.PagingSource<Int, com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity>

    /**
     * Bulk upsert for a freshly fetched page. INSERT OR REPLACE handles the
     * case where we're refreshing an already-cached page.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(rows: List<com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity>)

    /**
     * Drop a single page. Called only from REFRESH-load, never APPEND.
     * APPEND loads preserve existing rows because they can never shrink the
     * page (a page either returns <pageSize rows and triggers endOfPaginationReached,
     * or it returns exactly pageSize).
     */
    @Query("""
        DELETE FROM paged_station_cache
        WHERE queryType = :queryType
          AND queryValue = :queryValue
          AND pageOffset = :pageOffset
    """)
    suspend fun deletePage(queryType: String, queryValue: String, pageOffset: Int)

    /**
     * TTL check used by StationRemoteMediator on REFRESH. Returns the
     * newest cached_at among rows at (queryType, queryValue, pageOffset),
     * or null if nothing is cached at that offset.
     */
    @Query("""
        SELECT MAX(cachedAt) FROM paged_station_cache
        WHERE queryType = :queryType
          AND queryValue = :queryValue
          AND pageOffset = :pageOffset
    """)
    suspend fun newestCachedAt(queryType: String, queryValue: String, pageOffset: Int): Long?

    /**
     * Background housekeeping — drops cache rows older than cutoffMillis.
     * Called from a periodic WorkManager job (separate ticket) or eagerly
     * during RemoteMediator.initialize.
     */
    @Query("DELETE FROM paged_station_cache WHERE cachedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
```

Add imports at the top of `Daos.kt` (only `androidx.paging.PagingSource` is new — the rest is already imported):

```kotlin
import androidx.paging.PagingSource
```

(You can drop the fully-qualified names from the body once the import is added.)

- [ ] **Step 3: Register entity in `AppDatabase.kt`**

Edit `app/src/main/java/com/nolansoftware/airadio/data/database/AppDatabase.kt`:

1. Add import for `PagedStationCacheEntity` at the top alongside the other entity imports:
   ```kotlin
   import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity
   ```

2. Add to the `entities = [...]` array (alphabetical order is not required; append after the existing entries):
   ```kotlin
   PagedStationCacheEntity::class,
   ```

3. Bump `version = 1` to `version = 2`.

4. Add the abstract DAO accessor after the existing ones:
   ```kotlin
   abstract fun pagedStationCacheDao(): PagedStationCacheDao
   ```

5. Add import for `PagedStationCacheDao`:
   ```kotlin
   import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
   ```

- [ ] **Step 4: Add migration constant + register in Room builder**

The migration constant lives in `AppDatabase.kt` per the existing pattern of `companion object { DATABASE_NAME }`. Append:

```kotlin
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
```

Imports needed at top of `AppDatabase.kt`:

```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
```

Now register `MIGRATION_1_2` in `Room.databaseBuilder`. Open `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt`, locate `provideAppDatabase`, and add `.addMigrations(AppDatabase.MIGRATION_1_2)` to the builder chain. It currently reads roughly:

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
    .build()
```

Change to:

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
    .addMigrations(AppDatabase.MIGRATION_1_2)
    .build()
```

(If `MIGRATION_1_2` is unused-after-rename warnings appear, fine — they clear at end of task.)

- [ ] **Step 5: Add Hilt provider for the new DAO**

In `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt`, append a new provider after the existing `provideRecentlyPlayedDao`:

```kotlin
@Provides
fun providePagedStationCacheDao(database: AppDatabase): PagedStationCacheDao =
    database.pagedStationCacheDao()
```

No import changes needed — `PagedStationCacheDao` is in the same package as the other DAO providers (`com.nolansoftware.airadio.data.database.dao`).

- [ ] **Step 6: Build**

```bash
cd /home/nolan/projects/android/AIRadio
./gradlew assembleDebug 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. Warnings limited to the pre-existing items (`ArrowBack` deprecation; `runSync` `batchSize`/`processed` unused). Hilt kapt should regenerate without complaint.

- [ ] **Step 7: Cold-launch the app on a v1 DB**

Force-stop and re-open the app. The Room database should auto-migrate from v1 → v2 via `MIGRATION_1_2`. Watch logcat for any `RoomMigration` or `SQLiteOpenHelper` exceptions:

```bash
adb logcat -c
adb shell am force-stop com.nolansoftware.airadio
adb shell am start -n com.nolansoftware.airadio/.MainActivity
sleep 5
adb logcat -d | grep -iE "room|sqlite|migration|airadio" | head -50
```

Expected: no crash, app reaches Home screen.

**Review Focus line 5 check**: open the database file with `adb shell run-as com.nolansoftware.airadio sqlite3 databases/airadio_database ".schema paged_station_cache"` and confirm the new table exists with the expected columns and the `idx_paged_cache_query` index.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/com/nolansoftware/airadio/data/database/entity/PagedStationCacheEntity.kt \
  app/src/main/java/com/nolansoftware/airadio/data/database/dao/Daos.kt \
  app/src/main/java/com/nolansoftware/airadio/data/database/AppDatabase.kt \
  app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt
git commit -m "Add paged_station_cache schema for offline browse cache

- New PagedStationCacheEntity: denormalized full Station payload +
  (queryType, queryValue, pageOffset, sortPosition, cachedAt) metadata.
  Composite PK makes INSERT OR REPLACE idempotent.
- PagedStationCacheDao: pagingSource() for the Pager, upsertPage,
  deletePage (REFRESH-only), newestCachedAt (TTL guard),
  deleteOlderThan (housekeeping).
- AppDatabase v1 -> v2 with MIGRATION_1_2 (purely additive:
  CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS).
  SyncWorker.clearAllStations() does not touch this table.
- Hilt provider for PagedStationCacheDao mirrors the existing DAO
  providers in AppModule.

ViewModel and Screen unchanged. Cache wiring lands in subsequent
commits. See docs/superpowers/specs/2026-09-27-paging-remote-mediator-offline-cache-design.md
for the full design."
```

---

### Task 2: Mapper — `PagedStationCacheEntity → Station`

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/data/repository/mapper/Mappers.kt`

**Interfaces (consumed by later tasks):**
- `PagedStationCacheEntity.toStationDomain(): Station` — used by `GetStationsPagingUseCase` to map Room rows to the domain type the screen already consumes.

- [ ] **Step 1: Add the mapper**

Open `app/src/main/java/com/nolansoftware/airadio/data/repository/mapper/Mappers.kt`. Append at the end (alongside the existing `toDomainStations` extension added in `c008a5d`):

```kotlin
/**
 * Cache entity -> domain. Mirrors StationEntity.toDomain() above; the only
 * difference is the source row (cache vs. global popular stations).
 * lastchecktime is already a Long here, no String->Long parse needed.
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
```

No new imports required — `Station` and `PagedStationCacheEntity` are both already in the same file's import set (verify `PagedStationCacheEntity` is imported; if not, add `import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity` at the top).

- [ ] **Step 2: Build**

```bash
./gradlew assembleDebug 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. No new warnings.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/data/repository/mapper/Mappers.kt
git commit -m "Add PagedStationCacheEntity.toStationDomain() mapper

Mirrors StationEntity.toDomain(). lastchecktime is already Long on
the cache entity, no String->Long parse needed."
```

---

### Task 3: `StationRemoteMediator`

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/data/repository/paging/StationRemoteMediator.kt`

**Interfaces (consumed by Task 4):**
- `class StationRemoteMediator(type, query, repository, dao, now, pageSize, ttlMillis) : RemoteMediator<Int, PagedStationCacheEntity>`
- `override suspend fun initialize(): InitializeAction` — returns `LAUNCH_INITIAL_REFRESH` per spec.
- `override suspend fun load(loadType, state): MediatorResult` — full logic per spec §RemoteMediator.

- [ ] **Step 1: Create the file**

Create `app/src/main/java/com/nolansoftware/airadio/data/repository/paging/StationRemoteMediator.kt`:

```kotlin
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
```

Note: each `dao.deletePage` + `dao.upsertPage` pair runs as separate Room suspend transactions — that's fine for our purposes (the only concurrent reader is the Room PagingSource, which Room's invalidation tracker fires AFTER our writes commit, so it always sees a consistent state).

- [ ] **Step 2: Build**

```bash
./gradlew assembleDebug 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. No new warnings.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/data/repository/paging/StationRemoteMediator.kt
git commit -m "Add StationRemoteMediator for paginated browse cache

Coordinates network -> Room writes for the country / language / tag
Paging 3 stream. Reads are handled by the Room PagingSource on the
PagedStationCacheDao (Task 1); writes are handled here.

TTL: 7-day window applies to REFRESH only. APPEND always fetches
(forward scroll). PREPEND short-circuits (browse lists do not
paginate backward).

REFRESH deletes the page before inserting so a now-shorter page does
not leave dangling rows. APPEND inserts only — forward scroll can
never shrink a page."
```

---

### Task 4: UseCase integration + end-to-end manual verification

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/domain/usecase/UseCases.kt` (`GetStationsPagingUseCase`)
- Modify: `app/build.gradle.kts` (add `paging-room:3.2.1`)

**Interfaces (final state):**
- `GetStationsPagingUseCase.invoke(type, query, scope): Flow<PagingData<Station>>` — same signature as before (`c008a5d`); now backed by Pager + RemoteMediator + Room PagingSource instead of custom `StationPagingSource`.

- [ ] **Step 1: Add `paging-room` dependency**

In `app/build.gradle.kts`, find the Paging section (added in `c008a5d`):

```kotlin
// Paging 3 — backs the infinite-scroll station list. paging-compose
// provides collectAsLazyPagingItems() so the LazyColumn can read a
// PagingData<Station> directly. Version 3.2.1 is compatible with the
// Compose BOM 2024.12.01 used above; bumping either should re-check.
implementation("androidx.paging:paging-runtime-ktx:3.2.1")
implementation("androidx.paging:paging-compose:3.2.1")
```

Append one line:

```kotlin
implementation("androidx.paging:paging-room:3.2.1")
```

Update the comment block above to note that `paging-room` provides the Room `PagingSource` integration:

```kotlin
// Paging 3 — backs the infinite-scroll station list. paging-compose
// provides collectAsLazyPagingItems() so the LazyColumn can read a
// PagingData<Station> directly. paging-room lets the @Query on
// PagedStationCacheDao return a PagingSource<Int, T> directly so the
// Pager can serve cached rows from Room. Version 3.2.1 is compatible
// with the Compose BOM 2024.12.01 used above; bumping either should
// re-check.
```

- [ ] **Step 2: Update `GetStationsPagingUseCase`**

Open `app/src/main/java/com/nolansoftware/airadio/domain/usecase/UseCases.kt`. Locate the existing `GetStationsPagingUseCase` (added in `c008a5d`). Replace the class body with:

```kotlin
class GetStationsPagingUseCase @Inject constructor(
    private val repository: RadioRepository,
    private val dao: PagedStationCacheDao,
) {
    operator fun invoke(type: String, query: String, scope: CoroutineScope): Flow<PagingData<Station>> {
        val pager = Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                enablePlaceholders = false,
            ),
            remoteMediator = StationRemoteMediator(
                type = type,
                query = query,
                repository = repository,
                dao = dao,
            ),
            pagingSourceFactory = { dao.pagingSource(type, query) },
        )
        return pager.flow
            .map { pagingData -> pagingData.map { it.toStationDomain() } }
            .cachedIn(scope)
    }

    companion object {
        // Matches the API's per-request page size; the API caps a single
        // request at this many rows.
        const val PAGE_SIZE = 100

        // 7 days. A page older than this on REFRESH is silently refetched
        // when the network is available.
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
```

Imports to add at the top of `UseCases.kt` (alongside the existing Paging imports added in `c008a5d`):

```kotlin
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import com.nolansoftware.airadio.data.repository.paging.StationRemoteMediator
import com.nolansoftware.airadio.data.repository.mapper.toStationDomain
```

(The Hilt `@Inject constructor` line ensures `PagedStationCacheDao` is available because we added the provider in Task 1.)

- [ ] **Step 3: Build**

```bash
./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. No new warnings.

- [ ] **Step 4: Decide what to do with the now-unused `StationPagingSource.kt`**

The custom `StationPagingSource` from `c008a5d` is no longer referenced — `GetStationsPagingUseCase` no longer constructs it. Leave the file in place (do not delete in this commit) — deleting it is a separate cleanup that's outside this plan's scope. The build will succeed with an unused class warning at most; verify there are no errors.

If you want to remove it as part of this plan: also remove the now-dead `items(count = pagingItems.itemCount, ...)` overload from `StationListScreen.kt`'s imports (it stays in use — only the `PagingSource` reference is dead). Decision: **leave it**. Mention it in the commit message.

- [ ] **Step 5: Manual device testing — install + cold launch**

```bash
./gradlew installDebug
adb shell am force-stop com.nolansoftware.airadio
adb shell am start -n com.nolansoftware.airadio/.MainActivity
sleep 5
adb logcat -d | grep -iE "airadio|room|sqlite|paging" | head -50
```

Expected: app launches cleanly to Home screen, no `RoomOpenHelper` errors, no `RemoteMediator` crashes.

- [ ] **Step 6: Manual device testing — populate cache**

Open `Browse > Countries > China`. Confirm the list loads (first network fetch via REFRESH).

Verify cache write landed:

```bash
adb shell run-as com.nolansoftware.airadio sqlite3 databases/airadio_database \
  "SELECT COUNT(*) FROM paged_station_cache WHERE queryType='country' AND queryValue='China';"
```

Expected: 100 (or less if API returned fewer on the first page).

- [ ] **Step 7: Manual device testing — offline read (Review Focus line 3)**

1. With the app open on China (cache populated from Step 6), turn on airplane mode.
2. Force-stop and re-open the app.
3. Open `Browse > Countries > China` again.
4. Expected: real rows render from cache. **No error panel.** The first few rows show station names, not "Couldn't load stations. Check your connection."

Verify with logcat that the RemoteMediator did emit `MediatorResult.Error` for the REFRESH attempt (network is down):

```bash
adb logcat -d | grep -iE "ioexception|connect|stationremotemediator" | tail -10
```

A non-empty result is fine; the screen still shows cached rows because `itemCount > 0`.

- [ ] **Step 8: Manual device testing — TTL short-circuit (Review Focus line 1)**

1. With airplane mode still on, open China. Rows render from cache.
2. Turn airplane mode OFF.
3. Force-stop, re-open, navigate to China again.
4. Expected: rows appear without a visible network spinner. The RemoteMediator's REFRESH consults `dao.newestCachedAt(...)`, sees cached_at < 7 days old, returns `Success` without an API call.

Verify with logcat that no `searchStations` Retrofit call was made during this last open:

```bash
adb logcat -c
adb shell am force-stop com.nolansoftware.airadio
adb shell am start -n com.nolansoftware.airadio/.MainActivity
sleep 3
# (navigate to China in the UI)
sleep 5
adb logcat -d | grep -iE "okhttp.*searchstations|--> get" | head -20
```

Expected: no `--> GET .../json/stations/search?country=China` line in this logcat session.

- [ ] **Step 9: Manual device testing — APPEND behavior (Review Focus line 2)**

With China open and at least one page cached:
1. Scroll past the 100th row.
2. Watch logcat for a new `searchStations?country=China&offset=100` call.
3. Expected: yes, an APPEND-load fires for offset=100. The new rows arrive.
4. Verify the previous page was NOT deleted — check the cache:

```bash
adb shell run-as com.nolansoftware.airadio sqlite3 databases/airadio_database \
  "SELECT pageOffset, COUNT(*) FROM paged_station_cache WHERE queryType='country' AND queryValue='China' GROUP BY pageOffset;"
```

Expected: at least two distinct `pageOffset` values (0 and 100), each with their row count.

- [ ] **Step 10: Manual device testing — daily sync coexistence**

1. Populate China cache (Step 6).
2. Trigger a sync (easiest: force-stop + relaunch the app — the cold-start `OneTimeWorkRequest` fires `syncInitialData()`).
3. After sync completes, query the cache table:

```bash
adb shell run-as com.nolansoftware.airadio sqlite3 databases/airadio_database \
  "SELECT COUNT(*) FROM paged_station_cache WHERE queryType='country' AND queryValue='China';"
```

Expected: same count as before sync. `SyncWorker.clearAllStations()` only touches the global `stations` table; `paged_station_cache` survives.

Also verify the global stations table was actually refreshed (sanity check that sync ran):

```bash
adb shell run-as com.nolansoftware.airadio sqlite3 databases/airadio_database \
  "SELECT COUNT(*) FROM stations;"
```

Expected: ~300 (the cold-start `getStations(limit=300)` payload).

- [ ] **Step 11: Manual device testing — PREPEND short-circuit (Review Focus line 4)**

There is no UI surface that triggers PREPEND on the StationListScreen. To exercise the branch, write a one-off debug trigger (or just trust the code review of the `if (loadType == LoadType.PREPEND) return Success(...)` line at the top of `load()`).

If you want to verify on device: temporarily add a button on StationListScreen that calls `pagingItems.refresh()` and watch logcat for any Retrofit call to `?country=X&offset=0` reappearing — but this is overkill for a defensive code path. Skip unless motivated.

- [ ] **Step 12: Commit**

```bash
git add \
  app/build.gradle.kts \
  app/src/main/java/com/nolansoftware/airadio/domain/usecase/UseCases.kt
git commit -m "Wire GetStationsPagingUseCase to Pager + RemoteMediator + Room

GetStationsPagingUseCase now returns a Pager backed by:
- PagedStationCacheDao.pagingSource() (Room PagingSource) for cached rows
- StationRemoteMediator for network fills, TTL checks, and append

The screen-side signature (Flow<PagingData<Station>>) is unchanged;
StationListViewModel and StationListScreen need no edits. The
loadState handling added in c008a5d already absorbs REFRESH errors
when itemCount > 0 (cached rows visible, error panel hidden).

The custom StationPagingSource from c008a5d is no longer referenced.
Left in place for now; removing it is a separate cleanup.

Verified manually (see plan Task 4 Steps 6-10):
- China opens, cache populated to ~100 rows
- Airplane mode + restart + reopen China: cached rows visible, no
  error panel
- Online reopen of fresh cache: no network call (TTL short-circuit)
- Scroll past page 1: APPEND fetches page 2, prior page preserved
- SyncWorker.clearAllStations() runs: paged_station_cache untouched

See docs/superpowers/specs/2026-09-27-paging-remote-mediator-offline-cache-design.md
for the design and docs/superpowers/plans/2026-09-27-paging-remote-mediator-offline-cache.md
for the implementation plan."
```

---

## Spec coverage self-check

| Spec section | Covered by |
| --- | --- |
| §Storage — schema | Task 1 |
| §Storage — DAO | Task 1 |
| §Database version bump 1→2 | Task 1 |
| §Migration safety (additive only) | Task 1 Steps 1+4, Review Focus line 5 |
| §Dependencies (paging-room) | Task 4 Step 1 |
| §Updated GetStationsPagingUseCase | Task 4 Step 2 |
| §RemoteMediator (initialize + load) | Task 3 |
| §TTL rules (table) | Task 3 (load logic), Task 4 Step 8 (verify TTL short-circuit) |
| §SyncWorker non-interaction | Task 4 Step 10 |
| §Files to add / modify | All four tasks |
| §Risks (cache unbounded growth) | Out of scope — flagged in spec, deferred to follow-up ticket |
| §Verification (6 scenarios) | Task 4 Steps 6-11 cover all six |

## Out of scope (per spec non-goals)

- **Cache size cap + periodic cleanup.** `dao.deleteOlderThan` is in the DAO and ready to use; the WorkManager job that calls it is a separate ticket.
- **Manual swipe-to-refresh UI.** TTL + REFRESH is sufficient.
- **Removing the now-unused `StationPagingSource.kt`.** Cosmetic; safe to defer.

## Follow-up tickets to file after this lands

1. Cache-size cleanup: schedule a `PeriodicWorkRequest` calling `dao.deleteOlderThan(now - 30 days)`.
2. Remove `StationPagingSource.kt` (dead code).
3. Migration test using `MigrationTestHelper` (once a `src/androidTest/` harness exists).
