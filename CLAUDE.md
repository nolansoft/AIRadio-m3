# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Working tree status:** This is a local-only git repository on
> `master` with no commits yet and no remote configured. `git push`
> will fail until a remote is added. The `.gitignore` references
> further down list files that should not be committed.

## Project Overview

**AI Radio** — A production Android internet radio app that streams 10,000+ global stations via the public Radio Browser API (`https://api.radio-browser.info/`), with offline caching, favorites, recently played, background playback, and daily background sync. UI is Jetpack Compose Material 3; architecture is Clean Architecture (data / domain / ui) with MVVM + Repository + Hilt DI.

Package root: `com.nolansoftware.airadio`. minSdk 26, targetSdk/compileSdk 34.

## Build & Run

Use the Gradle wrapper (`gradlew` on Linux/macOS, `gradlew.bat` on Windows). `local.properties` already pins the Android SDK (`D:\Android\Sdk`) and `gradle.properties` pins **JDK 17** at `C:\jdk17\jdk-17.0.2`. **Do not run with a JDK > 17** — Kotlin 1.9.20's bundled JavaVersion parser breaks on JDK 25+.

Common commands:

```bash
./gradlew assembleDebug              # build debug APK
./gradlew installDebug               # install to connected device/emulator
./gradlew lint                       # static analysis (Android Lint)
./gradlew test                       # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumented tests on device
./gradlew clean                      # clean build outputs (build/, app/build/)
./gradlew :app:dependencies          # inspect dependency graph
```

There is no `test` source set populated yet — `./gradlew test` runs but finds nothing. Tests use JUnit 4, Espresso, and the Compose test BOM. To run a single instrumented test class: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=fully.qualified.ClassName`.

## Toolchain Pinning (do not bump casually)

- AGP **8.2.0**, Kotlin **1.9.20**, Compose Compiler **1.5.5** (the verified match for Kotlin 1.9.20 — 1.5.3 only supports 1.9.10)
- Compose BOM `2023.10.01`
- Room **2.6.1**, Retrofit **2.9.0**, Hilt **2.48**, Media3 **1.2.0**, Coil **2.5.0**, Navigation Compose **2.7.5**, WorkManager **2.8.1**, Hilt-Navigation-Compose / Hilt-Work **1.1.0**
- `jvmTarget = 17`, `sourceCompatibility/targetCompatibility = 17`
- `kapt { correctErrorTypes = true }` is required for Hilt + Room annotation processing
- Maven mirrors in `settings.gradle.kts` (Tencent + Aliyun + Google + Maven Central + JitPack) — leave the mirrors in place; they are needed for builds from CN networks

## Architecture

```
app/src/main/java/com/nolansoftware/airadio/
├── AIRadioApp.kt              # @HiltAndroidApp; provides WorkManager HiltWorkerFactory
├── MainActivity.kt            # @AndroidEntryPoint, sets Compose content, hosts NavHost + bottom bar
├── data/
│   ├── api/                   # RadioBrowserApi (Retrofit) + ApiModels (Gson DTOs)
│   ├── database/              # AppDatabase (Room v1), entities, DAOs
│   └── repository/            # RadioRepository + mapper/ (Api→Entity→Domain)
├── domain/
│   ├── model/                 # Station (Parcelable), Country, Language, Tag, PlayerState (sealed), Result
│   └── usecase/               # One class per use case (see "Use cases" below)
├── di/                        # AppModule + PlayerModule (@InstallIn SingletonComponent)
├── player/RadioPlayerService  # @AndroidEntryPoint foreground service, ExoPlayer + Media3 MediaSession
├── ui/
│   ├── components/            # StationCard (shared tile)
│   ├── screens/               # HomeScreen, SearchScreen, BrowseScreen, FavoritesScreen, PlayerScreen, StationListScreen
│   ├── viewmodels/            # One ViewModel per screen, all @HiltViewModel AndroidViewModels
│   ├── navigation/            # Screen sealed class + bottomNavItems list
│   └── theme/                 # AIRadioTheme (Material 3)
└── worker/SyncWorker          # @HiltWorker, CoroutineWorker for daily sync
```

### Key cross-layer contracts

- **Station** (`domain/model/DomainModels.kt`) is `@Parcelize` — it travels through Intent extras (`RadioPlayerService.EXTRA_STATION`) and into MediaSession metadata. Never strip the `@Parcelize` annotation.
- **PlayerState** is a sealed class (`Idle`, `Loading`, `Playing(station)`, `Paused(station)`, `Error(message)`). All UI observes this — never read `ExoPlayer.isPlaying` directly from Composables.
- **RadioRepository** is the only class that touches both DAOs and the API. Use cases wrap it (one invoke per method). Mappers live in `data/repository/mapper/Mappers.kt`.
- **`PlayerViewModel` is a process-wide singleton** — it binds to `RadioPlayerService` via `bindService(BIND_AUTO_CREATE)` in `init` and rebinds its `playerState: LiveData<PlayerState>` to the service's `StateFlow`. All five screens receive the *same* instance through `hiltViewModel()` because they share the activity NavHost scope.

### Use cases (`domain/usecase/UseCases.kt`)

`GetPopularStationsUseCase`, `GetRecentlyPlayedStationsUseCase`, `GetStationsByCountryUseCase`, `GetAllCountriesUseCase`, `GetAllLanguagesUseCase`, `GetPopularTagsUseCase`, `GetStationsByLanguageUseCase`, `GetStationsByTagUseCase`, `SearchStationsUseCase`, `GetFavoriteStationsUseCase`, `IsFavoriteUseCase`, `ToggleFavoriteUseCase`, `AddToRecentlyPlayedUseCase`, `SyncDataUseCase`, `GetStationByIdUseCase`. Each is a thin wrapper over `RadioRepository` — add new ones here rather than injecting the repository directly into ViewModels.

### Player pipeline

`PlayerViewModel.playStation()` builds an `ACTION_PLAY` Intent (`RadioPlayerService.newPlayIntent`) and calls `startForegroundService`. The service:

1. Pushes `PlayerState.Loading`, calls `exoPlayer.setMediaItem + prepare + playWhenReady`.
2. The `Player.Listener` (registered in `onCreate`) translates `STATE_BUFFERING → STATE_READY → STATE_ENDED` into `PlayerState` updates and into `startForeground(NOTIFICATION_ID, …)` / `stopForeground`.
3. Notification uses **legacy `androidx.media.app.NotificationCompat.MediaStyle`** (declared via `implementation("androidx.media:media:1.7.0")`) alongside the Media3 session — this is intentional, don't "fix" the import.
4. The service is also a `MediaBrowserService` and registers `MediaButtonReceiver` per the manifest.

`PlayerViewModel.pause()` / `stop()` send `ACTION_PAUSE` / `ACTION_STOP` intents via `startService` (the service is already running). `onCleared` unbinds the service; the service runs in the background until `stopSelf()`.

### Data sync

- `AIRadioApp.onCreate()` calls `SyncWorker.schedule(this)` on every cold start — this is the single source of truth for sync scheduling; `HomeViewModel` no longer schedules.
- `SyncWorker.schedule()` enqueues two requests:
  - A daily `PeriodicWorkRequest` under `WORK_NAME = "SyncWorker"` with `ExistingPeriodicWorkPolicy.KEEP` and a `NetworkType.CONNECTED` constraint.
  - A `OneTimeWorkRequest` under `INITIAL_WORK_NAME = "SyncWorker_Initial"` with `ExistingWorkPolicy.REPLACE` and the same constraint. WorkManager periodic workers wait ≥15 min before their first execution, so this one-time guarantees Room is populated on cold start.
- `SyncWorker.doWork` logs to the `SyncWorker` tag, calls `radioRepository.syncAllData()`, which fetches stations/countries/languages/tags via Retrofit and replaces each table (`clearAll*` then `insert*`). Returns `Result.retry()` on exception.
- `RadioRepository.addToRecentlyPlayed` also runs a 30-day cleanup pass via `recentlyPlayedDao.cleanupOldRecentlyPlayed`.

### WorkManager bootstrap gotcha

`AndroidManifest.xml`'s `InitializationProvider` must declare the WorkManagerInitializer meta-data with `tools:node="remove"` (not just `tools:node="merge"` on the provider). Without `tools:node="remove"`, the default `androidx.work.WorkManagerInitializer` runs and initializes WorkManager with a no-op configuration before `AIRadioApp.getWorkManagerConfiguration()` is ever called — `HiltWorkerFactory` never gets installed, and `@HiltWorker` classes like `SyncWorker` fail with `NoSuchMethodException: SyncWorker.<init>(Context, WorkerParameters)` because the default factory uses reflection.

### Navigation (`ui/navigation/Navigation.kt`)

Bottom bar: `Home` → `Search` → `Browse` → `Favorites` (defined as `bottomNavItems`). Detail screens: `Player` (`player/{stationId}`) and `StationList` (`station_list/{type}/{query}`, where `type ∈ {country,language,tag,recent}`). The bottom bar is hidden on detail screens (`showBottomBar` check in `MainActivity.kt`). Use `Screen.Player.createRoute(id)` and `Screen.StationList.createRoute(type, query)` — don't hand-build routes.

### Hilt graph

- `AppModule` (SingletonComponent): `Gson` (lenient), `OkHttpClient` (with BASIC `HttpLoggingInterceptor`), `Retrofit → RadioBrowserApi`, `Room → AppDatabase`, all six DAOs, `RadioRepository`.
- `PlayerModule` (SingletonComponent): `ExoPlayer` and `MediaSession` — both `@Singleton`, so they live as long as the process and are injected straight into the `@AndroidEntryPoint RadioPlayerService`.
- `AIRadioApp` implements `Configuration.Provider` and overrides `getWorkManagerConfiguration` to wire `HiltWorkerFactory` into WorkManager (required by `SyncWorker`'s `@HiltWorker`).

## Common Tasks

- **Add a new screen**: register a `Screen` object in `Navigation.kt`, add a `composable(route) { ... }` block in `MainActivity.kt`'s `NavHost`, create a `@HiltViewModel` in `ui/viewmodels/` (extend `AndroidViewModel(application)`), add a use case in `UseCases.kt` if it needs new repository behavior.
- **Add a new use case**: append a class in `domain/usecase/UseCases.kt` taking `RadioRepository` via `@Inject constructor`, with `operator fun invoke(...)` returning the appropriate `Flow`/suspend result.
- **Add a Room column / table**: bump `AppDatabase` `version`, update the `DatabaseEntities.kt` entity, update the DAO, and update the mapper in `data/repository/mapper/Mappers.kt` (and the `Station` domain model — keep `@Parcelize`). If a schema migration is needed, add a `Migration` to `AppModule.provideAppDatabase`.
- **Add an API field**: extend the matching `ApiX` DTO in `data/api/model/ApiModels.kt`, the entity, the domain model, and the mapper in one pass.
- **Add a new sync endpoint**: add the `@GET` to `RadioBrowserApi`, an entity + DAO, and wire the new fetch + replace into `RadioRepository.syncAllData()`.

## API quirks worth knowing

- Base URL is `https://de1.api.radio-browser.info/` in `data/api/RadioBrowserApi.kt`. The bare `api.radio-browser.info` hostname 404s in many networks because no A record is published — pin to a regional server (`de1`, `nl1`, `at1`, etc.) or set up a CNAME mirror.
- Station `limit` default is 2000 (not 10000). 10K-station responses are ~11 MB and slow on slow networks; the UI only displays the top 50 anyway.
- `ApiStation.lastchecktime` is typed as `String` (the API returns `"yyyy-MM-dd HH:mm:ss"`). `Mappers.kt` parses it to `Long` via `SimpleDateFormat` for `StationEntity.lastchecktime` (which is `Long`).
- `OkHttpClient` timeouts in `AppModule.provideOkHttpClient` are bumped to 30s connect / 60s read / 30s write / 120s call — the defaults (10s) are too short for the stations endpoint on slower networks.

## Permissions (AndroidManifest)

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (runtime-requested before playback on Android 13+), `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`. The service declares `android:foregroundServiceType="mediaPlayback"`. `WorkManager` is disabled in the manifest's `InitializationProvider` so `AIRadioApp.getWorkManagerConfiguration` is the sole initializer — do not re-enable the default initializer.

## Local Build Artifacts to Ignore

`build/`, `app/build/`, `.gradle/`, `.idea/`, `crash_log.txt`, `crash_log2.txt`, `crash_buffer.txt`, `verify_home.png`, `.sisyphus/` are all transient and should not be edited or committed (this is currently a non-git working tree).