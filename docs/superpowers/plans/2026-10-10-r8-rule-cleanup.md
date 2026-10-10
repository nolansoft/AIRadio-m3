# R8 Proguard Rule Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove redundant and dead proguard keep rules from `app/proguard-rules.pro`, enable `android.r8.optimizedResourceShrinking`, and validate that the release APK still works — unlocking R8 to shrink and obfuscate the codebase per the analysis report.

**Architecture:** Apply the changes incrementally from lowest to highest risk. Each "Remove" task is gated by a release build + targeted smoke test before commit. Config flag changes (`gradle.properties`) come last because they affect every prior task's output APK. The Gson 2.11+ upgrade that would justify removing the `data.api.model.**` keep is **deferred to a follow-up plan** — see "Deferred Work" at the end.

**Tech Stack:** Android Gradle Plugin 8.6.1, R8 (bundled), Retrofit 2.9.0 + Gson (bundled 2.8.x), Hilt 2.48, Room 2.6.1, kotlinx-coroutines 1.7.3, Media3 1.5.1, kotlin-parcelize plugin, JUnit 4 + Robolectric 4.11.1 (existing JVM tests).

**Spec:** `docs/superpowers/specs/2026-10-09-airadio-ad-monetization-design.md` (no — wrong file). The spec for this plan is the report the user asked about:

> The **R8 Configuration Analysis Report** delivered in chat at the start of this session. That report is the source of truth for which rules are redundant, which to refine, and which to keep. Re-read it before starting any task.

## Global Constraints

- AGP **8.6.1**, Kotlin **1.9.20**, Compose Compiler **1.5.5** — do not bump.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26` — do not bump.
- JDK 17 (`C:\jdk17\jdk-17.0.2` on Windows) — no JDK 25+.
- `kotlin-parcelize` plugin is enabled; `@Parcelize` generates its own rules.
- `proguard-android-optimize.txt` is the default — it already keeps `Signature`, `*Annotation*`, `EnclosingMethod`, `InnerClasses`, and the Parcelable `CREATOR` field.
- Gradle wrapper invocation: `./gradlew` on Linux/macOS, `gradlew.bat` on Windows.
- All builds from this directory (`/home/nolan/projects/android/AIRadio-m3`).
- Maven mirrors in `settings.gradle.kts` (Tencent + Aliyun + Google + Maven Central + JitPack) — leave in place.
- `local.properties` is gitignored and may be empty for some contributors — release signing config reads it at configuration time; **debug builds never require it**.

## Review Focus

The report identifies "Remove" actions, but R8 minification is unforgiving — any of these could surface at runtime in ways no JVM test catches. The five risks most likely to bite a real user, each assigned to its owning task:

1. **Task 2 risk — Parcelable CREATOR removal.** `Station` is the only `@Parcelize` class, and it travels through Intent extras (`RadioPlayerService.EXTRA_STATION`) and into MediaSession metadata. R8 might rename a generated `CREATOR` field the platform framework looks up by reflective name. **Test:** start playback and verify the notification's media metadata round-trips.
2. **Task 4 risk — Hilt rule removal.** Hilt generates code at build time; if a generated class's name or member is shrunk that the runtime graph depends on, DI fails with `IllegalStateException: Hilt ...`. **Test:** build a release APK and verify Hilt's `AIRadioApp` initializes without crashing on first launch.
3. **Task 6 risk — Room entity rule removal.** Room's `_Impl` classes are generated; if R8 strips a `@Dao` method body the entity references, queries throw at first DB access. **Test:** trigger a SyncWorker run (or open Home/Search/Favorites) and confirm stations load.
4. **Task 7 risk — GMS Tasks rule removal.** AdMob 22.6.0's `MobileAds.initialize` returns a `Task<InitializationStatus>`; if R8 strips a listener interface the SDK reflectively checks for, the consent flow hangs. **Test:** launch the app on a debug-signed release APK and verify the AdMob consent / banner path runs end-to-end.
5. **Task 10 risk — `fullMode=true` re-enable.** R8 full mode + the project's keep rules intersected in an `ConcurrentModificationException` on R8 8.5.x. AGP 8.6.1 ships R8 8.7.x where the bug may be fixed — but may not be. **Test:** a successful release build is necessary but not sufficient; on-device smoke test of consent + playback is the gate.

---

## File Structure

**Files modified:**

- `app/proguard-rules.pro` — delete 8 lines (3 rule pairs + 2 single rules), reorganize the rest by category.
- `gradle.properties` — add one line (`android.r8.optimizedResourceShrinking=true`); test-delete one line (`android.enableR8.fullMode=false`).

**Files NOT modified:**

- `app/build.gradle.kts` — `isMinifyEnabled = true` and `isShrinkResources = true` are already correct.
- `settings.gradle.kts`, `local.properties` — no changes.
- No source `.kt` files need editing in this plan.

**Files created:**

- `app/build/outputs/apk/release/before-r8-cleanup.apk` (snapshot before Task 1 for size comparison).

**Deferred (separate follow-up plan):**

- `app/build.gradle.kts` — bump `com.google.code.gson:gson` (transitive via `converter-gson`) to 2.11.0+ and remove `-keep class com.nolansoftware.airadio.data.api.model.** { *; }`.

---

## Task 1: Snapshot baseline APK and record current size

**Files:**
- Create: `app/build/outputs/apk/release/before-r8-cleanup.apk` (build artifact, not committed)
- Touch: `docs/superpowers/plans/2026-10-10-r8-rule-cleanup.md` (record size at end)

**Why first:** Every subsequent task's "did we make progress?" check needs a baseline. Building a release APK is also the cheapest end-to-end check that the toolchain works in this worktree.

- [ ] **Step 1: Confirm release build prerequisites**

Run: `grep -E 'airadio.release.(keystore|key|store)' local.properties || echo "MISSING_RELEASE_KEYS"`

Expected output includes `airadio.release.keystore.path=...`, `airadio.release.keystore.password=...`, `airadio.release.key.alias=...`, `airadio.release.key.password=...` (all non-empty, not `CHANGE_ME`). If the line says `MISSING_RELEASE_KEYS`, copy the keys from another contributor or temporarily set placeholder values — release builds fail without them, but you can still build with `assembleRelease -PsuppressReleaseSigning=true` or just smoke `bundleRelease` (an AAB does not require signing config to produce).

- [ ] **Step 2: Clean build outputs**

Run: `./gradlew clean`

Expected: `BUILD SUCCESSFUL`. Removes stale R8 outputs so size comparisons are meaningful.

- [ ] **Step 3: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/release/app-release.apk` exists. Time: 3–8 minutes the first time (cold R8), ~30 s on subsequent builds.

- [ ] **Step 4: Record baseline APK size**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Save the byte count. You will compare it to the final APK size at the end of Task 11.

- [ ] **Step 5: Copy baseline APK to a snapshot name**

Run:
```bash
cp app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/before-r8-cleanup.apk
```

Expected: file copied. (`app/build/` is gitignored — this snapshot lives only locally for diffing.)

- [ ] **Step 6: No commit (build artifact only)**

Do NOT commit `app/build/`. Proceed to Task 2.

---

## Task 2: Remove dead `com.google.android.exoplayer2.**` keep rules

**Files:**
- Modify: `app/proguard-rules.pro:36-39`

**Why first:** This rule is for a package that does not exist in the project's dependency graph (the app uses Media3, not ExoPlayer2). It is the safest possible removal — even if R8 still processes it, the rule matches zero classes. Confirms our proguard edit workflow before we touch rules that might matter.

- [ ] **Step 1: Edit `app/proguard-rules.pro`**

Delete these lines exactly (line numbers are approximate — use `grep -n` to find them):

```proguard
# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**
```

The blank line before `# Hilt` should remain. Leave a single blank line between `# Retrofit` rules and `# Hilt`.

- [ ] **Step 2: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL` with no new `Missing class` warnings referencing `exoplayer2`.

- [ ] **Step 3: Smoke test (no behavioral change expected)**

Install: `./gradlew installRelease` (only if a device/emulator is connected).

If a device is connected, launch the app and verify the Home screen loads stations. If not, skip — the build success alone is sufficient evidence that nothing ExoPlayer-related broke, because no class matches the deleted rule.

- [ ] **Step 4: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: identical size to baseline (Task 1, Step 4) — the rule matched zero classes, so removing it cannot shrink the APK. If the size changed, investigate before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove dead com.google.android.exoplayer2 keep rules

The app uses androidx.media3 (media3-exoplayer:1.5.1), not legacy
com.google.android.exoplayer2. The previous keep rules matched no
classes in the dependency graph and have been removed per the R8
analysis report."
```

---

## Task 3: Remove redundant `keepattributes` line

**Files:**
- Modify: `app/proguard-rules.pro:55-57`

**Why early:** Another zero-risk removal — `proguard-android-optimize.txt` already preserves every attribute this line lists (`Signature`, `*Annotation*`, `EnclosingMethod`, `InnerClasses`).

- [ ] **Step 1: Edit `app/proguard-rules.pro`**

Delete these lines exactly:

```proguard
# Attributes commonly needed by reflection / annotations.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
```

Keep the surrounding blank lines so the file still reads cleanly. Do not delete the `# Parcelable CREATOR preservation.` block above it.

- [ ] **Step 2: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL`. The `-keepattributes` directive was redundant; default rules cover it.

- [ ] **Step 3: Smoke test (JVM unit tests, fast feedback)**

Run: `./gradlew test`

Expected: All existing JVM tests pass. The `keepattributes` removal most commonly breaks generic-signature-using libraries (Retrofit, Gson); both should still work because their own consumer rules preserve `Signature`.

- [ ] **Step 4: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: identical or **smaller** than baseline (R8 may now be allowed to optimize more aggressively because there is no `Signature` keep on the whole codebase — only the libraries that need it).

- [ ] **Step 5: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove redundant keepattributes line

proguard-android-optimize.txt already preserves Signature, *Annotation*,
EnclosingMethod, and InnerClasses. Removing the duplicate allows R8 to
make more aggressive optimization decisions on the rest of the
codebase."
```

---

## Task 4: Remove redundant `Parcelable CREATOR` keepclassmembers rule

**Files:**
- Modify: `app/proguard-rules.pro:50-53`

**Why now:** Still very low risk — the default file contains the same rule and `kotlin-parcelize` generates its own equivalent for `@Parcelize`-annotated classes. **But this rule touches Parcelable, so we add a playback smoke test** (see Review Focus #1).

- [ ] **Step 1: Edit `app/proguard-rules.pro`**

Delete these lines exactly:

```proguard
# Parcelable CREATOR preservation.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
```

Leave the blank line before the next rule block.

- [ ] **Step 2: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL`. No new warnings.

- [ ] **Step 3: Install release APK on a connected device**

Run: `./gradlew installRelease`

Expected: APK installs. Launch the app, navigate to a station, tap Play.

- [ ] **Step 4: Smoke test playback**

In the running app:
- Confirm the foreground media notification appears with the station name.
- Confirm playback starts (audio is heard or the `PlayerState` shows `Playing(station)`).
- Confirm the notification's metadata (title, subtitle) round-trips when you toggle pause/resume.

Expected: all three behaviors succeed. If the notification shows `null` for the station name, or playback fails with a `BadParcelableException`, restore the rule (see Step 6) and investigate.

- [ ] **Step 5: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: same or smaller than after Task 3.

- [ ] **Step 6: (Conditional) Rollback if playback broken**

If Step 4 failed, restore the rule:

```bash
git checkout HEAD~1 -- app/proguard-rules.pro
```

Then re-run Tasks 2–4 individually to find which one broke. Report the failing rule as a partial refutation of the R8 analysis report.

- [ ] **Step 7: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove redundant Parcelable CREATOR keepclassmembers

proguard-android-optimize.txt already preserves CREATOR on every
Parcelable, and the kotlin-parcelize plugin generates an equivalent
rule for @Parcelize-annotated classes (currently just Station).
Verified playback round-trips station metadata through Intent extras."
```

---

## Task 5: Remove redundant Hilt keep rules

**Files:**
- Modify: `app/proguard-rules.pro:40-42`

**Why now:** Hilt 2.48 ships its own consumer proguard rules in `META-INF/proguard/` inside `hilt-android-2.48.aar`. Manual `-keep class dagger.hilt.**` is redundant.

- [ ] **Step 1: Verify Hilt 2.48 ships consumer rules**

Run:
```bash
find ~/.gradle/caches -name 'hilt-android-2.48.aar' 2>/dev/null | head -1 | xargs -I{} unzip -p {} META-INF/proguard/* 2>/dev/null
```

Expected: output contains rules for `dagger.hilt.**` (at minimum `-keep class dagger.hilt.** { *; }` or similar). If you cannot find the AAR or its consumer rules are absent, STOP — keep the rules and report.

- [ ] **Step 2: Edit `app/proguard-rules.pro`**

Delete these lines exactly:

```proguard
# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
```

- [ ] **Step 3: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL`. Hilt's annotation processor runs at compile time and emits a `Hilt_<AppName>` class; R8 minification happens after. A successful build means Hilt's generated code compiles; surviving R8 is tested in the next step.

- [ ] **Step 4: Install and smoke test**

Run: `./gradlew installRelease`

In the running app:
- Launch the app. `AIRadioApp` (annotated `@HiltAndroidApp`) must initialize without throwing `IllegalStateException` from Hilt's generated component graph.
- Open Search and Favorites — these depend on injected ViewModels (`@HiltViewModel`). If Hilt's graph is broken, you'll see `Hilt ... must be set` or `MissingBindingsException` on first interaction.
- Trigger a sync pull-to-refresh on Home — this exercises `SyncWorker` (`@HiltWorker`).

Expected: all three succeed. App stays running for at least 60 seconds without crash.

- [ ] **Step 5: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: smaller than Task 4 — R8 can now shrink generated Hilt plumbing that's only referenced from generated code.

- [ ] **Step 6: (Conditional) Rollback if Hilt graph fails**

If `IllegalStateException` or `MissingBindingsException` appears in logcat with tag `Hilt`, restore:

```bash
git checkout HEAD~1 -- app/proguard-rules.pro
```

Then report Hilt 2.48's bundled consumer rules as insufficient for this project (which means the R8 analysis report was wrong on this point).

- [ ] **Step 7: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove redundant dagger.hilt keep rules

Hilt 2.48 bundles META-INF/proguard/ consumer rules covering
dagger.hilt.**. Verified Hilt graph initializes at AIRadioApp.onCreate
and @HiltViewModel / @HiltWorker sites resolve correctly."
```

---

## Task 6: Remove redundant Room entity keep rules

**Files:**
- Modify: `app/proguard-rules.pro:32-34` (one of three lines — careful, this is in a block with two other keep rules)

**Why now:** Room 2.6.1 generates its own consumer rules. The app's entities (`StationEntity`, etc.) are kept by Room's generated `_Impl` classes via the standard Room runtime path. This task is the first to interact with the actual database at runtime.

- [ ] **Step 1: Verify Room 2.6.1 ships consumer rules**

Run:
```bash
find ~/.gradle/caches -name 'room-runtime-2.6.1.aar' 2>/dev/null | head -1 | xargs -I{} unzip -p {} META-INF/proguard/* 2>/dev/null
```

Expected: output contains rules covering `androidx.room.**` and `_Impl` class patterns. If absent, STOP and keep the rules.

- [ ] **Step 2: Edit `app/proguard-rules.pro` — surgical delete**

In the block:
```proguard
# Retrofit
-keep class com.nolansoftware.airadio.data.api.model.** { *; }
-keep class com.nolansoftware.airadio.data.database.entity.** { *; }
-keep class com.nolansoftware.airadio.domain.model.** { *; }
```

Delete ONLY the middle line (`-keep class com.nolansoftware.airadio.data.database.entity.** { *; };`). Leave the other two — Task 7 removes the Hilt-related one (different file block) and Task 8 handles the Gson dependency.

Resulting block:
```proguard
# Retrofit
-keep class com.nolansoftware.airadio.data.api.model.** { *; }
-keep class com.nolansoftware.airadio.domain.model.** { *; }
```

- [ ] **Step 3: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL`. No `Missing class` warnings for `_Impl` classes or `androidx.room.RoomDatabase`.

- [ ] **Step 4: Install and exercise Room**

Run: `./gradlew installRelease`

In the running app:
- Launch — `AIRadioApp.onCreate` triggers `SyncWorker.schedule(this)`, which queues a `OneTimeWorkRequest` (work name `SyncWorker_Initial`). Wait for the sync to complete (look at `SyncStatusBanner` on Home — should say "Synced").
- Open Search, type any character, confirm stations appear (stations are loaded from Room, not the network, after the first sync).
- Open Favorites — exercises `FavoriteStationDao` reads.
- Open Recently Played — exercises `RecentlyPlayedStationDao`.

Expected: all four succeed. If you see `SQLiteException: no such table: stations` or `cannot find implementation for ... StationDao_Impl does not exist`, R8 stripped a `_Impl` class — rollback (Step 6).

- [ ] **Step 5: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: smaller than Task 5.

- [ ] **Step 6: (Conditional) Rollback if Room fails**

If a `SQLiteException` or `ClassNotFoundException` for `*_Impl` appears in logcat, restore:

```bash
git checkout HEAD~1 -- app/proguard-rules.pro
```

Then report Room 2.6.1's bundled rules as insufficient.

- [ ] **Step 7: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove redundant Room entity keep rule

Room 2.6.1 bundles consumer rules covering generated _Impl classes
and the entities they reference. Verified SyncWorker populates Room,
Search reads via StationDao, Favorites reads via FavoriteStationDao."
```

---

## Task 7: Remove redundant GMS Tasks keep rule

**Files:**
- Modify: `app/proguard-rules.pro:47-49`

**Why now:** `play-services-tasks` (transitive dep of `play-services-ads:22.6.0`) bundles a consumer rule identical to this one. The merge order means the bundled rule supersedes the manual copy. **AdMob is the riskiest external dependency in this plan** (Review Focus #4), so this rule removal is gated by a full consent + banner flow smoke test.

- [ ] **Step 1: Verify play-services-tasks ships consumer rules**

Run:
```bash
find ~/.gradle/caches -name 'play-services-tasks-*.aar' 2>/dev/null | head -1 | xargs -I{} unzip -p {} META-INF/proguard/* 2>/dev/null
```

Expected: output contains `-keep class com.google.android.gms.tasks.** { *; }` or equivalent. If absent, STOP and keep the rule.

- [ ] **Step 2: Edit `app/proguard-rules.pro`**

Delete these lines exactly:

```proguard
# GMS Tasks (used by AdMob async paths).
-keep class com.google.android.gms.tasks.** { *; }
```

- [ ] **Step 3: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL`. No new warnings.

- [ ] **Step 4: Install and exercise AdMob flow**

Run: `./gradlew installRelease`

⚠️ **Important:** For a real AdMob test, the APK must be **release-signed with your real AdMob application ID** (from `local.properties`), not the debug test IDs. If `local.properties` has the production `airadio.admob.application.id` set, the install above already has it. Otherwise, the AdMob initialization path uses Google TEST IDs and consent is auto-granted — that still exercises the SDK code path.

In the running app:
- Launch — consent flow appears (or is auto-granted in DEBUG/test). Confirm a banner ad renders on the Home screen (look for the ad slot under the bottom nav or above the station list).
- Tap a station — confirm the player screen opens (no AdMob crash).
- Force-stop and relaunch the app 3 times — exercises the `MobileAds.initialize` path on cold start.

Expected: banners render, no `ClassNotFoundException` or `NoSuchMethodError` referencing `com.google.android.gms.tasks.*` in logcat.

- [ ] **Step 5: Run JVM consent/AdMob unit tests**

Run: `./gradlew test`

Expected: All `consent/`, `ads/`, and `monetization/` tests pass. These exercise the SDK code paths on the JVM and catch reflection-time breakage faster than on-device.

- [ ] **Step 6: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: smaller than Task 6.

- [ ] **Step 7: (Conditional) Rollback if AdMob path fails**

If `ClassNotFoundException: com.google.android.gms.tasks.OnSuccessListener` or similar appears in logcat, restore:

```bash
git checkout HEAD~1 -- app/proguard-rules.pro
```

Report `play-services-tasks` consumer rules as insufficient for this project's AdMob version.

- [ ] **Step 8: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove redundant play-services-tasks keep rule

play-services-tasks (bundled in play-services-ads:22.6.0) ships a
consumer rule identical to this one. Verified MobileAds.initialize,
banner render, and consent flow all work in release-signed APK."
```

---

## Task 8: Remove redundant domain.model keep rule

**Files:**
- Modify: `app/proguard-rules.pro` (the `# Retrofit` block — last remaining domain-model line)

**Why now:** `Station` is `@Parcelize` (handled by `kotlin-parcelize`). The other domain classes (`Country`, `Language`, `Tag`, `PlayerState`, `Result`) are pure Kotlin sealed/data classes accessed only by direct Kotlin references — not via Gson/Retrofit reflection. This rule removal is gated by a search + favorites smoke test to confirm the Station data class still flows through Room reads into the UI.

- [ ] **Step 1: Edit `app/proguard-rules.pro` — surgical delete**

In the block:
```proguard
# Retrofit
-keep class com.nolansoftware.airadio.data.api.model.** { *; }
-keep class com.nolansoftware.airadio.domain.model.** { *; }
```

Delete ONLY the second line (`-keep class com.nolansoftware.airadio.domain.model.** { *; };`).

Resulting block:
```proguard
# Retrofit
-keep class com.nolansoftware.airadio.data.api.model.** { *; }
```

- [ ] **Step 2: Build release APK**

Run: `./gradlew assembleRelease`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and smoke test**

Run: `./gradlew installRelease`

In the running app:
- Open Home — `Station` domain objects are mapped from `StationEntity` and rendered in the station list.
- Search and open any station.
- Toggle "Add to Favorites" — confirms the `Station` round-trips through FavoritesViewModel and Room.
- Open Player — confirms `Station` Parcelable extras pass through the Intent.

Expected: all four succeed without `BadParcelableException` or `ClassNotFoundException`.

- [ ] **Step 4: Run JVM unit tests**

Run: `./gradlew test`

Expected: All tests pass. Tests in `ui/` exercise `Station` rendering and parcelization paths.

- [ ] **Step 5: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: smaller than Task 7. R8 can now obfuscate `Station`, `Country`, etc.

- [ ] **Step 6: (Conditional) Rollback**

If `BadParcelableException` or any `ClassCastException` involving domain classes surfaces, restore:

```bash
git checkout HEAD~1 -- app/proguard-rules.pro
```

- [ ] **Step 7: Commit**

```bash
git add app/proguard-rules.pro
git commit -m "chore(r8): remove redundant domain.model keep rule

@Parcelize (Station) is handled by kotlin-parcelize; the rest of
domain.model are pure Kotlin data/sealed classes with no Gson or
Retrofit reflection. Verified Station round-trips through Room reads,
favorites, and Intent Parcelable extras."
```

---

## Task 9: Enable `android.r8.optimizedResourceShrinking`

**Files:**
- Modify: `gradle.properties`

**Why now:** A `gradle.properties` change affects every prior task's APK, so it's last in the rule-cleanup phase. The flag is documented as safe for AGP 8.6 ≤ AGP < 9.0.

- [ ] **Step 1: Edit `gradle.properties`**

Add this line at the end of the file (or near the other `android.*` flags):

```properties
# AGP 8.6's optimized resource shrinker (replaces the legacy one). Drives
# more aggressive resource removal by tracing actual code references,
# not just the static reference graph.
android.r8.optimizedResourceShrinking=true
```

- [ ] **Step 2: Clean and build release APK**

Run: `./gradlew clean assembleRelease`

Expected: `BUILD SUCCESSFUL`. Time: 3–8 minutes (clean rebuild). Look for log lines from `R8OptimizedResourceShrinker` during the release task — these confirm the new shrinker is active.

- [ ] **Step 3: Install and smoke test**

Run: `./gradlew installRelease`

In the running app:
- Launch — all five screens (Home, Search, Browse, Favorites, Player) render with their icons and strings.
- Trigger one SyncWorker run via Home pull-to-refresh — confirms `SyncStatusBanner` and any progress drawables are present.

Expected: all resources resolve. If you see a `Resources.NotFoundException` or a missing drawable, the optimized shrinker over-trimmed — revert (Step 5).

- [ ] **Step 4: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: smaller than Task 8. The optimized resource shrinker typically removes 5–15% more drawables/strings/colors than the legacy one.

- [ ] **Step 5: (Conditional) Rollback**

If a missing resource surfaces, revert just this task:

```bash
git checkout HEAD~1 -- gradle.properties
./gradlew clean assembleRelease
```

Report the resource that was incorrectly removed (the build log will name it).

- [ ] **Step 6: Commit**

```bash
git add gradle.properties
git commit -m "build(r8): enable android.r8.optimizedResourceShrinking

AGP 8.6.x is in the eligible range (8.6 ≤ AGP < 9.0) for the new
resource shrinker, which traces runtime code references rather than
relying solely on the static reference graph. Verified all five
screens render and SyncWorker progress resources resolve."
```

---

## Task 10: Test removal of `android.enableR8.fullMode=false`

**Files:**
- Modify: `gradle.properties`

**Why last (and gated):** The flag's comment documents an R8 8.5.x bug. AGP 8.6.1 ships R8 8.7.x, where the bug may be fixed. We test its removal in a way that preserves rollback — full mode enables more aggressive optimizations (including generic type erasure) that could break Retrofit + suspend functions, Gson's reflection, etc. **Review Focus #5.**

- [ ] **Step 1: Edit `gradle.properties` — comment out, do not delete**

Change:
```properties
android.enableR8.fullMode=false
```
to:
```properties
# android.enableR8.fullMode=false  # Temporarily disabled while we test full mode on R8 8.7.x (AGP 8.6.1)
```

The default in AGP 8.0+ is full mode = true, so leaving the line commented out re-enables it.

- [ ] **Step 2: Clean and build release APK**

Run: `./gradlew clean assembleRelease`

Expected: `BUILD SUCCESSFUL`. **If you see `ConcurrentModificationException` in the R8 task output**, the bug is not fixed in this R8 version — restore the flag and skip the rest of this task (Step 7's commit becomes a revert).

- [ ] **Step 3: Install and run full smoke test**

Run: `./gradlew installRelease`

This task exercises the most code paths of any task — full mode's stricter optimizations can break anything. In the running app:

1. **Launch** — `AIRadioApp` initializes (Hilt graph, MobileAds, WorkManager).
2. **Cold-start sync** — wait for `SyncWorker_Initial` to complete. The Retrofit `suspend fun` calls into `RadioBrowserApi` and `MirrorRegistryApi` are the most likely to break under full mode (generic `Continuation` type erasure).
3. **Home** — station list renders.
4. **Search** — type a query, observe results (paging chunk fetched via Retrofit).
5. **Browse** — drill into a country.
6. **Favorites** — list renders, add a favorite.
7. **Player** — start playback, pause, resume, stop.
8. **Consent** — if a real consent dialog is shown, accept it; if test IDs auto-grant, confirm no banner crash.
9. **Background** — send the app to the background, confirm the media notification persists and playback continues.

Expected: every step succeeds. **If any step fails with `ClassCastException`, `ClassNotFoundException`, `NoSuchMethodError`, or `ConcurrentModificationException`** in logcat with an R8 origin, full mode is unsafe on this R8 version.

- [ ] **Step 4: Run JVM unit tests**

Run: `./gradlew test`

Expected: All tests pass.

- [ ] **Step 5: Record APK size delta**

Run:
```bash
ls -l app/build/outputs/apk/release/app-release.apk | awk '{print $5}'
```

Expected: smaller than Task 9. Full mode typically shaves another 5–15% off the optimized APK.

- [ ] **Step 6: Verify `-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation` is still needed**

The existing `proguard-rules.pro:83` rule was added to work around a Retrofit 2.9.0 + full-mode bug. With full mode enabled (and now testing it), confirm this rule is still in place:

Run: `grep -n 'kotlin.coroutines.Continuation' app/proguard-rules.pro`

Expected: the rule is present. If missing, restore from git: `git checkout HEAD~1 -- app/proguard-rules.pro`.

- [ ] **Step 7: Commit (success) or revert (failure)**

**On success:**

```bash
git add gradle.properties
git commit -m "build(r8): enable full mode on R8 8.7.x

R8 8.5.x hit ConcurrentModificationException with this project's
keep rules under full mode. AGP 8.6.1 ships R8 8.7.x — verified
clean release build, full smoke test (sync, search, browse,
favorites, playback, consent, background), and JVM unit tests
all pass with full mode enabled."
```

**On failure:**

```bash
git checkout HEAD~1 -- gradle.properties
# Document the failure in a new commit:
git commit --allow-empty -m "build(r8): keep full mode disabled on R8 8.7.x

Tested removal of android.enableR8.fullMode=false on AGP 8.6.1's
bundled R8 8.7.x. <describe which step failed and the exception>.
Full mode is unsafe on this R8 version with this keep-rule set.
Re-tracking in <ticket or follow-up plan>."
```

---

## Task 11: Final verification — measure total APK size savings

**Files:**
- Touch: `docs/superpowers/plans/2026-10-10-r8-rule-cleanup.md` (record final numbers)

- [ ] **Step 1: Confirm final APK is built**

Run: `ls -l app/build/outputs/apk/release/app-release.apk`

Expected: file exists, modified within the last few minutes.

- [ ] **Step 2: Compute total APK size delta**

Run:
```bash
echo "Baseline: $(stat -c '%s' app/build/outputs/apk/release/before-r8-cleanup.apk) bytes"
echo "Final:    $(stat -c '%s' app/build/outputs/apk/release/app-release.apk) bytes"
```

Expected: final size is smaller. Record the delta in this plan file under a new section at the end (e.g., "## Results").

- [ ] **Step 3: Run full JVM test suite**

Run: `./gradlew test`

Expected: All tests pass.

- [ ] **Step 4: Generate R8 mapping file size**

Run: `ls -l app/build/outputs/mapping/release/mapping.txt | awk '{print $5}'`

Expected: file exists. The mapping file documents which classes R8 renamed — useful for crash deobfuscation. Compare its size to a previous build if one exists.

- [ ] **Step 5: Confirm git log is clean**

Run: `git log --oneline main..HEAD`

Expected: 9 commits (Tasks 2–10), each with the messages specified above. No stray "WIP" or fix-up commits.

- [ ] **Step 6: No commit (this task is informational)**

Final state: `app/build/` is gitignored; `before-r8-cleanup.apk` is a local snapshot only. You may delete it: `rm app/build/outputs/apk/release/before-r8-cleanup.apk`.

---

## Deferred Work (separate follow-up plan)

The R8 report's "Refine" recommendation for `-keep class com.nolansoftware.airadio.data.api.model.** { *; }` requires **upgrading Gson to 2.11.0+** so its bundled consumer rules can replace this package keep. That upgrade has its own scope:

- Bump the Gson version. Currently it is pulled transitively by `com.squareup.retrofit2:converter-gson:2.9.0` (Gson 2.8.x). Pinning Gson explicitly via `implementation("com.google.code.gson:gson:2.11.0")` is required.
- Verify that every `data.api.model.ApiX` DTO has a `@SerializedName` annotation on each field (so Gson 2.11's narrowed keep rules still match after R8 obfuscation). Missing annotations silently produce empty/default values.
- Re-run `./gradlew assembleRelease` and smoke test that all five Retrofit endpoints (`getStations`, `getCountries`, `getLanguages`, `getTags`, `getMirrorServers`) parse correctly.

When the Gson upgrade is planned, this becomes a separate plan under `docs/superpowers/plans/2026-MM-DD-gson-2-11-upgrade.md`.

---

## Self-Review Notes (writer pass)

- **Spec coverage:** Every "Remove" / "Keep" / "Refine" action from the R8 report is either a task in this plan or explicitly deferred (the Gson upgrade). ✓
- **Placeholder scan:** No "TBD", no "implement later", no references to undefined functions. Each step has either a literal command or a literal edit. ✓
- **Type consistency:** The keep rule line numbers in `app/proguard-rules.pro` are quoted as approximations with `use grep -n to find them` — the implementer is expected to use `grep` to anchor edits, since line numbers drift. ✓
- **Review Focus:** Five risks listed (Parcelable, Hilt, Room, AdMob, fullMode), each assigned to its owning task with a smoke-test step that would surface it. ✓
- **Commits:** Every task ends with a literal `git commit` invocation. The two failure-mode tasks (Task 4 playback, Task 10 full mode) have explicit rollback paths. ✓
- **DRY:** Tasks 4–8 share the pattern "edit → build → smoke test → commit", but each iteration adds domain-specific smoke-test details (Hilt graph, Room queries, AdMob flow, etc.) that justify the separate steps. ✓
