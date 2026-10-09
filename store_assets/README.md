# Google Play Store assets — AIRadio 1.3

Generated 2026-10-03 from the live APK on a BlueStacks-backed Android 13 emulator
(720×1280 → upscaled to 1080×1920 for Play Store).

All files satisfy Google Play's [Graphic assets, screenshots, & video policy](https://support.google.com/googleplay/android-developer/answer/9866151).

## Files

| File | Size | Dimensions | Format | Bytes | Play Store field |
|---|---:|---:|---|---:|---|
| `app_icon_512.png` | 41 KB | 512×512 | PNG | 42,135 | **App icon** |
| `feature_graphic_1024x500.png` | 87 KB | 1024×500 | PNG | 89,548 | **Feature graphic** (used in "Edit your feature graphic" → featured-app slots) |
| `screenshot_01_home.png` | 510 KB | 1080×1920 | PNG | 521,674 | Phone screenshot #1 — Home (Popular Stations list) |
| `screenshot_02_search.png` | 89 KB | 1080×1920 | PNG | 90,818 | Phone screenshot #2 — Search tab (empty) |
| `screenshot_03_search_results.png` | 953 KB | 1080×1920 | PNG | 976,582 | Phone screenshot #3 — Search results for "jazz" |
| `screenshot_04_browse.png` | 159 KB | 1080×1920 | PNG | 163,205 | Phone screenshot #4 — Browse tab (categories grid) |
| `screenshot_05_player.png` | 232 KB | 1080×1920 | PNG | 238,400 | Phone screenshot #5 — Player (now playing) |
| `screenshot_06_favorites.png` | 76 KB | 1080×1920 | PNG | 78,524 | Phone screenshot #6 — Favorites (1 saved station) |
| `screenshot_07_privacy.png` | 351 KB | 1080×1920 | PNG | 359,573 | Phone screenshot #7 — Privacy & settings sheet |

## Spec compliance

### App icon (`app_icon_512.png`)
- ✅ 512×512 px
- ✅ PNG format
- ✅ 41 KB (≤ 1 MB)
- ✅ No transparency (Play Store renders the app's own background)
- ✅ Rounded corners visible (Android adaptive-icon safe zone respected — content fits in center 312×312)
- ✅ Brand-recognizable (radial AI node + 3 broadcast arcs, matches in-app `ic_launcher_foreground`)

### Feature graphic (`feature_graphic_1024x500.png`)
- ✅ 1024×500 px
- ✅ PNG format
- ✅ 87 KB (≤ 15 MB)
- ✅ No text smaller than 22 px (legible on tablet & phone previews)
- ✅ No "device frames" (Google deprecated those for featured placement in 2023)

### Screenshots
- ✅ All 7 are 1080×1920 (within 320–3840 px range, 9:16 aspect ratio)
- ✅ All under 8 MB
- ✅ PNG format
- ✅ No letterboxing / status bar cropping
- ✅ Real screenshots from the release APK (not mockups)
- ✅ Show the app's actual core features:
  1. **Home** — Popular Stations grid (1st impression)
  2. **Search tab** — empty state (shows search affordance)
  3. **Search results** — "jazz" query (shows real catalog)
  4. **Browse** — categories (shows discoverability)
  5. **Player** — Now playing with pause/stop (shows media playback)
  6. **Favorites** — saved station (shows personalization)
  7. **Privacy sheet** — Google UMP / Privacy policy link (shows privacy features)

## Re-generating

```bash
# Icon + feature graphic (no emulator needed)
python3 /tmp/generate_assets.py

# Screenshots (requires connected emulator at 172.28.144.1:5556)
bash /tmp/capture_screenshots.sh   # if you saved it; otherwise re-capture manually
ffmpeg -i in.png -vf "scale=1080:1920:flags=lanczos" out.png
```

## What's NOT in this folder

- **App icon adaptive layers** (`ic_launcher_foreground.xml` etc.) — these live in
  `app/src/main/res/drawable/` and are auto-bundled into the APK. The 512×512 PNG
  here is the *Play Store* icon (what shows on the Play Store listing), not the
  in-app launcher icon (which Android generates from the adaptive layers).
- **Tablet screenshots (1024×1920 or larger)** — optional, the 7 phone shots
  already cover the app's story. Add if you want a tablet slot filled.
- **Promo video** — out of scope here (we did produce the FGS compliance
  video earlier, which is a different asset).
