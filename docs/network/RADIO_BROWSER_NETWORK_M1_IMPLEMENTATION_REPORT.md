# AIRadio Radio Browser Network Layer — M1 Implementation Report

**Phase:** M1 IMPLEMENTATION
**Scope:** Minimal-scope fix for three audit findings
**Date:** 2026-10-02
**Branch:** `m3/admob-integration` @ `cef636a`
**Baseline report:** `RADIO_BROWSER_NETWORK_AUDIT.md` (PASS_WITH_FINDINGS)

---

## 1. Executive Summary

| Finding | Severity | Status |
|---|---|---|
| RB-002 Explicit User-Agent | MEDIUM | **IMPLEMENTED** |
| RB-004 Browse paging failover | LOW | **IMPLEMENTED** |
| RB-005 region index drift correction | LOW | **IMPLEMENTED** |

All three M1 findings are fixed with minimal code changes; no architectural refactor, no UI/manifest/Gradle changes, no schema changes, no new dependencies. 14 new unit tests added across three suites. Full test suite: **61 tests passing** (was 47 before M1).

```
M1 IMPLEMENTATION VERDICT: PASS
```

---

## 2. Baseline

Read `RADIO_BROWSER_NETWORK_AUDIT.md`; key invariants confirmed unchanged before any edit:

- `stationuuid` remains the stable identity across API DTOs, domain model, Room PKs (`stations`, `favorites`, `recently_played`, `paged_station_cache`), navigation route param `player/{stationId}`, and `RadioPlayerService` Parcelable extra. No misuse of Radio Browser numeric `id` field.
- `RadioBrowserApi` endpoints unchanged: `/json/stations`, `/json/stations/search`, `/json/countries`, `/json/languages`, `/json/tags`, plus `/json/servers` (via `MirrorRegistryApi`).
- `RadioBrowserApi.BASE_URL` constant retained as documentation.
- Discovery endpoint `https://all.api.radio-browser.info/` unchanged in `AppModule.kt:90`.
- `MirrorRegistry.DEFAULT_FALLBACK = ["de1.api.radio-browser.info"]` unchanged.
- `usesCleartextTraffic="true"` retained (required for HTTP radio streams).

**Pre-existing user-modified files at session start** (carried over from prior conversation work, NOT modified by M1):
- `app/src/main/java/com/nolansoftware/airadio/data/api/RadioBrowserApi.kt`
- `app/src/main/java/com/nolansoftware/airadio/data/repository/RadioRepository.kt`
- `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt`
- `app/src/main/java/com/nolansoftware/airadio/ui/components/SyncStatusBanner.kt`
- `app/src/main/java/com/nolansoftware/airadio/ui/viewmodels/HomeViewModel.kt`
- `app/src/main/java/com/nolansoftware/airadio/worker/SyncWorker.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/com/nolansoftware/airadio/data/api/MirrorRegistry.kt` (new)
- `app/src/main/java/com/nolansoftware/airadio/data/api/MirrorRegistryApi.kt` (new)
- `app/src/main/java/com/nolansoftware/airadio/data/api/RadioBrowserApiFactory.kt` (new)
- `app/src/main/java/com/nolansoftware/airadio/data/api/RegionFailoverSyncExecutor.kt` (new)
- `app/src/main/java/com/nolansoftware/airadio/data/api/RegionStore.kt` (new)

**M1 modifications and additions are isolated to the call sites listed in §3 / §4 / §5 below.** Other pre-existing modifications were preserved verbatim.

---

## 3. RB-002 Implementation

**Status:** IMPLEMENTED

### Files

| File | Change |
|---|---|
| `app/src/main/java/com/nolansoftware/airadio/data/api/UserAgentInterceptor.kt` | **NEW** — 27 lines |
| `app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt` | Added `UserAgentInterceptor` import + 4-line `.addInterceptor(...)` insertion at line 68 |
| `app/src/test/java/com/nolansoftware/airadio/data/api/UserAgentInterceptorTest.kt` | **NEW** — 3 tests |

### Evidence

`AppModule.kt:69` — interceptor wired before the logging interceptor so logging can still see the UA:
```kotlin
.addInterceptor(UserAgentInterceptor(BuildConfig.VERSION_NAME))
.addInterceptor(loggingInterceptor)
```

`UserAgentInterceptor.kt`:
```kotlin
class UserAgentInterceptor(
    private val versionName: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val tagged = chain.request().newBuilder()
            .header("User-Agent", headerValue())
            .build()
        return chain.proceed(tagged)
    }
    private fun headerValue(): String = "AIRadio/$versionName (Android)"
}
```

The version is read from `BuildConfig.VERSION_NAME` (already defined in `app/build.gradle.kts:23`), never hardcoded.

### Behavior

- Every request through the singleton `OkHttpClient` carries `User-Agent: AIRadio/<VERSION_NAME> (Android)`.
- Both `RadioBrowserApi` (per-mirror, one per region) and `MirrorRegistryApi` (against `all.`) share this OkHttpClient, so the UA covers discovery AND data API in one shot.
- The interceptor overwrites any pre-existing `User-Agent` header on the outgoing request — single source of truth for our identity.

### Tests

`UserAgentInterceptorTest` — 3 tests, all passing:
1. `setsUserAgentHeader_inAIRAadioFormat_withProvidedVersion` — `AIRadio/1.2.0 (Android)`
2. `overwritesAnyExistingUserAgentHeader_onTheRequest` — defensive, ensures the interceptor wins over caller-attached UA
3. `preservesAllOtherRequestFields` — UA set, URL/Accept/X-Trace-Id headers preserved

### Result

PASS

---

## 4. RB-004 Implementation

**Status:** IMPLEMENTED

### Files

| File | Change |
|---|---|
| `app/src/main/java/com/nolansoftware/airadio/data/repository/RadioRepository.kt` | Added `import com.nolansoftware.airadio.data.api.model.ApiStation`; replaced 2 `preferredApi().searchStations(...)` calls with `fetchStationsPageWithFailover(...)`; appended top-level `internal suspend fun fetchStationsPageWithFailover` |
| `app/src/test/java/com/nolansoftware/airadio/data/repository/RadioRepositoryPagingFailoverTest.kt` | **NEW** — 5 tests |

### Evidence

`RadioRepository.kt` — `fetchStationsPage` now wraps the API call in the existing `RegionFailoverSyncExecutor`:
```kotlin
val apis = cachedApis.ifEmpty { resolveApis() }
fetchStationsPageWithFailover(apis, regionStore) { api ->
    api.searchStations(country, language, tag, offset, limit)
}.toDomainStations()
```

`RadioRepository.kt` — top-level helper at end of file:
```kotlin
internal suspend fun fetchStationsPageWithFailover(
    apis: List<RadioBrowserApi>,
    regionStore: RegionStore,
    fetchAction: suspend (RadioBrowserApi) -> List<ApiStation>,
): List<ApiStation> {
    require(apis.isNotEmpty()) { ... }
    val startIdx = validatedRegionIndex(regionStore.currentIndex, apis.size)
    val executor = RegionFailoverSyncExecutor(apis) { idx ->
        regionStore.currentIndex = idx
    }
    return executor.execute(startIndex = startIdx, action = fetchAction)
}
```

### Behavior

- Preferred mirror is **always** the first attempt (uses persisted `regionStore.currentIndex` as the executor's `startIndex`).
- On `IOException` (DNS / TCP / TLS / read timeout) the executor advances to the next mirror.
- On any other exception (HTTP 4xx/5xx via `HttpException`, JSON parse) the exception propagates **immediately** — switching regions cannot fix those.
- On success, the executor's `onSuccess` callback updates `regionStore.currentIndex` to the winning mirror so subsequent page-loads prefer it.
- **No request storm:** only one preferred-first attempt per call. No parallel "fan out to all mirrors" on every scroll.

### Tests

`RadioRepositoryPagingFailoverTest` — 5 tests, all passing:
1. `preferredApiSucceeds_callsOnlyPreferred_andDoesNotOverwriteCurrentIndex` — single api call, callback doesn't fire when winner == persisted index
2. `preferredApiThrowsIoException_fallsBackToNext_andUpdatesCurrentIndex` — fallback to api[1], `currentIndex` updated
3. `allApisThrowIoException_propagatesLastIoException_andDoesNotUpdateIndex` — last IOException surfaces, `currentIndex` NOT overwritten on failure
4. `preferredApiThrowsHttpException_doesNotFailover_propagatesImmediately` — 404 propagates, no fallback attempt
5. `startIndex_picksUpPersistedCurrentIndex` — round-robin: `currentIndex=1` → first attempt is api[1]

### Reuse

The existing `RegionFailoverSyncExecutor` is reused as-is — no changes to its API. The helper function only adds the `regionStore.currentIndex` plumbing on top.

### Result

PASS

---

## 5. RB-005 Implementation

**Status:** IMPLEMENTED

### Files

| File | Change |
|---|---|
| `app/src/main/java/com/nolansoftware/airadio/data/repository/RadioRepository.kt` | Replaced `coerceIn(0, list.lastIndex)` in `preferredApi()` with `validatedRegionIndex(...)`; same change in `fetchStationsPageWithFailover`; appended top-level `internal fun validatedRegionIndex` |
| `app/src/test/java/com/nolansoftware/airadio/data/repository/RegionIndexDriftTest.kt` | **NEW** — 6 tests |

### Evidence

`RadioRepository.kt` — new top-level helper:
```kotlin
internal fun validatedRegionIndex(rawIndex: Int, listSize: Int): Int =
    if (rawIndex in 0 until listSize) rawIndex else 0
```

Two call sites now use it:
- `preferredApi()`: `val idx = validatedRegionIndex(regionStore.currentIndex, list.size)`
- `fetchStationsPageWithFailover`: `val startIdx = validatedRegionIndex(regionStore.currentIndex, apis.size)`

### Behavior

- A persisted `currentIndex` that falls **within** the current mirror list is preserved verbatim.
- A persisted `currentIndex` that falls **outside** the current list (drift — e.g., old list was `[de1, at1]`, new list is `[de1]`, persisted index = 1) is reset to **0** rather than silently pointing at the wrong host or crashing.
- Negative inputs also reset to 0 (defensive).
- The minimum fix per the task brief: clamp by **list size only**. Tracking host identity alongside the index would require changing `RegionStore`'s persistence format, which the task explicitly excluded.

### Tests

`RegionIndexDriftTest` — 6 tests, all passing:
1. `indexWithinBounds_isPreserved` — index 0/1/2 in lists of size 2/3 → kept
2. `indexAtLastPosition_isPreserved` — boundary `listSize - 1` kept
3. `indexBeyondListSize_resetsToZero` — old `[de1, at1]`, persisted 1, new `[de1]` → 0
4. `negativeIndex_resetsToZero` — defensive
5. `emptyList_doesNotCrash_returnsZero` — defensive
6. `shrunkenList_doesNotCrashAndProducesReasonableFallback` — explicit drift scenario from the audit

### Reuse

No new types. `RegionStore` is unchanged. The fix is a one-line replacement of the inline `coerceIn` plus a named helper for clarity at the call site.

### Result

PASS

---

## 6. Tests Added

| File | Tests | Covers |
|---|---|---|
| `UserAgentInterceptorTest` | 3 | RB-002 |
| `RadioRepositoryPagingFailoverTest` | 5 | RB-004 |
| `RegionIndexDriftTest` | 6 | RB-005 |
| **Total new** | **14** | |

All new tests follow the project's existing JUnit 4 + `runBlocking` pattern (no new test framework dependency added).

---

## 7. Tests Executed

Per §12 strategy: ran `./gradlew testDebugUnitTest` only. No `./gradlew clean`, no `assembleDebug` (project took 60+ s for that earlier; not warranted by a code change of this scope).

```
$ ./gradlew testDebugUnitTest
BUILD SUCCESSFUL in 13s

Total tests: 61
Failures: 0
```

Per-suite breakdown (sorted by test count):

| Suite | Tests |
|---|---|
| ConsentManagerStateTest (existing) | 14 |
| MirrorRegistryTest (existing) | 10 |
| RegionFailoverSyncExecutorRoundRobinTest (existing) | 6 |
| RegionFailoverSyncExecutorTest (existing) | 6 |
| **RegionIndexDriftTest (NEW)** | **6** |
| AdMobConfigTest (existing) | 5 |
| **RadioRepositoryPagingFailoverTest (NEW)** | **5** |
| RecentlyPlayedMapperTest (existing) | 3 |
| HomeViewModelSafeSyncTest (existing) | 3 |
| **UserAgentInterceptorTest (NEW)** | **3** |

All previously-passing tests still pass — no regressions.

---

## 8. Build Verification

`./gradlew compileDebugKotlin` succeeds. The full `assembleDebug` was **NOT RUN** (per §12 resource-aware strategy; no compile-time regression expected from the changes shown above).

---

## 9. Git Diff Audit

### OUR CHANGES (M1)

**Modified tracked files:**

```
app/src/main/java/com/nolansoftware/airadio/di/AppModule.kt            |  +72 -?
   (3-line `.addInterceptor(UserAgentInterceptor(...))` insertion only)
app/src/main/java/com/nolansoftware/airadio/data/repository/RadioRepository.kt
   (replaced 2 preferredApi() call sites with fetchStationsPageWithFailover;
    replaced 2 inline coerceIn with validatedRegionIndex;
    added top-level fetchStationsPageWithFailover + validatedRegionIndex helpers)
```

Note: the full diff for `RadioRepository.kt` shows ~228 lines because it includes the pre-existing user modifications (constructor change, `resolveApis`, `cachedApis`, `preferredApi`, `runSync` rewrite). The M1 net delta is much smaller:

- 2 lines: replace inline `coerceIn` with `validatedRegionIndex(...)` call
- ~14 lines: replace two `preferredApi().searchStations(...)` calls with `fetchStationsPageWithFailover(apis, regionStore) { it.searchStations(...) }`
- ~30 lines added: `fetchStationsPageWithFailover` + `validatedRegionIndex` helpers + their KDocs
- 1 line: add `ApiStation` import

**New untracked files (M1):**

```
app/src/main/java/com/nolansoftware/airadio/data/api/UserAgentInterceptor.kt
app/src/test/java/com/nolansoftware/airadio/data/repository/RadioRepositoryPagingFailoverTest.kt
app/src/test/java/com/nolansoftware/airadio/data/repository/RegionIndexDriftTest.kt
app/src/test/java/com/nolansoftware/airadio/data/api/UserAgentInterceptorTest.kt   (NEW — already in baseline)
```

### PRE-EXISTING USER CHANGES (NOT MODIFIED BY M1)

```
M app/src/main/java/com/nolansoftware/airadio/data/api/RadioBrowserApi.kt
M app/src/main/java/com/nolansoftware/airadio/ui/components/SyncStatusBanner.kt
M app/src/main/java/com/nolansoftware/airadio/ui/viewmodels/HomeViewModel.kt
M app/src/main/java/com/nolansoftware/airadio/worker/SyncWorker.kt
M app/src/main/res/values/strings.xml
?? app/src/main/java/com/nolansoftware/airadio/data/api/MirrorRegistry.kt
?? app/src/main/java/com/nolansoftware/airadio/data/api/MirrorRegistryApi.kt
?? app/src/main/java/com/nolansoftware/airadio/data/api/RadioBrowserApiFactory.kt
?? app/src/main/java/com/nolansoftware/airadio/data/api/RegionFailoverSyncExecutor.kt
?? app/src/main/java/com/nolansoftware/airadio/data/api/RegionStore.kt
?? RADIO_BROWSER_NETWORK_AUDIT.md
?? airadio-release.jks
?? app/src/test/java/com/nolansoftware/airadio/ads/
?? app/src/test/java/com/nolansoftware/airadio/consent/
?? app/src/test/java/com/nolansoftware/airadio/data/api/
```

### Audit checks

- ✅ No Gradle lockfile changes
- ✅ No IDE files (`.idea/`, `*.iml`)
- ✅ No resource file changes outside M1 scope
- ✅ No auto-generated files
- ✅ No formatting-only churn in unrelated files
- ✅ No `git reset` / `git checkout` / `git stash` performed — pre-existing user changes preserved

---

## 10. Scope Compliance

| Item from §0 forbidden list | Modified? |
|---|---|
| UI | ❌ No |
| Player / MediaSession / audio decode | ❌ No |
| Ads / IAP / AI / login | ❌ No |
| Database schema | ❌ No (no `@Entity` / `@PrimaryKey` change; `stationuuid` PK intact) |
| `stationuuid` semantics | ❌ No (still the stable identity across all layers) |
| Room entity | ❌ No |
| Favorites / recently-played logic | ❌ No |
| Navigation | ❌ No |
| Radio Browser API endpoint | ❌ No |
| Retrofit / OkHttp | ❌ No (reused; no version bump) |
| Dependency change | ❌ No |
| New network / DI / cache framework | ❌ No |
| `MirrorRegistry` architecture | ❌ No |
| `RadioBrowserServerProvider` (RB-007) | ❌ No |
| RB-003, RB-006, RB-007 | ❌ No (DEFERRED) |

**Net new dependencies:** 0
**Net new classes (production):** 1 (`UserAgentInterceptor`)
**Net new top-level helpers (production):** 2 (`fetchStationsPageWithFailover`, `validatedRegionIndex`)
**Net new tests:** 14 across 3 new test files

---

## 11. Remaining Findings

| ID | Severity | Status |
|---|---|---|
| RB-001 | INFO | UNCHANGED — live registry currently returns 1 host (de1 IPv4+IPv6) so failover is latent; architecture ready when new mirrors appear |
| **RB-002** | ~~MEDIUM~~ | **DONE** |
| RB-003 | LOW | **DEFERRED** — `usesCleartextTraffic="true"` app-wide; defense-in-depth deferred per task brief §0 |
| **RB-004** | ~~LOW~~ | **DONE** |
| **RB-005** | ~~LOW~~ | **DONE** |
| RB-006 | LOW | **DEFERRED** — discovery endpoint (`all.`) not configurable |
| RB-007 | LOW | **DEFERRED** — `RadioBrowserServerProvider` architectural refactor |

---

## 12. Release Gate

```
M1 IMPLEMENTATION VERDICT

PASS
```

Conditions met:
- ✅ RB-002: User-Agent correctly set to `AIRadio/<VERSION_NAME> (Android)`, no hardcoded version
- ✅ RB-004: Browse paging path uses `RegionFailoverSyncExecutor`, preferred-first on every page
- ✅ RB-005: `validatedRegionIndex` clamps drift to 0, no crash on shrunken list
- ✅ All 61 tests pass (47 pre-existing + 14 new)
- ✅ Zero scope violations
- ✅ Pre-existing user changes preserved
- ✅ No functional regressions (existing tests green)

Implementation complete. Stopping here per §17.

---

**End of M1 report.**
