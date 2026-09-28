## AI Radio - Android Internet Radio App

## Development Process

The code in this repository was generated with the assistance of AI coding
assistants under human direction. The human author (Nolan, yfli@163.com) is
responsible for architecture design, prompt engineering, code review,
and final selection. This disclosure is provided in the interest of
transparent AI-assisted provenance.

A complete production-ready Internet Radio app for Android built with modern architecture and Jetpack Compose.

### Features

✨ **Core Features**
- 🌍 Browse 10,000+ global radio stations
- 🔍 Search stations by name, country, language, or tags
- ❤️ Favorites management
- 🎵 Background playback with media controls
- 📻 Recently played history
- 📶 Offline support with local database
- 🔄 Daily automatic sync
- 🎨 Material Design 3 UI

### Technologies Used

**Architecture**
- Clean Architecture (Data/Domain/UI layers)
- MVVM pattern
- Repository pattern

**Libraries**
- Jetpack Compose - Modern declarative UI
- Room - Local SQLite database
- Retrofit - API client for Radio Browser API
- Kotlin Coroutines + Flow - Async programming
- ExoPlayer - Audio streaming playback
- Coil - Image loading
- Navigation Compose - Screen navigation
- Hilt - Dependency injection
- WorkManager - Background sync

### API Integration

Uses the public Radio Browser API:
- Base URL: https://api.radio-browser.info
- Endpoints: /stations, /search, /countries, /languages, /tags

### Project Structure

```
app/
├── src/main/java/com/nolansoftware/airadio/
│   ├── data/
│   │   ├── api/                    # Retrofit API interface and models
│   │   ├── database/               # Room database, entities, DAOs
│   │   └── repository/             # Repository implementation and mappers
│   ├── domain/
│   │   ├── model/                  # Domain models
│   │   └── usecase/                # Use cases for business logic
│   ├── di/                         # Hilt dependency injection modules
│   ├── player/                     # ExoPlayer service and playback logic
│   ├── ui/
│   │   ├── components/             # Reusable Compose components
│   │   ├── screens/                # Screen implementations
│   │   ├── viewmodels/             # ViewModels for each screen
│   │   ├── navigation/             # Navigation definitions
│   │   └── theme/                  # Material Design 3 theme
│   ├── worker/                     # WorkManager for background sync
│   ├── AIRadioApp.kt               # Application class
│   └── MainActivity.kt             # Main entry point
└── build.gradle                    # App dependencies
```

### Setup Instructions

1. Open the project in Android Studio Hedgehog (2023.1.1) or later
2. Sync Gradle files to download all dependencies
3. Run the app on an emulator or physical device running Android 8.0 (API 26) or higher

### Permissions Required

- INTERNET - For streaming radio and syncing station data
- ACCESS_NETWORK_STATE - For detecting network availability
- FOREGROUND_SERVICE - For background audio playback
- POST_NOTIFICATIONS - For media control notifications
- WAKE_LOCK - To keep device awake during playback

### Data Sync Strategy

- **First launch**: Full sync of all stations, countries, languages, and tags
- **Subsequent launches**: Automatic daily sync in background
- **Offline mode**: Uses cached data when network is unavailable

### Player Features

- Background playback support
- Media notification with play/pause/stop controls
- ExoPlayer integration for reliable streaming
- Error handling for stream failures
- Support for various audio codecs (MP3, AAC, OGG, etc.)

## License

- AIRadio source code: [Apache License 2.0](LICENSE) — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
- Every `.kt` source file carries `// SPDX-License-Identifier: Apache-2.0` at the top.

### Third-party attribution

- **Radio station metadata** streamed from the [Radio Browser API](https://www.radio-browser.info/)
  is licensed under [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/).
- Embedded open-source libraries are credited in [NOTICE](NOTICE).
