# AIRadio FOREGROUND_SERVICE_MEDIA_PLAYBACK — Compliance Audit

**Date:** 2026-10-03
**Branch:** `main` @ `347c298`
**Package:** `com.nolansoftware.airadio`
**versionCode:** 4 / **versionName:** 1.3
**targetSdk:** 36 / **compileSdk:** 36 / **minSdk:** 26

---

## 1. Executive Summary

| Item | Status |
|---|---|
| Manifest FGS permission | **PASS** |
| Service `foregroundServiceType="mediaPlayback"` | **PASS** |
| Service bound to Media3 `MediaSession` | **PASS** |
| Service bound to ExoPlayer | **PASS** |
| User-initiated playback only | **PASS** |
| Background-playback WakeLock + WifiLock | **PASS** |
| `startForeground()` with correct FGS type | **PASS** |
| `stopForeground()` + `stopSelf()` lifecycle | **PASS** |
| Media-style notification with Play/Pause/Stop | **PASS** |

**No code changes required for compliance.** The current implementation already satisfies Google Play's `FOREGROUND_SERVICE_MEDIA_PLAYBACK` declaration requirements. One **cosmetic** manifest/service mismatch is documented below but is **not blocking** — the service is still correctly invoked via explicit `Intent`.

---

## 2. Manifest Audit

File: `app/src/main/AndroidManifest.xml`

### 2.1 Permissions

| Permission | Line | Required for | Status |
|---|---|---|---|
| `INTERNET` | 5 | Streaming audio + station metadata | ✅ |
| `FOREGROUND_SERVICE` | 7 | Any FGS use | ✅ |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 8 | mediaPlayback FGS type (API 34+ enforced) | ✅ |
| `POST_NOTIFICATIONS` | 9 | Media playback notification on API 33+ | ✅ |
| `WAKE_LOCK` | 10 | Keep CPU alive during playback | ✅ |

### 2.2 Service declaration

```xml
<service
    android:name=".player.RadioPlayerService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="android.media.browse.MediaBrowserService" />
    </intent-filter>
</service>

<receiver
    android:name="androidx.media3.session.MediaSessionManager$MediaButtonReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.MEDIA_BUTTON" />
    </intent-filter>
</receiver>
```

- ✅ `foregroundServiceType="mediaPlayback"` matches the FGS permission
- ✅ `exported="false"` (private to the app)
- ✅ MediaButtonReceiver declared so system media-button events route to the app's MediaSession
- ⚠️ **Cosmetic mismatch:** intent-filter declares `android.media.browse.MediaBrowserService` action but `RadioPlayerService` extends `Service`, NOT `MediaBrowserService`. This is a no-op intent-filter for `MediaBrowserService` clients (Android's media-browsing UI for cars/auto). The service is correctly started by `PlayerViewModel` via explicit `Intent` — see §3. **Not blocking FGS compliance.**

---

## 3. Launch Chain

Actual call chain (verified by reading source):

```
PlayerScreen UI
  │
  ├─ user taps the play button on a Station
  │
  ▼
PlayerViewModel.playStation(station)
  File: app/src/main/java/com/nolansoftware/airadio/ui/viewmodels/PlayerViewModel.kt:91
  │
  │  val intent = RadioPlayerService.newPlayIntent(getApplication(), station)
  │  getApplication<Application>().startForegroundService(intent)
  │
  ▼
Android Framework
  │
  │  Creates RadioPlayerService instance, calls onStartCommand()
  │
  ▼
RadioPlayerService.onStartCommand(intent, ACTION_PLAY)
  File: app/src/main/java/com/nolansoftware/airadio/player/RadioPlayerService.kt:147
  │
  │  ACTION_PLAY branch → play(station)
  │
  ▼
RadioPlayerService.play(station)
  File: app/src/main/java/com/nolansoftware/airadio/player/RadioPlayerService.kt:166
  │
  │  acquireLocks()       // PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF
  │  promoteToForeground(station)   // ← ServiceCompat.startForeground, see §4
  │  exoPlayer.setMediaItem(MediaItem.fromUri(station.urlResolved))
  │  exoPlayer.prepare()
  │  exoPlayer.playWhenReady = true
  │
  ▼
ExoPlayer → network audio stream → device speaker
  │
  │  STATE_READY → playerListener.onPlaybackStateChanged() → PlayerState.Playing
  │                → promoteToForeground() (re-promote with updated notification action set)
  │
  ▼
RadioPlayerService
  - _playerState: StateFlow<PlayerState> emits Playing(station)
  - PlayerViewModel observes via bound Binder → StateFlow<PlayerState> in ViewModel
  - Compose UI updates to show "Playing" + station info
  - Android system surfaces the MediaStyle notification (Play/Pause/Stop)
```

---

## 4. FGS Promotion Details

File: `app/src/main/java/com/nolansoftware/airadio/player/RadioPlayerService.kt`

```kotlin
private fun promoteToForeground(station: Station) {
    ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,                                       // = 1
        createNotification(station),                            // MediaStyle + Play/Pause/Stop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK  // type matches manifest
        } else {
            0
        }
    )
}
```

- ✅ Uses `ServiceCompat.startForeground` (androidx.core, supports pre-Q via the `0` fallback)
- ✅ `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` passed on API 29+
- ✅ **Promotion happens BEFORE `exoPlayer.prepare()`** — guarantees the 5-second FGS-start deadline (Android 13+) is met even if playback fails fast

---

## 5. Notification Details

```kotlin
return NotificationCompat.Builder(this, CHANNEL_ID)    // "radio_playback_channel"
    .setContentTitle(station.name)                      // e.g., "BBC Radio 1"
    .setContentText(station.country)                    // e.g., "United Kingdom"
    .setSmallIcon(R.drawable.ic_radio)
    .setContentIntent(pendingIntent)                    // → MainActivity
    .setPriority(NotificationCompat.PRIORITY_LOW)
    .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    .setOngoing(true)
    .addAction(playPauseAction)                         // ic_play / ic_pause
    .addAction(stopAction)                              // ic_stop
    .setStyle(
        MediaNotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1)           // show Play/Pause + Stop in compact
    )
    .build()
```

- ✅ Channel ID `radio_playback_channel`, IMPORTANCE_LOW (no sound, no vibration)
- ✅ Real station name + country shown
- ✅ Play/Pause and Stop actions (real PendingIntents to service actions, not stubs)
- ✅ MediaStyle so Android system shows it in media controls / lock screen / Android Auto

---

## 6. Stop / Pause Lifecycle

| User action | Code path | Foreground state | Service state |
|---|---|---|---|
| Tap **Pause** | `pause()` → `exoPlayer.pause()` → `_playerState.value = Paused` → `stopForeground(STOP_FOREGROUND_DETACH)` | Notification removed; service still alive (can resume) | Running |
| Tap **Stop** | `stop()` → `exoPlayer.stop()` → `stopForeground(STOP_FOREGROUND_REMOVE)` → `stopSelf()` | Notification removed; service can be killed | `stopSelf()` called |
| Audio **STATE_ENDED** | Player.Listener → `stopForeground(STOP_FOREGROUND_REMOVE)` → `stopSelf()` | Notification removed | `stopSelf()` called |
| Audio **STATE_IDLE** (after pause) | Player.Listener → `stopForeground(STOP_FOREGROUND_REMOVE)` | Notification removed | Running (idle) |

File references:
- `RadioPlayerService.kt:95` (pause → `STOP_FOREGROUND_DETACH`)
- `RadioPlayerService.kt:101-102` (ended → `STOP_FOREGROUND_REMOVE` + `stopSelf()`)
- `RadioPlayerService.kt:106` (idle → `STOP_FOREGROUND_REMOVE`)
- `RadioPlayerService.kt:271-273` (manual stop → `STOP_FOREGROUND_REMOVE` + `stopSelf()`)

---

## 7. Audio Background Survival

Two locks held throughout playback (released only on pause/stop/service-destroy):

```kotlin
// File: RadioPlayerService.kt:227
wakeLock = PowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIRadio:PlaybackWakeLock")
    .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }  // 4 h safety timeout

// File: RadioPlayerService.kt:241
wifiLock = WifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AIRadio:PlaybackWifiLock")
    .apply { acquire() }
```

- ✅ PARTIAL_WAKE_LOCK keeps CPU running while screen is off
- ✅ WIFI_MODE_FULL_HIGH_PERF prevents WiFi sleep (which would sever the stream)
- ✅ 4-hour timeout prevents battery drain if `releaseLocks()` is ever missed
- ✅ Released in `onDestroy()` as a safety net

---

## 8. MediaSession Integration

```kotlin
@Inject lateinit var mediaSession: MediaSession   // Media3 session, Hilt-provided in PlayerModule
```

- ✅ Media3 `MediaSession` wired into the service (injected by Hilt)
- ✅ `MediaButtonReceiver` declared in manifest (line 52) routes system media-button events
- ✅ Notification uses `MediaNotificationCompat.MediaStyle()` — Android surfaces this in system media controls

---

## 9. User Initiation Audit

| Question | Answer |
|---|---|
| Does anything start playback on app launch? | **NO** — app starts in Idle state; `AIRadioApp.onCreate()` only schedules WorkManager sync. |
| Does anything start playback on device boot? | **NO** — no `BOOT_COMPLETED` receiver; AIRadio has no auto-start. |
| Does anything start playback in the background? | **NO** — `playStation(station)` is only called from `PlayerScreen` when the user taps the play button. |
| Does anything start playback without user consent? | **NO** — the only path to `startForegroundService(ACTION_PLAY)` is through `PlayerViewModel.playStation()`, which is only called from the UI. |

- ✅ Service launch is **always** user-initiated
- ✅ Service is **never** auto-started

---

## 10. Decision: NO MINIMAL FIX REQUIRED

The current implementation already satisfies all Google Play `FOREGROUND_SERVICE_MEDIA_PLAYBACK` requirements:

- ✅ Manifest permissions declared (FGS + FGS_MEDIA_PLAYBACK)
- ✅ Service declares `foregroundServiceType="mediaPlayback"`
- ✅ Service uses `ServiceCompat.startForeground` with the matching FGS type on API 29+
- ✅ Playback is user-initiated (UI button tap → `PlayerViewModel.playStation` → `startForegroundService`)
- ✅ Media-style notification with real controls
- ✅ WakeLock + WifiLock keep the stream alive when screen off / app backgrounded
- ✅ `stopForeground()` + `stopSelf()` correctly end the FGS

The only flagged item is the **cosmetic** `<intent-filter>` mismatch (declares `MediaBrowserService` action but doesn't extend `MediaBrowserService`). This affects Android Auto / media-browser clients only — the service is correctly invoked by the app itself via explicit `Intent`. It does **not** affect FGS compliance.

Proceeding to BUILD → INSTALL → REAL-DEVICE TEST → RECORD.
