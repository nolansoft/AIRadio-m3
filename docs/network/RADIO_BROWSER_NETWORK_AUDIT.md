# AIRadio Radio Browser Network Layer Audit

**Audit type:** Strict READ-ONLY
**Scope:** Radio Browser API integration (data/api, data/repository, di, worker, ui/navigation, ui/screens, player, AndroidManifest)
**Out of scope:** UI components unrelated to API, audio decode, MediaSession, background playback, ads, IAP, login
**Date:** 2026-10-02
**Repository state:** branch `m3/admob-integration`, HEAD `cef636a` (plus uncommitted network-layer work in working tree)

> **Evidence policy.** Every claim cites a concrete file path and line number. Items I could not verify from the source are marked **UNVERIFIED**.

---

## 1. Executive Summary

**Verdict: PASS_WITH_FINDINGS**

AIRadio's Radio Browser integration is architecturally sound for the radio-streaming use case:

- **`stationuuid` is consistently the stable station identity** across API DTOs, domain model, Room entities, navigation routes, and the player service. There is **no** use of Radio Browser's numeric `id` field anywhere as a cross-mirror identity.
- **Multi-mirror failover + dynamic server discovery are implemented** via `MirrorRegistry` + `RegionFailoverSyncExecutor` + `RadioBrowserApiFactory`. The app does NOT hard-depend on a single mirror.
- **Favorites and recently-played are denormalized snapshots** keyed by `stationuuid`, so they survive a `clearAllStations()` cycle and (critically) do not depend on a specific mirror being live to resolve.
- **HTTPS is used for every Radio Browser API call.** Cleartext traffic in the manifest is required by individual radio station stream URLs (a separate concern).
- **HTTP timeouts are explicitly configured** (30/60/30/120 s) and far exceed the OkHttp defaults — appropriate for the multi-MB `/json/stations` payloads.

**No CRITICAL or HIGH findings.**

**MEDIUM findings (3):**
- Mirror registry's hardcoded fallback `[de1]` plus single-mirror live reality means failover is **latent** rather than active; only proves out when the registry returns ≥2 entries.
- No custom `User-Agent` is sent — OkHttp's default UA leaks OkHttp version and JVM, not app identity.
- `usesCleartextTraffic="true"` is set app-wide (necessary for HTTP radio streams, but exposes any HTTP-only endpoints we might add later).

**LOW findings (4):** discovery-vs-business coupling, mirror-specific data absence, retry-on-connection-failure defaults, banner UX for cache-stale path.

The implementation also has several **INFO-level** positive findings (denormalized favorites, 24h registry cache TTL, runSync guard, etc.).

---

## 2. Actual Architecture

### Real call chain (UI → network)

```
HomeScreen.kt:255            UI / Compose: read station list
   ↓ collectAsState()
HomeViewModel.kt:59          exposes StateFlow<List<Station>?>
   ↓ .stateIn(scope)
GetPopularStationsUseCase    domain/usecase/UseCases.kt:184
   ↓ invoke()
RadioRepository.kt:72        data/repository/RadioRepository.kt:72
   ↓ getPopularStations()
StationDao.kt:23             data/database/dao/Daos.kt:23
   ↑ Flow<List<StationEntity>>
ToDomain (Mappers.kt:97)     data/repository/mapper/Mappers.kt:97
   ↑ List<Station>            domain/model/DomainModels.kt:9

(Write path, for the SyncWorker that populates Room:)
SyncWorker.kt:23             worker/SyncWorker.kt:23 (@HiltWorker)
   ↓ radioRepository.syncInitialData()
RadioRepository.kt:265       syncInitialData() → runSync(fullSync=false)
   ↓ regionFailover.execute(startIndex=...)  RegionFailoverSyncExecutor.kt:48
   ↓ action(api) → coroutineScope { async { api.getStations() / getCountries() / getLanguages() / getTags() } }
RadioBrowserApi (Retrofit)   data/api/RadioBrowserApi.kt:12
   ↑ @GET("json/stations") etc.
OkHttpClient                  di/AppModule.kt:55-68
   ↑ Retrofit.Builder().baseUrl("https://$host/").client(client)
RadioBrowserApiFactory.kt:27  DefaultRadioBrowserApiFactory.create(host)

(Mirror discovery:)
MirrorRegistryApi.kt:19      @GET("json/servers")
   ↑ baseUrl="https://all.api.radio-browser.info/"   AppModule.kt:86
MirrorRegistry.kt            cache-first TTL read → falls back to DEFAULT_FALLBACK
```

### Critical classes & files

| File | Class | Role |
|---|---|---|
| `data/api/RadioBrowserApi.kt` | `RadioBrowserApi` | Retrofit interface (5 endpoints) |
| `data/api/MirrorRegistryApi.kt` | `MirrorRegistryApi` | Retrofit interface for `/json/servers` |
| `data/api/MirrorRegistry.kt` | `MirrorRegistry` | Live registry fetcher + 24h TTL cache + fallback |
| `data/api/RegionFailoverSyncExecutor.kt` | `RegionFailoverSyncExecutor` | Tries apis in order, IOException → next |
| `data/api/RadioBrowserApiFactory.kt` | `RadioBrowserApiFactory` / `DefaultRadioBrowserApiFactory` | host → Retrofit instance |
| `data/api/RegionStore.kt` | `RegionStore` / `SharedPrefsRegionStore` | Persists last-successful mirror index |
| `data/repository/RadioRepository.kt` | `RadioRepository` | Sole class touching both DAOs and API |
| `worker/SyncWorker.kt` | `SyncWorker` | WorkManager-driven cold-start + daily sync |
| `di/AppModule.kt` | `AppModule` / `RegionStoreModule` | Hilt graph |

---

## 3. Actual API Endpoints

| Endpoint | Used? | File / method | Purpose |
|---|---|---|---|
| `GET /json/servers` | ✅ | `data/api/MirrorRegistryApi.kt:19` | Discover live mirror list |
| `GET /json/stations?limit=...` | ✅ | `data/api/RadioBrowserApi.kt:14` `getStations` | Top-voted worldwide |
| `GET /json/stations/search?country=&language=&tag=&offset=&limit=` | ✅ | `data/api/RadioBrowserApi.kt:26` `searchStations` | Paged browse (country/language/tag) |
| `GET /json/countries` | ✅ | `data/api/RadioBrowserApi.kt:42` `getCountries` | Country list (sync metadata) |
| `GET /json/languages` | ✅ | `data/api/RadioBrowserApi.kt:45` `getLanguages` | Language list (sync metadata) |
| `GET /json/tags` | ✅ | `data/api/RadioBrowserApi.kt:48` `getTags` | Tag list (sync metadata) |
| `GET /json/url` | ❌ | not referenced | — |
| `GET /json/stations/byuuid` | ❌ | not referenced | — |
| `GET /json/stations/byname` | ❌ | not referenced | — |
| `GET /json/stations/bycountryexact` | ❌ | not referenced | — |

**Observation.** `/json/url` (which resolves `streamUrl` from `stationuuid`) is **NOT** used. The app trusts the `urlResolved` returned by the stations/search endpoints. This is correct because that field is returned by every station-listing endpoint; an extra roundtrip would just be a fallback for a degraded API. But it does mean if `urlResolved` is missing/expired at sync time and the user later plays that station, the player will hit whatever was cached.

---

## 4. Current Mirror Strategy

**Architecture: Hybrid C/D** — dynamic discovery with hardcoded single-mirror fallback.

```
AIRadio
   ↓
MirrorRegistry.getMirrors()
   ↓ (cache-first 24h TTL)
GET https://all.api.radio-browser.info/json/servers
   ↓ (on failure)
Stale cache, else DEFAULT_FALLBACK = ["de1.api.radio-browser.info"]
   ↓
List<RadioBrowserApi>  (one Retrofit instance per host)
   ↓
RegionFailoverSyncExecutor.execute(startIndex = (lastIdx + 1) % size)
```

### Evidence

- Live fetch: `data/api/MirrorRegistryApi.kt:19` `@GET("json/servers")`, base URL `di/AppModule.kt:86` `https://all.api.radio-browser.info/`
- Fallback constant: `data/api/MirrorRegistry.kt:133` `DEFAULT_FALLBACK = listOf("de1.api.radio-browser.info")`
- Round-robin: `data/repository/RadioRepository.kt:345` `val startIdx = (regionStore.currentIndex + 1) % apis.size`
- Persisted last-good: `data/api/RegionStore.kt` `SharedPrefsRegionStore`

### Number of places `de1.api.radio-browser.info` appears (production source only)

| File | Line | Context | Severity |
|---|---|---|---|
| `data/api/RadioBrowserApi.kt` | 70 | `const val BASE_URL` — kept for documentation/non-cold-start callers | LOW |
| `data/api/MirrorRegistry.kt` | 133 | `DEFAULT_FALLBACK` — only used when both live fetch and cache fail | LOW |
| `ui/components/SyncStatusBanner.kt` | 102 | Inside a code comment as an example error message | INFO |
| `data/api/MirrorRegistry.kt` | 64, 80 | Comments | INFO |

The hardcoded `de1` lives in **two production code locations** (a `const val` and a fallback list) plus comment references. Both are properly documented as fallback paths. They do NOT constitute a hard-dependency on de1: the cold-start sync goes through `MirrorRegistry.getMirrors()`, which serves cache first, then live registry, then falls back to this list.

---

## 5. Server Discovery

**Implemented.** Uses the project's documented `/json/servers` registry endpoint, **not** DNS SRV discovery (`_api._tcp.radio-browser.info`).

| Mechanism | Status | Evidence |
|---|---|---|
| `all.api.radio-browser.info/json/servers` (HTTP round-robin) | ✅ Active | `AppModule.kt:86` `provideMirrorRegistryApi` |
| DNS SRV (`_api._tcp.radio-browser.info`) | ❌ Not used | (no SRV lookup anywhere) |
| Static mirror list | ✅ As fallback only | `MirrorRegistry.DEFAULT_FALLBACK` |

**Finding (INFO):** The user's directive in the original task brief was to use `all.api.radio-browser.info` for discovery. The current code does exactly that. The fact that my probe environment could not reach `all.` over TLS but real devices + the project Android emulator can is noted but does not change the architectural assessment.

---

## 6. stationuuid Identity Audit

**Verdict: CORRECT.** `stationuuid` is the stable identity end-to-end.

### Usage map

| Layer | Field | Evidence |
|---|---|---|
| API DTO | `stationuuid: String` | `data/api/model/ApiModels.kt:8-9` `@SerializedName("stationuuid")` |
| Domain model | `val stationuuid: String` (Parcelize) | `domain/model/DomainModels.kt:10` |
| Room `stations` PK | `@PrimaryKey val stationuuid: String` | `data/database/entity/DatabaseEntities.kt:10` |
| Room `favorites` PK | `@PrimaryKey val stationuuid: String` | `DatabaseEntities.kt:45` |
| Room `recently_played` PK | `@PrimaryKey val stationuuid: String` | `DatabaseEntities.kt:77` |
| Room `paged_station_cache` (indexed) | `WHERE stationuuid = ...` | `Daos.kt:230` |
| Navigation route | `player/{stationId}` → receives `station.stationuuid` | `ui/navigation/Navigation.kt:27`, `ui/screens/PlayerScreen.kt:79` |
| Player intent extra | `putExtra(EXTRA_STATION, station)` (full Parcelable) | `player/RadioPlayerService.kt:406` |
| Search result list keys | `key = { it.stationuuid }` | `HomeScreen.kt:255`, `FavoritesScreen.kt:94`, etc. |

### `id` field usage

The Radio Browser API returns a numeric `id` (DB row ID, not stable across mirrors — different mirrors have different DB autoincrement counters). I searched for any use:

```
grep -rn '\.id\b\| val id\b\|@PrimaryKey val id' app/src/main
```

Only hit: `MainActivity.kt:106` `findStartDestination().id` — **this is a Navigation graph node ID, not a station ID.** No misuse of the Radio Browser numeric `id` field.

**No HIGH PRIORITY FINDING on this dimension.** This is one of the strongest aspects of the codebase.

---

## 7. URL / url_resolved Audit

### What the API returns

`ApiStation` (`data/api/model/ApiModels.kt:7-37`) carries both:
- `url: String` — declared stream URL
- `url_resolved: String` — server-resolved working URL (the API follows redirects server-side)

### What the player uses

**`RadioPlayerService.kt:193` — `MediaItem.fromUri(station.urlResolved)`** — the player prioritizes the resolved URL. This is correct: `urlResolved` skips the redirect hop and is more likely to point to a live endpoint.

### URL persistence

Both `url` and `url_resolved` are persisted in:
- `stations` table (`DatabaseEntities.kt:13`)
- `favorites` table (snapshot at toggle time, `DatabaseEntities.kt:63`)
- `recently_played` table (snapshot at play time, `DatabaseEntities.kt:97`)
- `paged_station_cache` table (`PagedStationCacheEntity.kt:35`)

### URL staleness on mirror failover

**No issue.** URLs are stream endpoints (e.g., `http://stream.example.com:8000/mount`), not mirror-relative paths. A mirror failover does not invalidate them. The DB stores the snapshot taken when the user favorited or played; even if all radio-browser mirrors go offline, the favorites list still resolves and the player still tries the saved stream URL.

### URL expiry on the radio station side

Stream URLs can go stale (the radio station changes its mount point). The code does NOT periodically re-fetch a station's metadata to refresh `url_resolved`. If the user plays a favorited station weeks after the last sync and the stream URL has changed, playback will fail. This is a known limitation; **out of scope** for this audit per task §20.

---

## 8. HTTP Client Audit

### Library

**OkHttp 4.x** (transitive from `play-services-ads` 22.6.0 + Retrofit 2.9.0), configured at `di/AppModule.kt:44-68`.

### Configuration

| Setting | Value | Evidence |
|---|---|---|
| `connectTimeout` | 30 s | `AppModule.kt:64` |
| `readTimeout` | 60 s | `AppModule.kt:65` |
| `writeTimeout` | 30 s | `AppModule.kt:66` |
| `callTimeout` | 120 s | `AppModule.kt:67` |
| `retryOnConnectionFailure` | (default true) | not set explicitly |
| `addInterceptor` | `HttpLoggingInterceptor` (BASIC debug / NONE release) | `AppModule.kt:46-51, 61` |
| Custom DNS | none | — |
| Custom `User-Agent` | **none** | (see §9) |

### Retrofit configuration

| Setting | Value | Evidence |
|---|---|---|
| Base URL (per-mirror) | `https://$host/` | `RadioBrowserApiFactory.kt:33` |
| Base URL (registry) | `https://all.api.radio-browser.info/` | `AppModule.kt:86` |
| Converter | Gson (lenient) | `AppModule.kt:38-41, 71` |
| Client | shared `@Singleton OkHttpClient` | `AppModule.kt:55` |
| Error handling | none at Retrofit layer; deferred to `safePerformSync` / `runSync` catch | — |

**Observation (INFO):** Retrofit and OkHttp versions (4.11.0 logging-interceptor, 2.9.0 retrofit) are not pinned in `app/build.gradle.kts` directly — they come transitively. Versions are determined by `play-services-ads:22.6.0`. **UNVERIFIED whether these versions are affected by known CVEs** without running an audit tool like OWASP Dependency-Check.

---

## 9. User-Agent Audit

**Finding (MEDIUM):** No custom User-Agent is configured.

```
grep -rn "User-Agent\|UserAgent\|userAgent" app/src/main
→ (no matches)
```

The OkHttp default UA is something like `okhttp/4.x.x`. The Radio Browser operators can therefore see OkHttp traffic but cannot identify AIRadio in their access logs. This is a minor operational / courtesy issue, not a security one.

**Recommendation direction (not implemented):** Add a `User-Agent: AIRadio/<version> (Android)` interceptor. The project version is available via `BuildConfig.VERSION_NAME` (per `app/build.gradle.kts:23`). `Application` package name + version is what Radio Browser operators commonly filter on when triaging abuse.

---

## 10. HTTPS Audit

### Radio Browser API transport: ✅ ALL HTTPS

All Radio Browser URLs in source are `https://`:
- `AppModule.kt:86` `https://all.api.radio-browser.info/`
- `RadioBrowserApiFactory.kt:33` `https://$host/` (constructed from mirror list)
- `RadioBrowserApi.kt:70` `https://de1.api.radio-browser.info/`

`http://` references found:
- `data/api/model/ApiModels.kt:14` — `@SerializedName("url_resolved")` — **this is a field NAME, not a URL.**
- `app/src/main/AndroidManifest.xml:2` — XML namespace, not a URL.
- `app/src/main/res/xml/backup_rules.xml:8` and `data_extraction_rules.xml:8` — Apache license URL in comments.

No HTTP Radio Browser API endpoint exists in the source.

### Cleartext traffic

`AndroidManifest.xml:25` `android:usesCleartextTraffic="true"`.

**Required by:** Individual radio station stream URLs (returned in `ApiStation.url` / `url_resolved`) are predominantly HTTP. The user's CLAUDE.md notes that radio streams are an independent concern. The `network_security_config` referenced in CLAUDE.md does **not** exist in source (verified by `find app/src/main/res/xml -type f` → only `backup_rules.xml`, `data_extraction_rules.xml`).

**Finding (LOW):** App-wide `usesCleartextTraffic="true"` is broader than necessary. Best practice would be a `network_security_config.xml` allowing cleartext only for the specific domains returned in stream URLs, or for all domains (which is what `usesCleartextTraffic="true"` already does). Adding the config would provide defense-in-depth if someone later adds a feature that downloads untrusted HTML/JS. **Out of audit scope to recommend a network_security_config; current behavior is acceptable for a radio-streaming app.**

---

## 11. Failure / Retry Audit

### Per-layer failure handling

| Failure type | Detected by | Current behavior |
|---|---|---|
| DNS failure | OkHttp throws `UnknownHostException` (subclass of `IOException`) | `RegionFailoverSyncExecutor` catches IOException → next mirror → on all-fail, rethrows last |
| TCP connect failure | OkHttp throws `ConnectException` (IOException) | Same as above |
| TLS failure | OkHttp throws `SSLHandshakeException` (IOException) | Same as above |
| Read timeout | OkHttp throws `SocketTimeoutException` (IOException) | Same as above |
| HTTP 4xx | Retrofit returns `HttpException` (NOT IOException) | **Immediate throw — no failover** (this is intentional; failover won't fix 401/403/404) |
| HTTP 5xx | Retrofit returns `HttpException` (NOT IOException) | **Immediate throw — no failover** |
| Empty body | Gson parses `[]` or `{}`; no exception | UI shows empty list |
| Malformed JSON | Gson throws `JsonSyntaxException` (RuntimeException) | Caught by `runSync` outer catch → `SyncState.Failed` → re-throws → WorkManager retry |

### WorkManager retry

`worker/SyncWorker.kt:46`:
```
if (runAttemptCount < MAX_AUTO_RETRIES) Result.retry() else Result.failure()
```
- `MAX_AUTO_RETRIES = 2` (SyncWorker.kt:63) — WorkManager retries with default exponential backoff.
- After 2 failed attempts, `Result.failure()` is returned and the worker stops trying.

### App-side auto-retry loop

`ui/viewmodels/HomeViewModel.kt:116-132` `startAutoRetryLoop`:
- `INITIAL_AUTO_RETRY_DELAY_MS = 3_000L` (3 s)
- `AUTO_RETRY_DELAY_MS = 5_000L` (5 s)
- `MAX_AUTO_RETRY_ATTEMPTS = 2`
- Worst case wall-clock = 3 + 2 × 5 = **13 seconds** before the UI stops auto-retrying.

### Safe exception handling

`HomeViewModel.kt:162-183` `safePerformSync`: catches `Exception` (NOT `CancellationException`) and logs. Prevents `viewModelScope.launch` from propagating an uncaught `UnknownHostException` to the main-thread uncaught-exception handler (the FATAL EXCEPTION crash fixed in commit `4ed9088`).

### Failure flow diagram

```
User opens app
   ↓
WorkManager fires SyncWorker (fullSync=false)
   ↓
runSync → resolveApis() → MirrorRegistry.getMirrors()
   ↓ (cache hit < 24h)
   ↓── cache miss / stale ──→ GET all./json/servers
   │                              ↓ failure
   │                              ↓ catch → fall back to disk cache
   │                              ↓ catch → fall back to ["de1"]
   ↓
RegionFailoverSyncExecutor.execute(startIndex = roundRobin)
   ↓
   try api[0]
   │ ↓ IOException (DNS / TCP / TLS / read timeout)
   │ ↓ try api[1] ... → eventually rethrows last IOException
   │ ↓ HttpException (4xx / 5xx) or JsonSyntaxException
   │ ↓ immediate throw
   ↓
runSync catch → SyncState.Failed(message, willRetry=true)
   ↓
safePerformSync catches → no crash
   ↓
HomeViewModel.startAutoRetryLoop sees SyncState.Failed
   ↓ (delay 5 s)
   ↓ retry up to 2 times
   ↓
   if all fail → banner shows "Sync failed: <exception class>: <message>"
   + "Tap to retry" CTA
```

---

## 12. Mirror Failover Audit

### Does the current implementation support mirror failover?

**Yes**, with the following caveats:

1. `RadioRepository.runSync` (`RadioRepository.kt:343`) wraps the parallel API batch in `RegionFailoverSyncExecutor.execute`, which tries each mirror in `apis` list in order and advances on `IOException`.
2. `RegionFailoverSyncExecutor` is tested for: success on first API (`RegionFailoverSyncExecutorTest`), fallback to next on IOException, throw on non-IOException, throw last on all-fail, callback with correct index (`RegionFailoverSyncExecutorTest`), round-robin startIndex (`RegionFailoverSyncExecutorRoundRobinTest`).
3. The list of mirrors is dynamic — populated by `MirrorRegistry` from the live `/json/servers` registry, cached for 24 h, with stale-cache and hardcoded fallback layers (`MirrorRegistryTest`).

### Caveats

1. **Live registry currently returns only `de1`.** I confirmed on the Android emulator on 2026-10-02 that `/json/servers` returns 2 entries — both `de1.api.radio-browser.info` (one IPv4 `91.98.4.78`, one IPv6 `2a01:4f8:1c1d:699::1`). After dedup, the in-memory mirror list has **1 host**. So failover is **architecturally present but not exercised today**. If the project adds a new mirror, the code will use it automatically.

2. **Paged browsing (`fetchStationsPage`) does NOT use the failover executor.** It calls `preferredApi()` which selects `apis[regionStore.currentIndex]` directly (`RadioRepository.kt:102-108`). This is intentional (user-driven, low-frequency, no need for retry storm on every scroll) but means: if the user is browsing on the cached "preferred" mirror and that mirror goes down mid-session, the next page fetch will fail. The exception propagates up and the paging UI shows a Paging error state. **Finding (LOW).**

3. **Hardcoded fallback `[de1]` is single-point-of-failure if the registry is unreachable AND the cache is empty.** This is the "first install, all radio-browser servers down" scenario. In that case the app falls back to de1, which is the same single point we're trying to mitigate. **Finding (INFO):** The fallback list should be expanded to multiple hostnames (e.g., `[de1, de2, ...]`) once those are reachable. Today, after dedup, there is only one reachable host, so the fallback list is effectively `[de1]`.

---

## 13. Cache Audit

| Storage | What is cached | Mirror-specific? | Evidence |
|---|---|---|---|
| Room `stations` | Top-N stations (300 by default, 1500 on daily) | ❌ Mirror-agnostic — content is the same across mirrors | `RadioRepository.kt:301-313` |
| Room `favorites` | Full station snapshot at toggle time, keyed by `stationuuid` | ❌ Mirror-agnostic | `RadioRepository.kt:415-433` |
| Room `recently_played` | Full station snapshot at play time, keyed by `stationuuid` | ❌ Mirror-agnostic | `RadioRepository.kt:447-466` |
| Room `paged_station_cache` | Pages visited during Browse, keyed by `(queryType, queryValue, pageOffset, sortPosition)` | ❌ Mirror-agnostic — content from any mirror is interchangeable | `RadioRepository.kt:177-205` |
| SharedPreferences `radio_browser_prefs` | `region_index` (last-successful mirror), `mirror_list` + `mirror_list_fetched_at` | ✅ Mirror-specific by design | `RegionStore.kt:31-39`, `MirrorRegistry.kt:48-66` |

**No data is cached in a mirror-specific way that would break failover.** The only mirror-specific cache is the `region_index` pointer, which is the entire *point* of the failover system. All station data is content (which is consistent across mirrors) plus stream URLs (which are mirror-independent).

---

## 14. Persistence / Favorites Audit

### Favorite flow trace

```
User taps heart on StationCard
   ↓
StationListViewModel.toggleFavorite(station)           ui/viewmodels/*ViewModel.kt
   ↓
ToggleFavoriteUseCase(station)                          domain/usecase/UseCases.kt
   ↓
RadioRepository.addToFavorites(station)                 RadioRepository.kt:413
   ↓
favoritesDao.addToFavorites(FavoriteEntity(
    stationuuid = station.stationuuid,                 ← KEY
    added_time, name, url, url_resolved,               ← SNAPSHOT
    favicon, country, countrycode, language,
    tags, codec, bitrate, votes, lastchecktime,
))
   ↓
Room persists. PK is stationuuid. No mirror URL stored.
```

### Surviving mirror failover

If all mirrors go offline:

1. `favorites` table still resolves (`FavoriteEntity` is self-contained).
2. `FavoritesScreen` reads `FavoriteEntity.toFavoriteStationDomain()` (`Mappers.kt:155`) which uses the **stored** snapshot — no network call.
3. User can tap play → `RadioPlayerService` uses `station.urlResolved` from the **stored** snapshot → stream URL tried directly.
4. If the stream URL is also stale → ExoPlayer fails → `PlayerState.Error(message)`.

**No mirror-coupled state in the favorites path. Critical correctness property: PASS.**

---

## 15. Google Play Relevant Technical Findings

Per task §15, only Radio-Browser-network-related findings.

| Item | Status | Notes |
|---|---|---|
| HTTP API transport | ✅ All HTTPS | See §10 |
| Unnecessary plaintext traffic | ⚠️ `usesCleartextTraffic="true"` is app-wide | Required for radio streams (HTTP); see §10 |
| Hardcoded 3rd-party server | ✅ Configurable via `MirrorRegistry.DEFAULT_FALLBACK` + live registry | See §4 |
| Privacy data sent | ✅ Only the `/json/*` query params (no user/device ID) | Reviewed `RadioBrowserApi.kt` annotations |
| Device ID sent | ✅ None | No Android ID / Advertising ID in any API param |
| User-Agent leaks PII | ✅ None — no custom UA at all | See §9 |
| API params minimization | ✅ Only `country`, `language`, `tag`, `offset`, `limit`, `order`, `reverse`, `name` — all functional, no device identifiers | `RadioBrowserApi.kt:14-49` |

**No findings that would trigger Google Play policy review** (which is outside this audit's scope per task §15).

---

## 16. Findings

### FINDING RB-001 — Live registry currently returns a single host (mirror failover latent)

**Severity:** INFO
**Category:** Mirror discovery
**File:** `data/api/MirrorRegistry.kt`, `data/repository/RadioRepository.kt`
**Line:** N/A (architectural)

**Observed behavior:** `MirrorRegistry.getMirrors()` deduplicates by hostname. Live `/json/servers` (verified on emulator 2026-10-02) returns 2 entries both named `de1.api.radio-browser.info` (IPv4 + IPv6). After dedup, in-memory list size = 1. Round-robin and failover logic exists but the live registry currently cannot feed it more than 1 host.

**Evidence:**
- `MirrorRegistry.kt:99-104` `dedup` via `distinct()`
- Emulator verification: 2 entries, both `de1`
- Comment in `RadioBrowserApi.kt:54-72` already notes "When the project re-publishes new mirrors, no code change is needed"

**Impact:** When the project re-publishes new mirrors, they will be picked up automatically. No current user-visible impact because `de1` is the only reachable host.

**Why it matters:** Future-proofing. Worth noting so a future contributor doesn't mistake the absence of failover exercise for a code bug.

**Recommended direction:** No code change required. If multi-region becomes a hard requirement (e.g., for a CN mirror), the fallback list in `MirrorRegistry.DEFAULT_FALLBACK` could be expanded to `[de1, <cn-mirror>]` to provide resilience even when the registry is unreachable.

**Implementation status:** NOT IMPLEMENTED (because no change is required)

---

### FINDING RB-002 — No custom User-Agent

**Severity:** MEDIUM
**Category:** API etiquette / observability
**File:** `di/AppModule.kt`
**Line:** 55-68 (OkHttp builder)

**Observed behavior:** No `addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "AIRadio/${BuildConfig.VERSION_NAME}").build()) }` call exists. All Radio Browser requests go out with OkHttp's default `User-Agent: okhttp/<version>` header.

**Evidence:**
- `grep -rn "User-Agent\|UserAgent\|userAgent" app/src/main` → no matches
- `AppModule.kt:55-68` OkHttp builder does not call `.header(...)` or `Interceptor` for UA

**Impact:** Radio Browser operators cannot identify AIRadio traffic in their access logs. Slightly worse for their abuse triage and slightly worse for our ability to be unblocked if a future ban wave sweeps generic OkHttp traffic.

**Why it matters:** Standard practice for production apps hitting third-party APIs is to identify themselves.

**Recommended direction:** Add a custom interceptor setting `User-Agent: AIRadio/${BuildConfig.VERSION_NAME} (Android)`. BuildConfig.VERSION_NAME is already defined in `app/build.gradle.kts:23`.

**Implementation status:** NOT IMPLEMENTED (audit phase)

---

### FINDING RB-003 — `usesCleartextTraffic="true"` is app-wide

**Severity:** LOW
**Category:** Transport security (Radio Browser API surface)
**File:** `app/src/main/AndroidManifest.xml`
**Line:** 25

**Observed behavior:** The manifest sets `android:usesCleartextTraffic="true"` at the application level. There is no `android:networkSecurityConfig` resource to scope cleartext to specific domains.

**Evidence:** `AndroidManifest.xml:25`; `find app/src/main/res/xml -type f` → only `backup_rules.xml`, `data_extraction_rules.xml`. No network_security_config.xml exists.

**Impact:** All HTTP endpoints (currently only individual radio stream URLs) are allowed. If a future contributor adds an HTTP-only API endpoint for a non-radio-browser feature, it would work without raising a NetworkSecurityPolicy violation.

**Why it matters:** Defense-in-depth. Not a current bug.

**Recommended direction:** If scope is added in the future, replace with a `network_security_config.xml` that allows cleartext only for the domains of returned stream URLs (or, more pragmatically, for all `0.0.0.0/0` to match current behavior). Otherwise, leave as is.

**Implementation status:** NOT IMPLEMENTED (audit phase)

---

### FINDING RB-004 — `paged_station_cache` browsing path does not use failover

**Severity:** LOW
**Category:** Mirror failover
**File:** `data/repository/RadioRepository.kt`
**Line:** 100-108, 240-265

**Observed behavior:** `fetchStationsPage` (the cold-start paging for Browse > Countries / Languages / Tags) calls `preferredApi()` which returns `apis[regionStore.currentIndex]` directly. No failover wrapping.

**Evidence:** `RadioRepository.kt:104` returns `apis[regionStore.currentIndex.coerceIn(0, list.lastIndex)]`. No `RegionFailoverSyncExecutor` call in `fetchStationsPage`.

**Impact:** If the persisted "preferred" mirror is down at the moment the user scrolls to the next page, the page fetch fails immediately. User sees a Paging error in the Browse list. The next cold start re-evaluates `regionStore.currentIndex` via the round-robin logic, so a transient outage is healed on the next launch.

**Why it matters:** Slightly worse UX during a mirror outage than necessary. Mitigated by the next cold start.

**Recommended direction:** Wrap `fetchStationsPage`'s API call in `RegionFailoverSyncExecutor.execute`. Tradeoff: more parallel connection attempts on every scroll, which the previous design intentionally avoided. A `preferredApi -> first attempt -> fall back to next` strategy would be the minimal fix.

**Implementation status:** NOT IMPLEMENTED (audit phase)

---

### FINDING RB-005 — `regionStore.currentIndex` is not actively checked against current mirror list size

**Severity:** LOW
**Category:** Mirror failover / index drift
**File:** `data/repository/RadioRepository.kt`
**Line:** 100-108

**Observed behavior:** `preferredApi()` does `regionStore.currentIndex.coerceIn(0, list.lastIndex)`. This handles "index too large" but NOT "index refers to a host no longer in the live list." If the registry previously returned `[de1, at1]` and now returns `[de1]`, the stored `currentIndex=1` silently becomes invalid for `at1` and is coerced to `0`.

**Evidence:** `RadioRepository.kt:107` `coerceIn(0, list.lastIndex)`.

**Impact:** Defensive `coerceIn` means the app never crashes. But `currentIndex` may point to a "phantom" prior region, so `regionStore.currentIndex = idx` written by `onSuccess` may set a misleading value. Cosmetic at worst.

**Why it matters:** Minor state hygiene.

**Recommended direction:** When `MirrorRegistry.getMirrors()` returns a different list, re-validate the persisted `currentIndex` against the new list (default to 0 if `currentIndex` doesn't match any element by some stable key).

**Implementation status:** NOT IMPLEMENTED (audit phase)

---

### FINDING RB-006 — Discovery endpoint URL is not configurable

**Severity:** LOW
**Category:** Configuration flexibility
**File:** `di/AppModule.kt`
**Line:** 86

**Observed behavior:** `provideMirrorRegistryApi` hardcodes `https://all.api.radio-browser.info/` as the discovery endpoint. Changing it requires a code edit.

**Evidence:** `AppModule.kt:86`.

**Impact:** If `all.` goes offline (as it did on 2026-10-02 from the WSL2 test environment), the discovery call fails. The 24h cache fallback kicks in, but on a fresh install with no cache, the user gets the hardcoded `[de1]` list immediately, with no chance to discover an alternative.

**Why it matters:** Real-world operability.

**Recommended direction:** Make the discovery endpoint configurable via `local.properties` (similar to how AdMob IDs are configured) or via a remote config service. Lower-effort: keep the hardcoded default but also include `https://de1.api.radio-browser.info/json/servers` as a fallback discovery endpoint tried after `all.` fails.

**Implementation status:** NOT IMPLEMENTED (audit phase)

---

### FINDING RB-007 — Discovery and business logic are coupled at repository layer

**Severity:** LOW
**Category:** Architecture / layering
**File:** `data/repository/RadioRepository.kt`
**Line:** 88-95, 343-348

**Observed behavior:** `RadioRepository` knows about `MirrorRegistry`, `RadioBrowserApiFactory`, and the failover executor — i.e., the repository is doing both data access and mirror orchestration. There is no separate `RadioBrowserServerProvider` abstraction.

**Evidence:** `RadioRepository.kt:55-56` constructor injects both `apiFactory` and `mirrorRegistry`; `RadioRepository.kt:88-95` `resolveApis` orchestrates discovery.

**Impact:** Tighter coupling than ideal. Repository is harder to unit-test in isolation (would need to mock both). But the failover + cache logic is well-covered by `MirrorRegistryTest` (10 tests) and `RegionFailoverSyncExecutorTest` + `RegionFailoverSyncExecutorRoundRobinTest` (12 tests), so the layered coverage is acceptable.

**Why it matters:** Future maintainability.

**Recommended direction:** Optional refactor — extract a `RadioBrowserServerProvider` interface that hides discovery + failover + caching, and have `RadioRepository` consume `RadioBrowserApi` from it directly. Not required for current functionality.

**Implementation status:** NOT IMPLEMENTED (audit phase)

---

## 17. Recommended Target Architecture (NOT IMPLEMENTED — design only)

```
┌──────────────────────────────────────────────────────────┐
│ RadioBrowserServerProvider (interface)                   │
│   - discover(): List<RadioBrowserServer>                 │
│   - acquireApi(): RadioBrowserApi   (round-robin start)  │
│   - reportSuccess(server) / reportFailure(server)        │
└──────────────────────────────────────────────────────────┘
                       ↓ implements
┌──────────────────────────────────────────────────────────┐
│ DefaultRadioBrowserServerProvider                        │
│   - MirrorRegistry.getMirrors()    ← cache + live        │
│   - RegionFailoverSyncExecutor      ← failover            │
│   - RegionStore                    ← round-robin state    │
│   - RadioBrowserApiFactory         ← host → Retrofit      │
└──────────────────────────────────────────────────────────┘
                       ↓ exposes
                RadioBrowserApi (interface)
                       ↓ used by
              RadioRepository (unchanged)
                       ↓
            Domain (UseCases, ViewModels, UI)
```

This is a refactor recommendation, not a requirement. The current `RadioRepository`-orchestrates-everything design works correctly and is well-tested. The recommendation would only matter if more sophisticated server-selection logic is added later (e.g., health-aware load balancing, weighted round-robin).

---

## 18. Goal Backlog Assessment

For each goal in task §19, classify based on current audit:

| Goal | Required? | Status |
|---|---|---|
| 1. Don't treat `de1` as the only permanent server | **REQUIRED** | ✅ Done — live registry + fallback list |
| 2. Mirror failover | **REQUIRED** | ✅ Done — `RegionFailoverSyncExecutor` |
| 3. HTTPS preferred | **REQUIRED** | ✅ Done — all API URLs are `https://` |
| 4. Explicit User-Agent | **RECOMMENDED** | ❌ Missing — RB-002 |
| 5. Use `stationuuid` as stable identity | **REQUIRED** | ✅ Done — full chain uses `stationuuid` |
| 6. Favorites not dependent on mirror-specific id | **REQUIRED** | ✅ Done — `stationuuid` is the only ID; no `id` field used |
| 7. Reasonable API timeout | **REQUIRED** | ✅ Done — 30/60/30/120 s |
| 8. Recoverable DNS/network failure | **REQUIRED** | ✅ Done — 3-layer fallback (live → cache → hardcoded) + UI auto-retry + manual CTA |
| 9. Mirror failure doesn't break search | **REQUIRED** | ⚠️ Partial — sync is resilient; paging path is not (RB-004) |
| 10. Server list cacheable | **RECOMMENDED** | ✅ Done — SharedPreferences, 24h TTL |
| 11. Server discovery decoupled from business | **OPTIONAL** | ⚠️ Coupled at `RadioRepository` (RB-007) |

---

## 19. Audit Verdict

```
AUDIT VERDICT

PASS_WITH_FINDINGS
```

**Justification:**

- **No CRITICAL or HIGH findings.** The codebase correctly uses `stationuuid` as the stable identity end-to-end, has multi-mirror failover, uses HTTPS, has reasonable timeouts, and has self-contained favorites/recently-played that survive mirror outage.
- **3 MEDIUM / LOW findings** are documentation and etiquette (User-Agent, cleartext scope, paged-browse failover) — none block production.
- **4 LOW findings** (index drift, hardcoded discovery endpoint, layered coupling, etc.) are future-proofing concerns.

### Can we proceed to IMPLEMENTATION phase?

**Yes.** No CRITICAL or HIGH findings are blocking. The MEDIUM finding (User-Agent) is a quick, low-risk addition if desired. The LOW findings can be deferred or skipped without affecting current app behavior.

The pre-existing memory entry `multi-region-radio-browser-failover.md` already documents the architectural decisions and tradeoffs of the current mirror system; no audit changes to that memory are needed.

### Recommended implementation order (if/when proceeding)

1. **RB-002 (User-Agent)** — single interceptor addition, no behavior change, ~10 lines.
2. **RB-004 (paged-browse failover)** — wrap `fetchStationsPage` calls in `RegionFailoverSyncExecutor`, ~20 lines plus tests.
3. **RB-005 (region index drift)** — re-validate `currentIndex` against the live list after `resolveApis`, ~5 lines.
4. RB-003, RB-006, RB-007 — defer; non-urgent.

---

**End of audit.**
