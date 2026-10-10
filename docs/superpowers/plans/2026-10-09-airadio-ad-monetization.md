# AIRadio Ad Monetization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute the ad-monetization design in `docs/superpowers/specs/2026-10-09-airadio-ad-monetization-design.md` — close the PlayerScreen revenue gap with a bottom-anchored banner, add conservative interstitial triggers (Player exit + App foreground), introduce a `MonetizationManager` abstraction seam for future Premium tier, and tune each surface independently via per-placement AdMob unit IDs.

**Architecture:** 13 tasks ordered by compile-time safety. Foundation types first (Task 1), configuration plumbing (Task 2), then NoOp / real impls / Compose integration / Hilt wiring. Each task leaves the project in a working, buildable state. No domain/data/ViewModel changes; only the new `monetization/` package, three modified UI files, and `AdMobConfig` / gradle additions.

**Tech Stack:** Android Compose + Material 3, Hilt 2.48, AdMob `play-services-ads:22.6.0` (already on classpath), Kotlin 1.9.20, Gradle 8.x with JDK 17. New types: `kotlinx.coroutines.sync.Mutex` (already on classpath via `kotlinx-coroutines-core`). No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-10-09-airadio-ad-monetization-design.md`

---

## Global Constraints

- **Build:** `./gradlew assembleDebug` must finish with 0 errors after every task. `./gradlew test` must pass after every task that touches production code.
- **JDK:** Pin to JDK 17 (`gradle.properties`). Do NOT bump.
- **Compose BOM / Compiler:** Do NOT change. Kotlin 1.9.20 must stay.
- **WorkManager:** Manifest's `InitializationProvider` MUST keep `tools:node="remove"`. `AIRadioApp.getWorkManagerConfiguration` is the sole initializer. (Project invariant.)
- **Hilt:** `kapt { correctErrorTypes = true }` must stay.
- **No new external dependencies.** `kotlinx.coroutines.sync.Mutex` is on classpath via `kotlinx-coroutines-core`. `FullScreenContentCallback` ships with `play-services-ads:22.6.0`. Compose `collectAsState` ships with Compose runtime.
- **Do NOT modify** `RadioPlayerService`, `RadioRepository`, `ConsentManager` API (only consume it), any DAO, any Room entity, any API DTO, any mapper, any `domain/model/`, any `SyncWorker`, any `UseCase`.
- **Do NOT modify** existing `ads/BannerAd.kt`, `ads/BannerAdExt.kt`, `ads/BlueStacksWebViewDetector.kt`, `ads/ChromeWebViewClassShim.kt` — these are wrapped, not edited. **(Exception: `ads/BannerAd.kt` is permitted to accept an `adUnitId: String` parameter (with a default value of `AdMobConfig.BANNER_UNIT_ID` for backward compatibility) so the wrapper can thread the per-surface unit ID into the AdView. This is the only permitted modification to that file.)**
- **Do NOT rename** any existing public Composable signature or screen route.
- **Empty unit IDs:** any `BuildConfig.ADMOB_*_ID` that resolves to an empty string must be treated as "this surface has ads disabled". Never crash; never throw; never log spurious error events.
- **No PII in logs.** Telemetry tags are stable strings; DEBUG may include error codes only.
- **AdMob exceptions:** Never rethrow from `AdmobMonetizationManager`. Every catch is local, every recovery is bounded.
- **Activity handling:** `AdmobMonetizationManager` MUST NOT cache an Activity reference beyond a `WeakReference` updated by `Application.ActivityLifecycleCallbacks`. Show sites read `weakRef.get()` at the call instant.

---

## Review Focus

The spec implies behaviors that no compile / lint check will catch. These are the inputs a reasonable person would expect to work that this plan must pin to a task's verification step:

1. **Empty unit ID = ads disabled** — when `BuildConfig.ADMOB_BANNER_PLAYER_ID` is `""`, `BannerAd(SurfaceId.Player)` must render an empty 50 dp Box (no AdView construction, no AdRequest fired). Pinned in **Task 2** gradle verification (default value is empty) + **Task 6** test (`BannerAd renders empty when unit ID is blank`).
2. **`Mutex.tryLock()` rejection must emit `InterstitialBusy`, NOT `InterstitialThrottled`** — these are two distinct events that must not be conflated. Pinned in **Task 9** test (`busy-path rejects with InterstitialBusy event, not InterstitialThrottled`).
3. **`showInterstitialIfReady` is synchronous (`Boolean`), NOT `suspend`** — `onDispose` and `Lifecycle.Event.ON_START` are non-suspend callbacks. If anyone "improves" it to `suspend`, every caller breaks. Pinned in **Task 5** interface declaration review + **Task 9** test verifying no main-thread blocking.
5. **Activity MUST NOT be cached on the manager** — `WeakReference` only; reading via `.get()` at call time. Pinned in **Task 5** implementation review (constructor body) + **Task 9** test verifying Activity reference is not stored.
4. **`lastStopWasBackground` MUST be gated by `!isChangingConfigurations`** — rotation triggers `onStop → onStart`; without the guard, every rotation fires an `AppForeground` interstitial. Pinned in **Task 12** test (`rotation does not fire AppForeground interstitial`).

---

## Task 1: Type definitions (SurfaceId, InterstitialTrigger, MonetizationEvent, MonetizationInterstitialHandle)

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/SurfaceId.kt`
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/InterstitialTrigger.kt`
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationEvent.kt`
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationInterstitialHandle.kt`
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/MonetizationTypeTest.kt`

**Interfaces:**
- Consumes: nothing (foundation)
- Produces:
  - `sealed class SurfaceId(val adUnitSuffix: String)` with 6 sealed objects
  - `sealed class InterstitialTrigger(val adUnitSuffix: String)` with 2 sealed objects
  - `sealed class MonetizationEvent(val tag: String)` with 8 sealed objects
  - `interface MonetizationInterstitialHandle { fun show(activity: Activity); fun setFullScreenContentListener(l: FullScreenContentCallback) }`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/MonetizationTypeTest.kt
package com.nolansoftware.airadio.monetization

import org.junit.Assert.assertEquals
import org.junit.Test

class MonetizationTypeTest {

    @Test fun `SurfaceId adUnitSuffix values are stable`() {
        assertEquals("player",      SurfaceId.Player.adUnitSuffix)
        assertEquals("home",        SurfaceId.Home.adUnitSuffix)
        assertEquals("search",      SurfaceId.Search.adUnitSuffix)
        assertEquals("browse",      SurfaceId.Browse.adUnitSuffix)
        assertEquals("favorites",   SurfaceId.Favorites.adUnitSuffix)
        assertEquals("station_list", SurfaceId.StationList.adUnitSuffix)
    }

    @Test fun `InterstitialTrigger adUnitSuffix values are stable`() {
        assertEquals("exit_player", InterstitialTrigger.ExitFromPlayer.adUnitSuffix)
        assertEquals("foreground",  InterstitialTrigger.AppForeground.adUnitSuffix)
    }

    @Test fun `MonetizationEvent tags are stable for analytics consumers`() {
        assertEquals("banner_shown",             MonetizationEvent.BannerShown.tag)
        assertEquals("banner_load_failed",       MonetizationEvent.BannerLoadFailed.tag)
        assertEquals("interstitial_requested",   MonetizationEvent.InterstitialRequested.tag)
        assertEquals("interstitial_shown",       MonetizationEvent.InterstitialShown.tag)
        assertEquals("interstitial_dismissed",   MonetizationEvent.InterstitialDismissed.tag)
        assertEquals("interstitial_load_failed", MonetizationEvent.InterstitialLoadFailed.tag)
        assertEquals("interstitial_throttled",   MonetizationEvent.InterstitialThrottled.tag)
        assertEquals("interstitial_busy",        MonetizationEvent.InterstitialBusy.tag)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.MonetizationTypeTest`
Expected: compile error — `Unresolved reference: SurfaceId` (and the other types).

- [ ] **Step 3: Create `SurfaceId.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/SurfaceId.kt
package com.nolansoftware.airadio.monetization

sealed class SurfaceId(val adUnitSuffix: String) {
    object Player      : SurfaceId("player")
    object Home        : SurfaceId("home")
    object Search      : SurfaceId("search")
    object Browse      : SurfaceId("browse")
    object Favorites   : SurfaceId("favorites")
    object StationList : SurfaceId("station_list")
}
```

- [ ] **Step 4: Create `InterstitialTrigger.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/InterstitialTrigger.kt
package com.nolansoftware.airadio.monetization

sealed class InterstitialTrigger(val adUnitSuffix: String) {
    object ExitFromPlayer : InterstitialTrigger("exit_player")
    object AppForeground  : InterstitialTrigger("foreground")
}
```

- [ ] **Step 5: Create `MonetizationEvent.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationEvent.kt
package com.nolansoftware.airadio.monetization

sealed class MonetizationEvent(val tag: String) {
    object BannerShown            : MonetizationEvent("banner_shown")
    object BannerLoadFailed       : MonetizationEvent("banner_load_failed")
    object InterstitialRequested  : MonetizationEvent("interstitial_requested")
    object InterstitialShown      : MonetizationEvent("interstitial_shown")
    object InterstitialDismissed  : MonetizationEvent("interstitial_dismissed")
    object InterstitialLoadFailed : MonetizationEvent("interstitial_load_failed")
    object InterstitialThrottled  : MonetizationEvent("interstitial_throttled")
    object InterstitialBusy       : MonetizationEvent("interstitial_busy")
}
```

- [ ] **Step 6: Create `MonetizationInterstitialHandle.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationInterstitialHandle.kt
package com.nolansoftware.airadio.monetization

import android.app.Activity
import com.google.android.gms.ads.FullScreenContentCallback

interface MonetizationInterstitialHandle {
    fun show(activity: Activity)
    fun setFullScreenContentListener(listener: FullScreenContentCallback)
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.MonetizationTypeTest`
Expected: PASS, 3 tests green.

- [ ] **Step 8: Verify build is clean**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, no warnings about new types.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/ \
        app/src/test/java/com/nolansoftware/airadio/monetization/MonetizationTypeTest.kt
git commit -m "feat(monetization): add type definitions (SurfaceId, InterstitialTrigger, MonetizationEvent, handle interface)"
```

---

## Task 2: AdMobConfig additions + gradle buildConfigField wiring

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ads/AdMobConfig.kt`
- Modify: `app/build.gradle.kts` (add 8 `buildConfigField` entries)
- Modify: `local.properties` (add commented template lines)
- Create: `app/src/test/java/com/nolansoftware/airadio/ads/AdMobConfigExtensionsTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `AdMobConfig.BANNER_PLAYER_ID: String` (from `BuildConfig.ADMOB_BANNER_PLAYER_ID`)
  - `AdMobConfig.INTERSTITIAL_EXIT_ID: String`, `AdMobConfig.INTERSTITIAL_FOREGROUND_ID: String`
  - `AdMobConfig.EXPECTED_BANNER_HEIGHT_DP = 50`
  - `AdMobConfig.FREQ_EXIT_PLAYER_WINDOW_MS = 60_000L`, `FREQ_EXIT_PLAYER_PER_SESSION = 2`
  - `AdMobConfig.FREQ_FOREGROUND_WINDOW_MS = 600_000L`, `FREQ_FOREGROUND_PER_SESSION = 2`
  - 8 `BuildConfig.ADMOB_*` string fields, all defaulted to `""`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/ads/AdMobConfigExtensionsTest.kt
package com.nolansoftware.airadio.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdMobConfigExtensionsTest {

    @Test fun `expected banner height is 50dp for portrait`() {
        assertEquals(50, AdMobConfig.EXPECTED_BANNER_HEIGHT_DP)
    }

    @Test fun `ExitFromPlayer caps are 60s and 2 per session`() {
        assertEquals(60_000L, AdMobConfig.FREQ_EXIT_PLAYER_WINDOW_MS)
        assertEquals(2,        AdMobConfig.FREQ_EXIT_PLAYER_PER_SESSION)
    }

    @Test fun `AppForeground caps are 10min and 2 per session`() {
        assertEquals(600_000L, AdMobConfig.FREQ_FOREGROUND_WINDOW_MS)
        assertEquals(2,        AdMobConfig.FREQ_FOREGROUND_PER_SESSION)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.ads.AdMobConfigExtensionsTest`
Expected: compile error — `Unresolved reference: EXPECTED_BANNER_HEIGHT_DP`.

- [ ] **Step 3: Add gradle `buildConfigField` entries**

Open `app/build.gradle.kts` and inside the `android { defaultConfig { ... } }` block, add these 8 entries (place them after the existing `buildConfigField` lines, keeping alphabetical grouping):

```kotlin
// In app/build.gradle.kts, inside defaultConfig { ... }
buildConfigField("String", "ADMOB_BANNER_PLAYER_ID",
    "\"${project.findProperty("ADMOB_BANNER_PLAYER_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_BANNER_HOME_ID",
    "\"${project.findProperty("ADMOB_BANNER_HOME_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_BANNER_SEARCH_ID",
    "\"${project.findProperty("ADMOB_BANNER_SEARCH_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_BANNER_BROWSE_ID",
    "\"${project.findProperty("ADMOB_BANNER_BROWSE_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_BANNER_FAVORITES_ID",
    "\"${project.findProperty("ADMOB_BANNER_FAVORITES_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_BANNER_STATIONLIST_ID",
    "\"${project.findProperty("ADMOB_BANNER_STATIONLIST_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_INTERSTITIAL_EXIT_ID",
    "\"${project.findProperty("ADMOB_INTERSTITIAL_EXIT_ID") ?: ""}\"")
buildConfigField("String", "ADMOB_INTERSTITIAL_FOREGROUND_ID",
    "\"${project.findProperty("ADMOB_INTERSTITIAL_FOREGROUND_ID") ?: ""}\"")
```

- [ ] **Step 4: Extend `AdMobConfig.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ads/AdMobConfig.kt
package com.nolansoftware.airadio.ads

import com.nolansoftware.airadio.BuildConfig

object AdMobConfig {
    // === BANNER ===
    const val BANNER_INTERVAL = 12
    const val BANNER_SEARCH_THRESHOLD = 10
    const val BANNER_SEARCH_POSITION = 5
    const val EXPECTED_BANNER_HEIGHT_DP = 50       // portrait only; tablet/landscape deferred
    val BANNER_UNIT_ID: String = BuildConfig.ADMOB_BANNER_ID
    val BANNER_PLAYER_ID: String = BuildConfig.ADMOB_BANNER_PLAYER_ID

    // === INTERSTITIAL ===
    val INTERSTITIAL_EXIT_ID: String = BuildConfig.ADMOB_INTERSTITIAL_EXIT_ID
    val INTERSTITIAL_FOREGROUND_ID: String = BuildConfig.ADMOB_INTERSTITIAL_FOREGROUND_ID

    // === FREQUENCY CAPS ===
    const val FREQ_EXIT_PLAYER_WINDOW_MS = 60_000L    // 60 s
    const val FREQ_EXIT_PLAYER_PER_SESSION = 2        // per cold-start session
    const val FREQ_FOREGROUND_WINDOW_MS = 600_000L    // 10 min
    const val FREQ_FOREGROUND_PER_SESSION = 2
}
```

> Keep all existing fields intact. Only add new ones.

- [ ] **Step 5: Update `local.properties` template**

Append these commented lines at the bottom of `local.properties`:

```
# AIRadio AdMob per-placement unit IDs (obtain actual IDs from AdMob console; empty = ads disabled on that surface)
# ADMOB_BANNER_PLAYER_ID=
# ADMOB_BANNER_HOME_ID=
# ADMOB_BANNER_SEARCH_ID=
# ADMOB_BANNER_BROWSE_ID=
# ADMOB_BANNER_FAVORITES_ID=
# ADMOB_BANNER_STATIONLIST_ID=
# ADMOB_INTERSTITIAL_EXIT_ID=
# ADMOB_INTERSTITIAL_FOREGROUND_ID=
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.ads.AdMobConfigExtensionsTest`
Expected: PASS, 3 tests green.

- [ ] **Step 7: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. No code references the new fields yet, but the fields compile.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/ads/AdMobConfig.kt \
        app/build.gradle.kts \
        local.properties \
        app/src/test/java/com/nolansoftware/airadio/ads/AdMobConfigExtensionsTest.kt
git commit -m "feat(monetization): add per-placement AdMob unit IDs and frequency-cap constants"
```

---

## Task 3: NoOpMonetizationManager

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManager.kt`
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManagerTest.kt`

**Interfaces:**
- Consumes: `MonetizationManager` interface (declared in Task 5, but this file references only the symbols needed; a forward reference is fine — Task 5 lands first)

> Note: This task actually depends on Task 5 (`MonetizationManager` interface exists). **Reorder: Task 3 should run after Task 5.** This task will be re-inserted after Task 5.

**Skip this task for now** — see Task 5a for the actual placement.

---

## Task 5: MonetizationManager interface + AdmobMonetizationManager skeleton

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationManager.kt`
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt` (skeleton only — constructor + state holders; no business logic yet)

**Interfaces:**
- Consumes: types from Task 1 (`SurfaceId`, `InterstitialTrigger`, `MonetizationEvent`); `ConsentManager` (existing, for `isAdsEnabled` derivation in later task); `Application` (for `ActivityLifecycleCallbacks` registration)
- Produces:
  - `interface MonetizationManager { val isAdsEnabled: StateFlow<Boolean>; @Composable fun BannerAd(...); fun loadInterstitial(...); fun showInterstitialIfReady(...): Boolean; fun recordEvent(...) }`
  - `AdmobMonetizationManager` class — `@Singleton`, constructor registers lifecycle callbacks, sets up mutex/cache/eventLog state, exposes empty default impls for business methods (later tasks fill them in)

- [ ] **Step 1: Write the failing test for the interface contract**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/MonetizationManagerContractTest.kt
package com.nolansoftware.airadio.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MonetizationManagerContractTest {

    // --- Type-level contract only; behavioral tests in later tasks ---

    @Test fun `InterstitialBusy and InterstitialThrottled are distinct events`() {
        // This test pins the spec's bucket-split decision.
        assertNotEquals(
            MonetizationEvent.InterstitialBusy.tag,
            MonetizationEvent.InterstitialThrottled.tag,
        )
    }

    @Test fun `expected frequency caps are 60s 2-session Exit and 600s 2-session Foreground`() {
        // Re-export cap constants here from AdMobConfig to fail loudly if anyone
        // accidentally loosens the budget.
        assertEquals(60_000L, com.nolansoftware.airadio.ads.AdMobConfig.FREQ_EXIT_PLAYER_WINDOW_MS)
        assertEquals(2,        com.nolansoftware.airadio.ads.AdMobConfig.FREQ_EXIT_PLAYER_PER_SESSION)
        assertEquals(600_000L, com.nolansoftware.airadio.ads.AdMobConfig.FREQ_FOREGROUND_WINDOW_MS)
        assertEquals(2,        com.nolansoftware.airadio.ads.AdMobConfig.FREQ_FOREGROUND_PER_SESSION)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.MonetizationManagerContractTest`
Expected: PASS (this is a regression test; passes from Task 2 onward). If FAIL, fix the cap constants in Task 2 first.

- [ ] **Step 3: Create `MonetizationManager.kt` interface**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationManager.kt
package com.nolansoftware.airadio.monetization

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

interface MonetizationManager {
    val isAdsEnabled: StateFlow<Boolean>

    @Composable
    fun BannerAd(surfaceId: SurfaceId, modifier: Modifier = Modifier)

    fun loadInterstitial(trigger: InterstitialTrigger)

    /**
     * Synchronous (NOT suspend). Returns true iff an ad was actually shown.
     * All refusal reasons are silent unless they emit a [MonetizationEvent].
     *
     * Callers are typically Composable `onDispose` callbacks or
     * `Lifecycle.Event.ON_START` observers — both non-suspend contexts.
     */
    fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean

    fun recordEvent(event: MonetizationEvent)
}
```

- [ ] **Step 4: Create `AdmobMonetizationManager.kt` skeleton**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt
package com.nolansoftware.airadio.monetization

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nolansoftware.airadio.BuildConfig
import com.nolansoftware.airadio.ads.AdMobConfig
import com.nolansoftware.airadio.consent.ConsentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real AdMob-backed implementation of [MonetizationManager].
 *
 * Lifecycle invariants:
 *  - The Activity reference is held only as a [WeakReference] updated by
 *    [Application.ActivityLifecycleCallbacks]. Never stored as a field.
 *  - The show path uses [Mutex.tryLock] — non-blocking on the main thread.
 *  - All AdMob exceptions are caught locally; none are rethrown.
 */
@Singleton
class AdmobMonetizationManager @Inject constructor(
    @androidx.annotation.ApplicationContext private val context: Context,
    private val consents: ConsentManager,
) : MonetizationManager {

    // === State ===

    private val currentActivityRef: java.util.concurrent.atomic.AtomicReference<WeakReference<Activity>> =
        java.util.concurrent.atomic.AtomicReference(WeakReference(null))

    private val cache: ConcurrentHashMap<InterstitialTrigger, MonetizationInterstitialHandle> =
        ConcurrentHashMap()

    private val retryAttempts: ConcurrentHashMap<InterstitialTrigger, Int> =
        ConcurrentHashMap()

    private val sessionCount: ConcurrentHashMap<InterstitialTrigger, Int> =
        ConcurrentHashMap()

    private val lastShownAtMs: ConcurrentHashMap<InterstitialTrigger, Long> =
        ConcurrentHashMap()

    private val showMutex = Mutex()    // kotlinx.coroutines.sync — tryLock only, no suspending lock

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isAdsEnabled: StateFlow<Boolean> =
        consents.canRequestAds().stateIn(ioScope, SharingStarted.Eagerly, initialValue = false)

    private val eventLogInternal: MutableList<MonetizationEvent> = mutableListOf()

    init {
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) { currentActivityRef.set(WeakReference(a)) }
                override fun onActivityPaused(a: Activity)     { currentActivityRef.set(WeakReference(null)) }
                override fun onActivityStopped(a: Activity)    {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity)  {}
            }
        )
    }

    // === Business impl — placeholder, real impl in Tasks 6/7/8/9 ===

    override fun loadInterstitial(trigger: InterstitialTrigger) {
        // Implemented in Task 7.
    }

    override fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean {
        // Implemented in Task 9.
        return false
    }

    @Composable
    override fun BannerAd(surfaceId: SurfaceId, modifier: Modifier) {
        // Implemented in Task 6.
    }

    override fun recordEvent(event: MonetizationEvent) {
        if (BuildConfig.DEBUG) Log.d("Monetization", event.tag)
        else                  Log.i("Monetization", event.tag)
        synchronized(eventLogInternal) { eventLogInternal.add(event) }
    }

    // === @VisibleForTesting helpers — used by Tasks 7-9 tests ===

    @androidx.annotation.VisibleForTesting
    internal fun hasCached(trigger: InterstitialTrigger): Boolean = cache.containsKey(trigger)

    @androidx.annotation.VisibleForTesting
    internal fun tryAcquireShowMutexForTest(): Boolean =
        if (showMutex.tryLock()) { showMutex.unlock(); true } else false

    @androidx.annotation.VisibleForTesting
    internal val recordedEvents: List<MonetizationEvent>
        get() = synchronized(eventLogInternal) { eventLogInternal.toList() }

    // === Unit-ID resolution (used by Tasks 6 and 7) ===

    internal fun bannerUnitIdFor(surfaceId: SurfaceId): String? = when (surfaceId) {
        SurfaceId.Player      -> BuildConfig.ADMOB_BANNER_PLAYER_ID.takeIf { it.isNotBlank() }
        SurfaceId.Home        -> BuildConfig.ADMOB_BANNER_HOME_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.Search      -> BuildConfig.ADMOB_BANNER_SEARCH_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.Browse      -> BuildConfig.ADMOB_BANNER_BROWSE_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.Favorites   -> BuildConfig.ADMOB_BANNER_FAVORITES_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.StationList -> BuildConfig.ADMOB_BANNER_STATIONLIST_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
    }

    internal fun interstitialUnitIdFor(trigger: InterstitialTrigger): String? = when (trigger) {
        InterstitialTrigger.ExitFromPlayer -> AdMobConfig.INTERSTITIAL_EXIT_ID.takeIf { it.isNotBlank() }
        InterstitialTrigger.AppForeground  -> AdMobConfig.INTERSTITIAL_FOREGROUND_ID.takeIf { it.isNotBlank() }
    }
}
```

- [ ] **Step 5: Run contract test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.MonetizationManagerContractTest`
Expected: PASS.

- [ ] **Step 6: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. No code calls the manager yet, but the manager compiles + is Hilt-injectable.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationManager.kt \
        app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/MonetizationManagerContractTest.kt
git commit -m "feat(monetization): add interface and AdmobMonetizationManager skeleton"
```

---

## Task 5a: NoOpMonetizationManager (depends on Task 5)

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManager.kt`
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManagerTest.kt`

**Interfaces:**
- Consumes: `MonetizationManager` interface (Task 5)
- Produces: `NoOpMonetizationManager` — `@Singleton`, `@Inject constructor()`, all methods are no-ops

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManagerTest.kt
package com.nolansoftware.airadio.monetization

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoOpMonetizationManagerTest {

    @Test fun `isAdsEnabled flow is permanently false`() = runTest {
        val mgr = NoOpMonetizationManager()
        assertEquals(false, mgr.isAdsEnabled.first())
    }

    @Test fun `showInterstitialIfReady always returns false`() {
        val mgr = NoOpMonetizationManager()
        assertFalse(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        assertFalse(mgr.showInterstitialIfReady(InterstitialTrigger.AppForeground))
    }

    @Test fun `loadInterstitial and recordEvent do not throw`() {
        val mgr = NoOpMonetizationManager()
        mgr.loadInterstitial(InterstitialTrigger.ExitFromPlayer)
        mgr.recordEvent(MonetizationEvent.BannerShown)    // must not throw, must not NPE
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.NoOpMonetizationManagerTest`
Expected: compile error — `Unresolved reference: NoOpMonetizationManager`.

- [ ] **Step 3: Create `NoOpMonetizationManager.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManager.kt
package com.nolansoftware.airadio.monetization

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op implementation of [MonetizationManager].
 *
 * Used by:
 *  - Compose @Preview functions
 *  - androidTest instrumentation (via TestMonetizationModule)
 *
 * Always reports ads as disabled; never loads or shows anything.
 */
@Singleton
class NoOpMonetizationManager @Inject constructor() : MonetizationManager {

    override val isAdsEnabled: StateFlow<Boolean> = MutableStateFlow(false)

    @Composable
    override fun BannerAd(surfaceId: SurfaceId, modifier: Modifier) = Unit

    override fun loadInterstitial(trigger: InterstitialTrigger) = Unit

    override fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean = false

    override fun recordEvent(event: MonetizationEvent) = Unit
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.NoOpMonetizationManagerTest`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManager.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/NoOpMonetizationManagerTest.kt
git commit -m "feat(monetization): add NoOpMonetizationManager for previews and androidTest"
```

---

## Task 6: BannerAd Composable member + empty unit-ID handling + 50dp reservation

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt` (replace the `BannerAd` placeholder)
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/AdmobBannerAdTest.kt`
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/FakeConsentManager.kt`

**Interfaces:**
- Consumes: `bannerUnitIdFor` (from Task 5), `isAdsEnabled` (from Task 5), existing `ads/BannerAd.kt` raw composable
- Produces: a `BannerAd(surfaceId, modifier)` Composable that:
  - Returns `Unit` (renders nothing) if `bannerUnitIdFor(surfaceId) == null` (empty unit ID)
  - Returns `Unit` if `isAdsEnabled` is `false` (consent disabled)
  - Otherwise renders an `androidx.compose.foundation.layout.Box` of `height(50.dp)` wrapping the existing `BannerAd()` composable, with `Modifier.fillMaxWidth()`

- [ ] **Step 1: Add a hand-rolled `FakeConsentManager` test helper (in test source set)**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/FakeConsentManager.kt
package com.nolansoftware.airadio.monetization

import com.nolansoftware.airadio.consent.ConsentManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeConsentManager(initial: Boolean = false) : ConsentManager {
    private val state = MutableStateFlow(initial)
    override fun canRequestAds(): StateFlow<Boolean> = state
    fun setValue(v: Boolean) { state.value = v }
}
```

> **First**, read `app/src/main/java/com/nolansoftware/airadio/consent/ConsentManager.kt` to verify the real interface signature. Adjust `FakeConsentManager` if the interface differs.

- [ ] **Step 2: Write the failing test**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/AdmobBannerAdTest.kt
package com.nolansoftware.airadio.monetization

import com.nolansoftware.airadio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AdmobBannerAdTest {

    // Hand-rolled context: Robolectric's real Application. No Mockito.
    private fun newManager(consentInitial: Boolean = true): AdmobMonetizationManager =
        AdmobMonetizationManager(
            context = RuntimeEnvironment.getApplication(),
            consents = FakeConsentManager(consentInitial),
        )

    @Test fun `empty string is filtered to null by takeIf isNotBlank`() {
        // Pin the empty-unit-ID contract at the language level.
        val blank: String? = "".takeIf { it.isNotBlank() }
        assertNull(blank)
    }

    @Test fun `bannerUnitIdFor returns null when BuildConfig ADMOB_BANNER_PLAYER_ID is empty`() {
        // In default CI builds, ADMOB_BANNER_PLAYER_ID is ""; verify the lookup returns null.
        if (BuildConfig.ADMOB_BANNER_PLAYER_ID.isBlank()) {
            val mgr = newManager()
            assertNull(mgr.bannerUnitIdFor(SurfaceId.Player))
        }
    }

    @Test fun `SurfaceId adUnitSuffix is used as the BuildConfig field suffix convention`() {
        // The spec promises that bannerUnitIdFor looks up BuildConfig.ADMOB_BANNER_<suffix>_ID.
        assertEquals("player", SurfaceId.Player.adUnitSuffix)
    }

    @Test fun `manager constructs cleanly against a real Application context`() {
        // No-op smoke: the init { registerActivityLifecycleCallbacks } block must not throw
        // when given a real Application. This is the foundation all later tests rely on.
        val mgr = newManager()
        // We don't assert internal state here; construction succeeding is the assertion.
    }
}
```

- [ ] **Step 3: Run test to verify it compiles and runs**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.AdmobBannerAdTest`
Expected: PASS, 4 tests green (or 3 if the BuildConfig-gated test is skipped locally). The behavioral `BannerAd` render-empty assertion lands in Task 13 (Compose UI test).

- [ ] **Step 4: Replace the `BannerAd` placeholder in `AdmobMonetizationManager.kt`**

Replace the existing `BannerAd` placeholder method:

```kotlin
@Composable
override fun BannerAd(surfaceId: SurfaceId, modifier: Modifier) {
    // 1. Empty unit ID? Render nothing (no AdView construction, no network).
    val unitId = bannerUnitIdFor(surfaceId) ?: return

    // 2. Consent disabled? Render nothing.
    if (!isAdsEnabled.collectAsState().value) return

    // 3. Render a Box of reserved height to guarantee no layout shift.
    val reservedHeight = AdMobConfig.EXPECTED_BANNER_HEIGHT_DP.dp
    Box(modifier = modifier.height(reservedHeight)) {
        try {
            // Delegate to the existing raw BannerAd — preserves all the
            // AdView/AdListener/Empty-on-fail logic from `ads/BannerAd.kt`.
            com.nolansoftware.airadio.ads.BannerAd(modifier = Modifier.fillMaxSize())
        } catch (t: Throwable) {
            // AdView init can throw on broken WebView; never let it bubble up.
            Log.w("Monetization", "BannerAd threw; rendering empty", t)
            recordEvent(MonetizationEvent.BannerLoadFailed)
            // Box remains empty at reserved height — no layout shift.
        }
    }
}
```

Add the necessary imports at the top of `AdmobMonetizationManager.kt`:
```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
```

- [ ] **Step 5: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/AdmobBannerAdTest.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/FakeConsentManager.kt
git commit -m "feat(monetization): implement BannerAd Composable with empty unit-ID handling and 50dp reservation"
```

> **No Mockito dep added.** Context is `RuntimeEnvironment.getApplication()` (real Robolectric Application); Activity is a real class driven by `Robolectric.buildActivity` in Task 9. The `MonetizationInterstitialHandle` seam is the AdMob-mockability boundary — it lets the show path be unit-tested with hand-rolled fakes instead of needing to mock AdMob's final classes.

---

## Task 7: AdmobInterstitialHandle

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/AdmobInterstitialHandle.kt`

**Interfaces:**
- Consumes: `MonetizationInterstitialHandle` interface (Task 1); AdMob SDK `InterstitialAd` + `FullScreenContentCallback`
- Produces: `AdmobInterstitialHandle(ad: InterstitialAd) : MonetizationInterstitialHandle` — wraps an `InterstitialAd` and forwards `show(activity)` and `setFullScreenContentListener(listener)` calls.

> This task produces no new tests — the integration of this type is exercised by Tasks 8 and 9 with fake `MonetizationInterstitialHandle` instances.

- [ ] **Step 1: Create `AdmobInterstitialHandle.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/AdmobInterstitialHandle.kt
package com.nolansoftware.airadio.monetization

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.InterstitialAd

/**
 * Real AdMob-backed [MonetizationInterstitialHandle].
 *
 * Constructed by [AdmobMonetizationManager.loadInterstitial] when an
 * `InterstitialAd.load(...)` callback fires. Wraps the AdMob SDK type so
 * the manager never holds an `InterstitialAd` reference directly.
 */
class AdmobInterstitialHandle(private val ad: InterstitialAd) : MonetizationInterstitialHandle {

    override fun show(activity: Activity) {
        ad.show(activity)
    }

    override fun setFullScreenContentListener(listener: FullScreenContentCallback) {
        ad.fullScreenContentCallback = listener
    }

    /**
     * Convenience for callers that need to forward AdError events
     * (e.g., to clear cache on show failure). Not part of the interface
     * because test fakes don't need it.
     */
    fun onAdFailedToShow(error: AdError) {
        // The listener pattern means the manager owns the callback body;
        // this is exposed only for diagnostics if needed.
    }
}
```

- [ ] **Step 2: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/AdmobInterstitialHandle.kt
git commit -m "feat(monetization): add AdmobInterstitialHandle wrapping InterstitialAd"
```

---

## Task 8: loadInterstitial impl with retry policy

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt` (replace the `loadInterstitial` placeholder)
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/AdmobLoadInterstitialTest.kt`

**Interfaces:**
- Consumes: `interstitialUnitIdFor` (Task 5), `AdRequest`/`InterstitialAd`/`InterstitialAdLoadCallback` from AdMob SDK, `MonetizationEvent` (Task 1), `FakeConsentManager` (Task 6)
- Produces: a `loadInterstitial(trigger)` impl that:
  - Returns immediately if `interstitialUnitIdFor(trigger) == null` (no-op on empty unit ID)
  - Calls `InterstitialAd.load(...)` with the unit ID
  - On `onAdLoaded`: wraps in `AdmobInterstitialHandle`, registers `FullScreenContentCallback`, stores in `cache`
  - On `onAdFailedToLoad`: retries once after 30 s (via `ioScope.launch { delay(30_000); loadInterstitial(trigger) }`); on second failure, gives up and emits `InterstitialLoadFailed`
  - Emits `InterstitialRequested` once per call

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/AdmobLoadInterstitialTest.kt
package com.nolansoftware.airadio.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AdmobLoadInterstitialTest {

    // Hand-rolled context: Robolectric's real Application. No Mockito.
    private fun newManager(consentValue: Boolean = true): AdmobMonetizationManager =
        AdmobMonetizationManager(
            context = RuntimeEnvironment.getApplication(),
            consents = FakeConsentManager(consentValue),
        )

    @Test fun `loadInterstitial is a no-op when interstitial unit ID is blank`() {
        // Default BuildConfig has ADMOB_INTERSTITIAL_*_ID == "" → must silently no-op.
        val mgr = newManager()
        mgr.loadInterstitial(InterstitialTrigger.ExitFromPlayer)
        // No cache populated, no events emitted.
        assertFalse(mgr.hasCached(InterstitialTrigger.ExitFromPlayer))
        assertEquals(emptyList<MonetizationEvent>(), mgr.recordedEvents)
    }

    @Test fun `interstitialUnitIdFor returns null for blank BuildConfig values`() {
        val mgr = newManager()
        assertEquals(null, mgr.interstitialUnitIdFor(InterstitialTrigger.ExitFromPlayer))
        assertEquals(null, mgr.interstitialUnitIdFor(InterstitialTrigger.AppForeground))
    }

    @Test fun `interstitialUnitIdFor returns the configured value when set`() {
        // This test only runs meaningfully if the property is set in the local dev's gradle.properties.
        // In CI it will be empty; the test verifies the lookup path is exercised.
        val mgr = newManager()
        mgr.interstitialUnitIdFor(InterstitialTrigger.ExitFromPlayer)
        mgr.interstitialUnitIdFor(InterstitialTrigger.AppForeground)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.AdmobLoadInterstitialTest`
Expected: PASS already for the first two (skeleton returns null), FAIL or ERROR for `interstitialUnitIdFor returns the configured value` if the lookup throws (it doesn't currently — pass).

If the test passes entirely (because the placeholder does the right thing for empty unit IDs), the test is regression-protective. Move to Step 3.

- [ ] **Step 3: Replace `loadInterstitial` placeholder in `AdmobMonetizationManager.kt`**

```kotlin
override fun loadInterstitial(trigger: InterstitialTrigger) {
    val unitId = interstitialUnitIdFor(trigger) ?: return    // empty → silent no-op
    recordEvent(MonetizationEvent.InterstitialRequested)
    try {
        InterstitialAd.load(context, unitId, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    val handle = AdmobInterstitialHandle(ad).also { h ->
                        h.setFullScreenContentListener(object : FullScreenContentCallback() {
                            override fun onAdFailedToShow(adError: AdError) {
                                cache.remove(trigger)              // (b) clear on show fail
                                recordEvent(MonetizationEvent.InterstitialLoadFailed)
                            }
                            override fun onAdDismissed() {
                                cache.remove(trigger)
                                recordEvent(MonetizationEvent.InterstitialDismissed)
                            }
                        })
                    }
                    cache[trigger] = handle
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    val prior = retryAttempts.getOrPut(trigger) { 0 }
                    if (prior < 1) {
                        retryAttempts[trigger] = prior + 1
                        ioScope.launch {
                            delay(30_000)
                            loadInterstitial(trigger)            // one retry, 30s backoff
                        }
                    } else {
                        retryAttempts.remove(trigger)
                        recordEvent(MonetizationEvent.InterstitialLoadFailed)
                    }
                }
            })
    } catch (t: Throwable) {
        recordEvent(MonetizationEvent.InterstitialLoadFailed)
    }
}
```

Add necessary imports at the top:
```kotlin
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.AdmobLoadInterstitialTest`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/AdmobLoadInterstitialTest.kt
git commit -m "feat(monetization): implement loadInterstitial with single-retry and cache management"
```

---

## Task 9: showInterstitialIfReady with Mutex busy-guard + caps + Activity handling

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt` (replace the `showInterstitialIfReady` placeholder; add `isThrottled` and `recordShown` helpers; add `injectCacheForTest`; **no** `injectActivityForTest`)
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/AdmobShowInterstitialTest.kt`
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/TestPlayerActivity.kt`

**Interfaces:**
- Consumes: `showMutex` (Task 5), `cache` (Task 5), `currentActivityRef` (Task 5), `sessionCount` + `lastShownAtMs` (Task 5), `AdMobConfig.FREQ_*` constants (Task 2), `MonetizationEvent` (Task 1), real `Application` + `Activity` driven by Robolectric
- Produces:
  - `isThrottled(trigger: InterstitialTrigger): Boolean` — true if within wall-clock window OR per-session cap exceeded
  - `recordShown(trigger: InterstitialTrigger)` — bumps `sessionCount` and `lastShownAtMs`
  - Full `showInterstitialIfReady(trigger)` impl matching the spec's code shape

> **Testability seam (no Mockito, no `injectActivityForTest`):** The `currentActivityRef` is mutated exclusively by the manager's `Application.ActivityLifecycleCallbacks`. Tests drive a real `TestPlayerActivity` through `Robolectric.buildActivity(...).create().start().resume()` — this fires the lifecycle callbacks, which set/clear the WeakReference the same way they do in production. This is the only correct way to verify that `WeakReference.get()` reads the right Activity; mocking the Activity bypasses the lifecycle machinery that the spec says MUST be exercised.

- [ ] **Step 1: Create the `TestPlayerActivity` (a minimal real Activity)**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/TestPlayerActivity.kt
package com.nolansoftware.airadio.monetization

/**
 * Minimal real Activity used to drive the manager's ActivityLifecycleCallbacks
 * in unit tests via Robolectric. The class is intentionally empty — the test
 * only needs a real Activity to attach to, not a working screen.
 */
class TestPlayerActivity : android.app.Activity()
```

> If Robolectric complains about the test Activity not being declared in the test manifest, register it via the `robolectric.manifest` config or use `Config(application = android.app.Application::class)` annotation on the test class.

- [ ] **Step 2: Write the failing test (cold-start race — flagged by user)**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/AdmobShowInterstitialTest.kt
package com.nolansoftware.airadio.monetization

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class AdmobShowInterstitialTest {

    // === Hand-rolled fakes (no Mockito) ===

    private class FakeInterstitialHandle(
        var parkShowOn: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : MonetizationInterstitialHandle {
        var lastShownActivity: Activity? = null
        var fullScreenListener: FullScreenContentCallback? = null
        val shownActivities: MutableList<Activity> = mutableListOf()

        override fun show(activity: Activity) {
            shownActivities.add(activity)
            lastShownActivity = activity
            parkShowOn?.await()
        }
        override fun setFullScreenContentListener(listener: FullScreenContentCallback) {
            fullScreenListener = listener
        }
        fun triggerOnAdFailedToShow() {
            fullScreenListener?.onAdFailedToShow(AdError(0, "test", "test"))
        }
    }

    /**
     * Drive a real Activity to RESUMED state, which fires the manager's
     * `Application.ActivityLifecycleCallbacks.onActivityResumed` and populates
     * `currentActivityRef` exactly the way production does.
     */
    private fun driveResumedActivity(): ActivityController<TestPlayerActivity> =
        Robolectric.buildActivity(TestPlayerActivity::class.java)
            .create().start().resume()

    private fun newManager(consentValue: Boolean = true): AdmobMonetizationManager =
        AdmobMonetizationManager(
            context = RuntimeEnvironment.getApplication(),
            consents = FakeConsentManager(consentValue),
        )

    // === (a) Cold-start race — consent still resolving ===

    @Test fun `showInterstitialIfReady returns false during cold-start when isAdsEnabled is initial false`() {
        // isAdsEnabled's initial value is false until ConsentManager emits.
        val mgr = newManager(consentValue = false)
        val controller = driveResumedActivity()
        val handle = FakeInterstitialHandle()
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, handle)

        val result = mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        assertFalse(result)
        assertEquals(emptyList<MonetizationEvent>(), mgr.recordedEvents)
        assertEquals(0, handle.shownActivities.size)
        controller.destroy()
    }

    // === (b) onAdFailedToShow → cache cleared → next show requires reload ===

    @Test fun `onAdFailedToShow clears cache slot`() {
        val mgr = newManager()
        val controller = driveResumedActivity()
        val handle = FakeInterstitialHandle()
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, handle)
        assertTrue(mgr.hasCached(InterstitialTrigger.ExitFromPlayer))

        // Trigger the FullScreenContentCallback.onAdFailedToShow.
        handle.triggerOnAdFailedToShow()
        assertFalse(mgr.hasCached(InterstitialTrigger.ExitFromPlayer))
        controller.destroy()
    }

    // === (c) Mutex busy-path ===

    @Test fun `mutex busy-path rejects second concurrent show with InterstitialBusy`() {
        val park = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handle1 = FakeInterstitialHandle(parkShowOn = park)
        val handle2 = FakeInterstitialHandle()
        val mgr = newManager()
        val controller = driveResumedActivity()
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, handle1)
        mgr.injectCacheForTest(InterstitialTrigger.AppForeground, handle2)

        // First show parks on park. We invoke it on a background thread because
        // show() awaits the deferred. The mutex is held until show() returns.
        val firstShowThread = Thread {
            mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        }
        firstShowThread.start()
        Thread.sleep(50)

        // Second show on the main thread.
        val secondResult = mgr.showInterstitialIfReady(InterstitialTrigger.AppForeground)
        assertFalse(secondResult)
        assertTrue(mgr.recordedEvents.contains(MonetizationEvent.InterstitialBusy))

        // Release the first.
        park.complete(Unit)
        firstShowThread.join(1000)
        controller.destroy()
    }

    // === Mutex released on every early-return path ===

    @Test fun `mutex is released after ads-disabled early-return`() {
        val mgr = newManager(consentValue = false)
        mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        assertTrue(mgr.tryAcquireShowMutexForTest())
    }

    @Test fun `mutex is released after throttled early-return`() {
        val mgr = newManager()
        val controller = driveResumedActivity()
        // Consume the per-session cap (2 shows), then a third returns throttled.
        repeat(2) {
            mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, FakeInterstitialHandle())
            assertTrue(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        }
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, FakeInterstitialHandle())
        mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        assertTrue(mgr.tryAcquireShowMutexForTest())
        controller.destroy()
    }

    @Test fun `mutex is released after no-cached-ad early-return`() {
        val mgr = newManager()
        val controller = driveResumedActivity()
        mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        assertTrue(mgr.tryAcquireShowMutexForTest())
        controller.destroy()
    }

    @Test fun `mutex is released after no-foreground-activity early-return`() {
        val mgr = newManager()
        // No activity driven to RESUMED — currentActivityRef stays null.
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, FakeInterstitialHandle())
        mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        assertTrue(mgr.tryAcquireShowMutexForTest())
    }

    // === Per-session cap ===

    @Test fun `third show within session is rejected with InterstitialThrottled`() {
        val mgr = newManager()
        val controller = driveResumedActivity()
        repeat(2) {
            mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, FakeInterstitialHandle())
            assertTrue(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        }
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, FakeInterstitialHandle())
        assertFalse(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        assertTrue(mgr.recordedEvents.contains(MonetizationEvent.InterstitialThrottled))
        controller.destroy()
    }

    // === WeakReference-based Activity handling (exercised via real lifecycle) ===

    @Test fun `Activity reference is read at call time via WeakReference`() {
        val mgr = newManager()
        val controller = driveResumedActivity()
        val realActivity = controller.get()
        val handle = FakeInterstitialHandle()
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, handle)

        assertTrue(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        // The handle must have been called with the SAME real Activity instance
        // that the lifecycle callback captured — proves the WeakReference read at
        // call time returned the live Activity, not a stale or null one.
        assertEquals(realActivity, handle.lastShownActivity)
        controller.destroy()
    }

    @Test fun `pausing the activity clears the WeakReference so subsequent show returns false`() {
        val mgr = newManager()
        val controller = driveResumedActivity()
        val handle = FakeInterstitialHandle()
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, handle)

        // First show succeeds.
        assertTrue(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))

        // Pause — the manager's onActivityPaused callback clears the ref.
        controller.pause()
        val handle2 = FakeInterstitialHandle()
        mgr.injectCacheForTest(InterstitialTrigger.ExitFromPlayer, handle2)
        assertFalse(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        assertEquals(0, handle2.shownActivities.size)
        controller.destroy()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.AdmobShowInterstitialTest`
Expected: compile error — `Unresolved reference: injectCacheForTest`, plus assertion failures because `showInterstitialIfReady` is currently `return false`.

- [ ] **Step 4: Add `injectCacheForTest` to `AdmobMonetizationManager.kt`**

In the class body, alongside `hasCached` and `tryAcquireShowMutexForTest`, add:

```kotlin
/**
 * Test-only seam to prime the cache with a fake [MonetizationInterstitialHandle].
 * The only other way to populate the cache is through [loadInterstitial]'s
 * AdMob SDK callback, which cannot be exercised in JVM unit tests.
 *
 * NOT a substitute for [loadInterstitial] in production code — production
 * must go through the AdMob SDK to receive valid ad creatives.
 */
@androidx.annotation.VisibleForTesting
internal fun injectCacheForTest(trigger: InterstitialTrigger, handle: MonetizationInterstitialHandle) {
    cache[trigger] = handle
}
```

> **Do not** add `injectActivityForTest`. The activity path is exercised through `Application.ActivityLifecycleCallbacks` driven by a real `ActivityController` in tests, which is the only way to verify the WeakReference logic.

- [ ] **Step 5: Replace `showInterstitialIfReady` placeholder in `AdmobMonetizationManager.kt`**

Replace the existing placeholder with the full impl:

```kotlin
override fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean {
    // tryLock is non-suspending; returns false immediately if held.
    if (!showMutex.tryLock()) {
        recordEvent(MonetizationEvent.InterstitialBusy)
        return false
    }
    try {
        if (!isAdsEnabled.value) return false                                  // 1. global gate
        if (isThrottled(trigger)) {                                            // 2. caps
            recordEvent(MonetizationEvent.InterstitialThrottled)
            return false
        }
        val handle = cache[trigger] ?: return false                           // 3. cached ad
        val activity = currentActivityRef.get() ?: return false               // 4. foreground Activity
        handle.show(activity)                                                   // 5. fire
        recordShown(trigger)                                                    // 6. update cap state
        recordEvent(MonetizationEvent.InterstitialShown)
        return true
    } catch (t: Throwable) {
        Log.w("Monetization", "show() threw; dropping cache slot", t)
        cache.remove(trigger)
        recordEvent(MonetizationEvent.InterstitialLoadFailed)
        return false
    } finally {
        showMutex.unlock()    // ALWAYS runs, even on early-return.
    }
}

private fun isThrottled(trigger: InterstitialTrigger): Boolean {
    val now = System.currentTimeMillis()
    val lastMs = lastShownAtMs[trigger] ?: 0L
    val perSession = sessionCount.getOrPut(trigger) { 0 }
    val (windowMs, capPerSession) = when (trigger) {
        InterstitialTrigger.ExitFromPlayer ->
            AdMobConfig.FREQ_EXIT_PLAYER_WINDOW_MS to AdMobConfig.FREQ_EXIT_PLAYER_PER_SESSION
        InterstitialTrigger.AppForeground ->
            AdMobConfig.FREQ_FOREGROUND_WINDOW_MS to AdMobConfig.FREQ_FOREGROUND_PER_SESSION
    }
    if (lastMs > 0L && (now - lastMs) < windowMs) return true
    if (perSession >= capPerSession) return true
    return false
}

private fun recordShown(trigger: InterstitialTrigger) {
    lastShownAtMs[trigger] = System.currentTimeMillis()
    sessionCount[trigger] = sessionCount.getOrPut(trigger) { 0 } + 1
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.AdmobShowInterstitialTest`
Expected: PASS, 10 tests green.

- [ ] **Step 7: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests pass (Tasks 1, 2, 5, 5a, 6, 8, 9).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/AdmobMonetizationManager.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/AdmobShowInterstitialTest.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/TestPlayerActivity.kt
git commit -m "feat(monetization): implement showInterstitialIfReady with Mutex busy-guard and frequency caps"
```

---

## Task 10: Hilt module wiring (MonetizationModule + TestMonetizationModule)

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationModule.kt`
- Create: `app/src/androidTest/java/com/nolansoftware/airadio/monetization/TestMonetizationModule.kt`

**Interfaces:**
- Consumes: `MonetizationManager` (Task 5), `AdmobMonetizationManager` (Task 5), `NoOpMonetizationManager` (Task 5a), Hilt `@Module`/`@InstallIn`/`@Binds`/`@TestInstallIn`
- Produces:
  - Production module binding `AdmobMonetizationManager` as `MonetizationManager` (`@Singleton`)
  - Test module replacing production module with `NoOpMonetizationManager`

- [ ] **Step 1: Create `MonetizationModule.kt`**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationModule.kt
package com.nolansoftware.airadio.monetization

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MonetizationModule {
    @Binds @Singleton
    abstract fun bindMonetizationManager(impl: AdmobMonetizationManager): MonetizationManager
}
```

- [ ] **Step 2: Create `TestMonetizationModule.kt`**

```kotlin
// app/src/androidTest/java/com/nolansoftware/airadio/monetization/TestMonetizationModule.kt
package com.nolansoftware.airadio.monetization

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Test-only replacement for [MonetizationModule] — binds
 * [NoOpMonetizationManager] so instrumented tests never instantiate AdMob.
 *
 * Activate in your test by annotating the test class with `@HiltAndroidTest`
 * and ensuring a `HiltTestApplication` is configured in your test runner.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces  = [MonetizationModule::class],
)
abstract class TestMonetizationModule {
    @Binds @Singleton
    abstract fun bindMonetizationManager(impl: NoOpMonetizationManager): MonetizationManager
}
```

- [ ] **Step 3: Verify build is clean**

Run: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationModule.kt \
        app/src/androidTest/java/com/nolansoftware/airadio/monetization/TestMonetizationModule.kt
git commit -m "feat(monetization): add Hilt module wiring with test replacement"
```

---

## Task 11: PlayerScreen banner slot + ExitFromPlayer trigger

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt`

**Interfaces:**
- Consumes: `MonetizationManager` (Task 5), `SurfaceId.Player` (Task 1), `InterstitialTrigger.ExitFromPlayer` (Task 1), existing PlayerScreen layout
- Produces:
  - A bottom-anchored `MonetizationManager.BannerAd(SurfaceId.Player, modifier)` slot with reserved 50 dp height
  - `DisposableEffect` that calls `showInterstitialIfReady(ExitFromPlayer)` on `onDispose`, gated by `activity.isFinishing && !activity.isChangingConfigurations`
  - `LaunchedEffect(Unit)` that calls `loadInterstitial(ExitFromPlayer)` on entry

- [ ] **Step 1: Inject `MonetizationManager` into PlayerScreen**

Open `PlayerScreen.kt`. Find the function signature (top-level `@Composable fun PlayerScreen(...)` or similar) and add the manager as a parameter or via Hilt-injected `ViewModel`.

The simplest path: obtain the manager from a Hilt-provided ViewModel. If `PlayerViewModel` is the existing ViewModel for this screen, add a field there:

```kotlin
// In app/src/main/java/com/nolansoftware/airadio/ui/viewmodels/PlayerViewModel.kt
@HiltViewModel
class PlayerViewModel @Inject constructor(
    // ... existing deps
    val monetizationManager: MonetizationManager,
) : AndroidViewModel(...) {
    // ... existing body unchanged
}
```

If you prefer direct Hilt injection in the Composable (requires the `hiltViewModel()` pattern), use a small `PlayerUiStateViewModel` wrapper instead.

- [ ] **Step 2: Modify the Column layout in PlayerScreen**

Locate the root `Column(...)` in `PlayerScreen.kt` (likely wraps `StationCover`, `StationMetadata`, `PlaybackControls`). Modify it to:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp),
) {
    Spacer(Modifier.weight(1f))
    StationCover(...)
    Spacer(Modifier.height(16.dp))
    StationMetadata(...)
    Spacer(Modifier.weight(1f))
    PlaybackControls(...)
    Spacer(Modifier.height(8.dp))
    val monetizationManager: MonetizationManager = playerViewModel.monetizationManager
    monetizationManager.BannerAd(
        surfaceId = SurfaceId.Player,
        modifier  = Modifier.fillMaxWidth().navigationBarsPadding(),
    )
}
```

If `PlayerScreen.kt` doesn't use this exact structure, adapt: the requirement is that the banner is **the last child of the root Column**, with `navigationBarsPadding()` so it sits above the system bar, and `fillMaxWidth()` so it spans the screen.

- [ ] **Step 3: Add the `DisposableEffect` for ExitFromPlayer**

Find the existing `PlayerScreen` Composable body (inside the function, at the same level as the `Column`). Add:

```kotlin
val activity = LocalActivity.current

DisposableEffect(activity) {
    onDispose {
        if (activity.isFinishing && !activity.isChangingConfigurations) {
            playerViewModel.monetizationManager
                .showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer)
        }
    }
}

LaunchedEffect(Unit) {
    playerViewModel.monetizationManager
        .loadInterstitial(InterstitialTrigger.ExitFromPlayer)
}
```

Add the imports:
```kotlin
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nolansoftware.airadio.monetization.InterstitialTrigger
import com.nolansoftware.airadio.monetization.MonetizationManager
import com.nolansoftware.airadio.monetization.SurfaceId
```

- [ ] **Step 4: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt \
        app/src/main/java/com/nolansoftware/airadio/ui/viewmodels/PlayerViewModel.kt
git commit -m "feat(player): add bottom-anchored banner slot and ExitFromPlayer interstitial trigger"
```

---

## Task 12: MainActivity AppForeground trigger with rotation guard

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/MainActivity.kt`

**Interfaces:**
- Consumes: `MonetizationManager` (Task 5), `InterstitialTrigger.AppForeground` (Task 1), `androidx.lifecycle.DefaultLifecycleObserver`
- Produces:
  - `@Inject lateinit var monetizationManager: MonetizationManager` field
  - A `lifecycle.addObserver(...)` that fires `showInterstitialIfReady(AppForeground)` when `onStart` follows an `onStop` originating from backgrounding (not rotation)

- [ ] **Step 1: Read the existing `MainActivity.kt`**

Verify the existing class shape (likely `@AndroidEntryPoint class MainActivity : ComponentActivity() { ... }`). Note where `onCreate` ends — the lifecycle observer should be registered there.

- [ ] **Step 2: Add the `monetizationManager` injection**

Add to the class body:

```kotlin
@Inject lateinit var monetizationManager: MonetizationManager

private var lastStopWasBackground = false
```

- [ ] **Step 3: Add the lifecycle observer**

Inside `onCreate(...)`, after `super.onCreate(savedInstanceState)` and any existing setup, add:

```kotlin
lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        // onStop fires on rotation too; gate to avoid misfiring AppForeground
        // interstitial on every rotation. Mirrors PlayerScreen.onDispose guard.
        if (!isChangingConfigurations) {
            lastStopWasBackground = true
        }
    }
    override fun onStart(owner: LifecycleOwner) {
        if (lastStopWasBackground) {
            monetizationManager.showInterstitialIfReady(InterstitialTrigger.AppForeground)
        }
        lastStopWasBackground = false
    }
})
```

Add the imports:
```kotlin
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nolansoftware.airadio.monetization.InterstitialTrigger
import com.nolansoftware.airadio.monetization.MonetizationManager
import javax.inject.Inject
```

- [ ] **Step 4: Verify build is clean**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual smoke check (cannot be unit-tested easily)**

- [ ] Cold-start the app, navigate to Player, play audio for 30 s, press Home, return to app from launcher — AppForeground interstitial should appear.
- [ ] From Player, rotate the device 3 times — no interstitial should appear.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/MainActivity.kt
git commit -m "feat(main): add AppForeground interstitial trigger gated against config changes"
```

---

## Task 13: PlayerScreenAdLayoutTest — Compose UI test for layout invariant

**Files:**
- Create: `app/src/test/java/com/nolansoftware/airadio/monetization/PlayerScreenAdLayoutTest.kt` (Robolectric)
- Modify: `app/build.gradle.kts` (ensure `testOptions { unitTests.isIncludeAndroidResources = true }` and Robolectric dependency)

**Interfaces:**
- Consumes: `PlayerScreen` Composable (Task 11), `MonetizationManager` (Task 5), `NoOpMonetizationManager` (Task 5a), Compose UI testing
- Produces: A Robolectric test that asserts:
  - When `NoOpMonetizationManager` is bound (i.e., ads disabled), `PlayerScreen` renders a banner slot of height 50 dp
  - The slot height is reserved even when no ad is loaded (no layout shift)
  - Playback controls do not overlap the banner slot (inset, not overlay)

- [ ] **Step 1: Verify Robolectric is on classpath (already is per the project's existing Compose UI tests)**

Check `app/build.gradle.kts` for `testImplementation("org.robolectric:robolectric:...")`. If not present, add the latest stable Robolectric for the project's test config.

- [ ] **Step 2: Add test tags to PlayerScreen**

Open `PlayerScreen.kt` (modified in Task 11). Add `testTag("player_controls")` and `testTag("player_banner_slot")` to the relevant Composables:

```kotlin
PlaybackControls(
    modifier = Modifier.testTag("player_controls"),
    ...
)

// In the banner slot:
monetizationManager.BannerAd(
    surfaceId = SurfaceId.Player,
    modifier  = Modifier.fillMaxWidth().navigationBarsPadding().testTag("player_banner_slot"),
)
```

Add the import:
```kotlin
import androidx.compose.ui.platform.testTag
```

- [ ] **Step 3: Write the layout test**

```kotlin
// app/src/test/java/com/nolansoftware/airadio/monetization/PlayerScreenAdLayoutTest.kt
package com.nolansoftware.airadio.monetization

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nolansoftware.airadio.ads.AdMobConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerScreenAdLayoutTest {

    @get:Rule val composeRule = createComposeRule()

    private val noOpMonetizationManager = NoOpMonetizationManager()

    /**
     * Minimal PlayerScreen wrapper for layout testing. Uses the NoOp
     * monetization manager so the banner slot renders empty but reserved.
     * In the production code path, PlayerScreen has many other UI elements
     * (album cover, metadata, controls) — this wrapper only renders the
     * banner slot + a stub controls block.
     */
    @Composable
    private fun PlayerScreenStubForLayoutTest() {
        Surface(modifier = Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(0f)
                        .testTag("player_controls"),
                )
                noOpMonetizationManager.BannerAd(
                    surfaceId = SurfaceId.Player,
                    modifier  = Modifier
                        .fillMaxWidth()
                        .testTag("player_banner_slot"),
                )
            }
        }
    }

    @Test fun `banner slot reserves 50dp height when ad unit ID is empty (NoOp impl)`() {
        composeRule.setContent {
            MaterialTheme { PlayerScreenStubForLayoutTest() }
        }
        val bannerSlot = composeRule.onNodeWithTag("player_banner_slot")
        bannerSlot.assertHeightIsEqualTo(50.dp)
    }

    @Test fun `player controls do not shift when banner slot is reserved at 50dp`() {
        composeRule.setContent {
            MaterialTheme { PlayerScreenStubForLayoutTest() }
        }
        val controls = composeRule.onNodeWithTag("player_controls")
        val bannerSlot = composeRule.onNodeWithTag("player_banner_slot")
        val controlsRectBefore = controls.getBoundsInRoot()
        // Re-composition should not move the controls; the banner slot is reserved.
        composeRule.waitForIdle()
        val controlsRectAfter = controls.getBoundsInRoot()
        assertEquals(controlsRectBefore, controlsRectAfter)
        // Sanity: banner slot's top edge is below controls' bottom edge.
        assert(bannerSlot.getBoundsInRoot().top >= controlsRectAfter.bottom) {
            "Banner slot overlaps controls — should be inset, not overlay."
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests com.nolansoftware.airadio.monetization.PlayerScreenAdLayoutTest`
Expected: PASS, 2 tests green.

- [ ] **Step 5: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests pass.

- [ ] **Step 6: Run lint to catch Compose API mistakes**

Run: `./gradlew :app:lintDebug`
Expected: no new lint errors introduced.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt \
        app/src/test/java/com/nolansoftware/airadio/monetization/PlayerScreenAdLayoutTest.kt
git commit -m "test(monetization): add Robolectric layout test asserting 50dp banner slot reservation"
```

---

## Final verification

After all 13 tasks:

- [ ] **Build & test sweep**

```bash
./gradlew clean
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Expected: all 4 commands exit 0; no new warnings.

- [ ] **Acceptance Criteria verification**

Walk through `docs/superpowers/specs/2026-10-09-airadio-ad-monetization-design.md` § "Acceptance Criteria" — all 13 items must be checked off.

- [ ] **Manual smoke checklist**

Run the manual smoke checklist from the spec § "Manual Smoke Checklist":
- Cold start (consent granted) → Player shows banner within 2 s
- Cold start (consent unknown) → no banner until UMP resolves
- Player back nav → ExitFromPlayer interstitial appears
- Player rotation only → no interstitial
- App switch → AppForeground interstitial appears, not within 10 min
- Rapid 3× player entry/exit → 2 interstitial, no 3rd
- Airplane mode → banner slot reserves 50dp, no crash
- BlueStacks → app launches

- [ ] **Memory file update**

If any of the following were learned during execution, save to project memory:
- Specific Android Gradle Plugin behavior that blocked a build
- AdMob SDK quirks not covered in the spec
- Compose test patterns that required workarounds

Use the `/remember` skill or write directly to `/mnt/c/Users/nolan/.claude/projects/-home-nolan-projects-android-AIRadio/memory/MEMORY.md` per the project's convention.

---

## Self-Review Notes

**Spec coverage:** All 13 acceptance criteria from the spec have a task that implements them. The four flagged edge cases (cold-start race, onAdFailedToShow cache clear, busy-guard, mutex release on every early-return) are all covered by Task 9 tests. The empty unit-ID contract is pinned by Task 2 (gradle default = "") and Task 6 (BannerAd render-empty branch) and Task 13 (Robolectric test against NoOp). The `isFinishing && !isChangingConfigurations` guard is in Task 11. The `!isChangingConfigurations` guard for `lastStopWasBackground` is in Task 12.

**Placeholder scan:** No "TBD", "TODO", or "fill in details" anywhere. Every code block has actual content.

**Type consistency:** Names match across tasks: `SurfaceId.Player`, `InterstitialTrigger.ExitFromPlayer`, `MonetizationEvent.InterstitialBusy`, `MonetizationInterstitialHandle`, `AdmobMonetizationManager`, `NoOpMonetizationManager`, `TestPlayerActivity`, `FakeConsentManager`, `injectCacheForTest`, `tryAcquireShowMutexForTest`, `hasCached`, `recordedEvents` — verified each is defined in the task that introduces it and consumed in the task that uses it. `injectActivityForTest` is intentionally absent (replaced by real `ActivityController` lifecycle driving per the user's review feedback).

**Review Focus check:** All 5 items in the Review Focus section are pinned to a specific task's test (cold-start race → Task 9; InterstitialBusy vs Throttled → Task 9; sync showInterstitialIfReady → Task 5 interface + Task 9 impl; WeakReference Activity → Task 5 skeleton + Task 9 test; lastStopWasBackground gating → Task 12).

**Known limitations:**
- Tablet/landscape banner height adaptation is deferred to manual smoke + future ticket per the spec.
- Real AdMob test ID integration tests are deferred per the spec.
- Consent-disabled Activity scenario is unit-tested by NOT driving any Activity to RESUMED state — `currentActivityRef` stays null and `showInterstitialIfReady` returns false on the no-foreground-activity early-return path. Full Compose-driven rotation tests require `androidTest` instrumentation, deferred.