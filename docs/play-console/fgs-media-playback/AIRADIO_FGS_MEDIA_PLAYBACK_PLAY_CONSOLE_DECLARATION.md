# AIRadio — `FOREGROUND_SERVICE_MEDIA_PLAYBACK` Play Console Declaration

This declaration is required for Google Play Console's "Foreground service permissions" → `FOREGROUND_SERVICE_MEDIA_PLAYBACK` declaration field. Every statement below is grounded in the actual implementation in this codebase.

---

## Function description

AIRadio is an internet radio player. The user opens the app, browses a catalog of radio stations fetched from the public Radio Browser API, picks a station, and taps a play button to start streaming. Once playback starts, AIRadio must continue playing the audio even when the user navigates away from the app, switches to another app, locks the screen, or puts the device in their pocket — this is the core listening experience for any radio app and the entire reason a user opens the app in the first place.

To deliver this, AIRadio runs a foreground service of type `mediaPlayback` while audio is active. The service holds audio focus, manages an ExoPlayer instance, and posts a media-style notification with Play/Pause and Stop controls. The notification is the user's handle for controlling playback and is required by Android's media-session contract for any audio app that wants to play in the background.

---

## Why a foreground service is needed

Audio playback in Android is throttled or killed when the app moves to the background unless the app declares a foreground service with a recognized type. For media playback, the only accepted type is `mediaPlayback`.

If AIRadio did not use a foreground service, the following would happen:

- As soon as the user pressed Home or switched apps, Android would put AIRadio in the cached-app state and tear down its process within seconds.
- The radio stream connection would drop, the user's chosen station would stop playing, and audio would cut out mid-song.
- The Android system media controls (lock screen, Bluetooth headset buttons, Android Auto) would not appear, because the system has no live media session to surface.
- The notification shade would have no entry to tap for resuming or stopping.

A foreground service is therefore necessary for the core function — playing a radio station continuously — not for any auxiliary purpose.

---

## User initiation

Playback is **always** started by a deliberate user action. The path is:

```
User opens AIRadio
   ↓
User taps a station card on the Home screen
   ↓
PlayerViewModel.playStation(station)
   ↓
Intent ACTION_PLAY with the chosen Station as Parcelable extra
   ↓
RadioPlayerService.onStartCommand(ACTION_PLAY)
```

There is no other entry point. The service is never started by:

- App launch — `AIRadioApp.onCreate()` only schedules WorkManager for data sync, not playback.
- Device boot — there is no `BOOT_COMPLETED` receiver.
- A scheduled alarm — none configured.
- A push notification — AIRadio has no FCM/remote-notification integration.
- An external intent — the service is `exported="false"` and the action strings (`com.nolansoftware.airadio.action.PLAY/PAUSE/STOP`) are namespaced under the app's package, so only AIRadio's own code can dispatch them.

The user must explicitly choose a station and tap play. Every playback session starts because the user said so.

---

## User perceptibility

While the foreground service is active, AIRadio posts a persistent notification on the system status bar. The notification uses `MediaStyle`, which means it:

- Appears in the notification shade under the channel name **"Radio Playback"**.
- Is promoted to the **system media controls** (the media notification shown when the user pulls down the quick-settings panel, on the lock screen if the device supports it, and in Android Auto).
- Carries a content title set to the **station name** (e.g., "Radio Paradise Main Mix (EU) 320k AAC") and a content text set to the station's country.
- Provides three real, tappable actions: **Play/Pause** and **Stop**, each backed by an explicit `PendingIntent` that delivers `ACTION_PLAY/PAUSE/STOP` to the service.

The notification's category is `CATEGORY_TRANSPORT`, the visibility is `VISIBILITY_PUBLIC` (so it appears on the lock screen), and the importance is `IMPORTANCE_LOW` (no sound, no vibration — a radio stream does not need to interrupt the user). The user can see at a glance what is playing and can pause or stop without opening AIRadio.

---

## User termination

The user can stop playback in two ways:

1. **In-app**: tap the **Stop** button on the player screen. This dispatches `ACTION_STOP` to the service. The service then calls `stopForeground(STOP_FOREGROUND_REMOVE)` to dismiss the notification and `stopSelf()` to allow Android to destroy the process. Audio focus is released.
2. **System media controls**: tap **Stop** in the media-style notification (shade, lock screen, or Android Auto). This dispatches the same `ACTION_STOP` intent via the notification's PendingIntent.

In addition, **Pause** is available in both surfaces and stops audio without ending the foreground service — the user can resume by tapping Play. Audio playback that hits the end of its source (e.g., a stream that goes silent) also triggers the same teardown path automatically via ExoPlayer's `STATE_ENDED` callback.

The user always has a way to stop playback, and stopping always fully tears down the foreground service.

---

## Technical reference (for reviewer's convenience)

| Item | Value |
|---|---|
| Service class | `com.nolansoftware.airadio.player.RadioPlayerService` |
| Service extends | `android.app.Service` + `androidx.lifecycle.LifecycleOwner` |
| Foreground type | `mediaPlayback` (`FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`) |
| Permissions | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `INTERNET` |
| Media stack | AndroidX Media3 1.5.1 (ExoPlayer + MediaSession) |
| Audio focus | `AUDIO_CONTENT_TYPE_MUSIC` + `USAGE_MEDIA`, focus gain type `GAIN` |
| WakeLock | `PARTIAL_WAKE_LOCK` (4-hour safety timeout) + `WIFI_MODE_FULL_HIGH_PERF` |
| Notification | `MediaStyle`, `CATEGORY_TRANSPORT`, `VISIBILITY_PUBLIC`, channel id `radio_playback_channel`, importance `LOW` |
| Notification actions | Play/Pause + Stop (real PendingIntents to service actions) |
| Start path | `PlayerViewModel.playStation()` → `startForegroundService(ACTION_PLAY)` |
| Stop path | `ACTION_STOP` → `stopForeground(REMOVE)` + `stopSelf()` |

For full source references see `AIRADIO_FGS_MEDIA_PLAYBACK_AUDIT.md` §3-§8.
