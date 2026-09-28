# Privacy Policy

**Effective:** 2026-09-28
**App:** AIRadio (`com.nolansoftware.airadio`)
**Author:** yfli@163.com

## What data AIRadio stores on your device

- **Favorites list** (radio stations you've tapped the heart on) — stored in a local Room database.
- **Recently-played history** (timestamp + station ID, kept for 30 days then cleaned up by `RadioRepository.addToRecentlyPlayed`).
- **App preferences** (theme override, if any) — stored in SharedPreferences.

## What data AIRadio does NOT collect

- No analytics SDK (no Firebase Analytics, no Crashlytics, no Sentry).
- No crash reporter.
- No usage telemetry.
- No advertising identifiers are read or written.
- No third-party tracking.

## Network usage

- AIRadio fetches radio station metadata from the **Radio Browser API** (`https://de1.api.radio-browser.info/`, no authentication required) at cold-start sync and daily background sync.
- The API endpoint sees your IP address and the User-Agent string that OkHttp sends by default. No identifying information is sent by the app beyond what HTTP itself exposes.
- HTTP requests are logged at `Level.BASIC` in debug builds (URL + status + size); in release builds, logging is silent (`Level.NONE`).

## Backup policy

- Auto-backup to Google Drive is **enabled** for app preferences only.
- **Listening history (favorites + recently-played) is excluded** from both cloud backup and device-transfer backup. Deleting the app data will erase your local favorites; reinstalling won't restore them.
- See `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` for the full backup policy.

## Permissions

- `INTERNET` — to stream audio and fetch station metadata.
- `ACCESS_NETWORK_STATE` — for WorkManager's network-constraint on background sync.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — to keep the radio playing when the screen is off.
- `WAKE_LOCK` — to prevent the device from dozing mid-stream.
- `POST_NOTIFICATIONS` — for the media-style playback notification.
- `RECEIVE_BOOT_COMPLETED` — to restart the playback service after a reboot.

AIRadio does **not** request location, contacts, microphone, camera, or storage permissions.

## Contact

For privacy questions: yfli@163.com
