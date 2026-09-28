# AIRadio UI Beautification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute the UI beautification design in `docs/superpowers/specs/2026-09-28-airadio-ui-beautify-design.md` — split theme tokens, build gradient-placeholder cover pipeline, redesign StationCard as a vertical tile, rewrite PlayerScreen as a centered immersive hero, migrate four list pages to LazyVerticalGrid, polish bottom navigation.

**Architecture:** One-shot UI refactor (方案 A). The plan is decomposed into 14 reviewable commits ordered by compile-time safety — each task leaves the project in a working state. No domain/data/ViewModel architecture changes; only theme tokens, Compose UI, and one Coil config. No new dependencies (Compose BOM 2024.12.01, material-icons-extended, coil-compose 2.7.0, lifecycle-runtime-compose already on classpath).

**Tech Stack:** Jetpack Compose + Material 3, Coil 2.7.0 (`SubcomposeAsyncImage` + `ImageLoaderFactory`), Hilt 2.48, Navigation Compose 2.7.5. Gradle wrapper. minSdk 26, compileSdk 36, JDK 17.

**Spec:** `docs/superpowers/specs/2026-09-28-airadio-ui-beautify-design.md`

---

## Global Constraints

- **Build:** `./gradlew assembleDebug` must finish with 0 errors. Lint (`./gradlew lint`) must not introduce new errors.
- **JDK:** Pin to JDK 17 (`C:\jdk17\jdk-17.0.2` on Windows host, available via gradle.properties). Do NOT bump.
- **Compose BOM / Compiler:** Do NOT change `2024.10.00` Compose BOM or `1.5.5` Compose Compiler. Kotlin 1.9.20 must stay.
- **WorkManager:** The manifest's `InitializationProvider` MUST keep `tools:node="remove"` for `androidx.work.WorkManagerInitializer`. `AIRadioApp.getWorkManagerConfiguration` is the sole initializer — do not re-enable the default. (Project invariant, unrelated to this plan but easy to break.)
- **Hilt:** `app/build.gradle.kts` MUST keep `kapt { correctErrorTypes = true }` for Hilt + Room annotation processing.
- **No new dependencies.** Compose BOM, material-icons-extended, coil-compose, lifecycle-runtime-compose are all already on classpath. If a "missing" symbol appears, prefer the `rx` variant already available.
- **Do NOT migrate LiveData → StateFlow** (out of scope per spec). Only `PlayerViewModel.isFavorite(stationId)` switches from `collectAsState` to `collectAsStateWithLifecycle` (one call site).
- **Do NOT modify** `RadioPlayerService`, `SyncWorker`, repository, use cases, DAOs, Room entities, API DTOs, mappers, `domain/model/`.
- **Do NOT rename** any existing public Composable signature. Extend, don't replace.
- **Strings:** No new English strings. Use existing `R.string.*` resources; if a literal string is unavoidable, follow Material 3 sentence case (`"Stop"`, `"Retry"`, `"Now Playing"`).
- **Theming:** All colors must come from `MaterialTheme.colorScheme.*`. All shapes must come from `MaterialTheme.shapes.*`. All text styles must come from `MaterialTheme.typography.*`. No hex literals in screen files; only in `Color.kt`. No hard-coded `RoundedCornerShape(N.dp)` outside `theme/`.

---

## Review Focus

The spec implies behaviors that no compile / lint check will catch. These are the inputs a reasonable person would expect to work that this plan must pin to a task's verification step:

1. **Dynamic color on Android 12+** — manual visual on an Android 12+ emulator with the wallpaper changed; switching wallpaper and relaunching must re-tint the whole UI (no stale colors). Pinned in **Task 1** verification (Android 12+ emulator manual check) and **Task 11** verification (PlayerScreen re-tinted).
2. **Status bar / nav bar transparency** — content under system bars must remain readable (gradient bg of PlayerScreen shows through, scrollable lists do not paint the status bar). Pinned in **Task 1** compile + **Task 11** manual.
3. **`SubcomposeAsyncImage` loading + success slot swap** — the gradient placeholder must fade to the loaded image via crossfade; no flash of `ic_radio` icon mid-load. Pinned in **Task 4** manual visual.
4. **`FavoriteHeart` tap visual** — haptic must fire, color tween must complete, spring scale must settle. Pinned in **Task 5** manual visual + haptic check.
5. **Grid pagination edge case** — `LazyVerticalGrid` + Paging 3 with `append is LoadState.Loading` / `append is LoadState.Error` must show footer spinner / retry button at the bottom of the grid, not appended as grid cells at the side. Pinned in **Task 12** manual paging scroll test.

---

## Task 1: Theme tokens (Color / Type / Shape / Theme split)

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/ui/theme/Color.kt`
- Create: `app/src/main/java/com/nolansoftware/airadio/ui/theme/Type.kt`
- Create: `app/src/main/java/com/nolansoftware/airadio/ui/theme/Shape.kt`
- Rewrite: `app/src/main/java/com/nolansoftware/airadio/ui/theme/Theme.kt`

**Produces:** `LightColors: ColorScheme`, `DarkColors: ColorScheme`, `Typography: Typography`, `Shapes: Shapes`, refactored `AIRadioTheme(darkTheme, dynamicColor, content)` composable that wires all three.

- [ ] **Step 1: Write Color.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/theme/Color.kt
package com.nolansoftware.airadio.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Purple tones (Material 3 tonal palette baseline)
val Purple10 = Color(0xFF21005D)
val Purple20 = Color(0xFF381E72)
val Purple30 = Color(0xFF4F378B)
val Purple40 = Color(0xFF6750A4)
val Purple80 = Color(0xFFD0BCFF)
val Purple90 = Color(0xFFEADDFF)

// Violet tones
val Violet30 = Color(0xFF4A4458)
val Violet40 = Color(0xFF625B71)
val Violet80 = Color(0xFFCCC2DC)
val Violet90 = Color(0xFFEEDFE7)

// Sky (tertiary) tones
val Sky30 = Color(0xFF1F3A5F)
val Sky40 = Color(0xFF2E5C8A)
val Sky80 = Color(0xFFB5D5F0)
val Sky90 = Color(0xFFD4E7F8)

// Neutral palette
val Neutral10 = Color(0xFF1C1B1F)
val Neutral20 = Color(0xFF2B2930)
val Neutral90 = Color(0xFFE6E1E5)
val Neutral95 = Color(0xFFF1ECF4)
val Neutral99 = Color(0xFFFFFBFE)

// Variant neutrals
val NeutralVariant30 = Color(0xFF49454F)
val NeutralVariant50 = Color(0xFF79747E)
val NeutralVariant80 = Color(0xFFCAC4D0)

val LightColors = lightColorScheme(
    primary = Purple40,
    onPrimary = Neutral99,
    primaryContainer = Purple90,
    onPrimaryContainer = Purple10,
    secondary = Violet40,
    onSecondary = Neutral99,
    secondaryContainer = Violet90,
    onSecondaryContainer = Violet30,
    tertiary = Sky40,
    onTertiary = Neutral99,
    tertiaryContainer = Sky90,
    onTertiaryContainer = Sky30,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = NeutralVariant30,
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
)

val DarkColors = darkColorScheme(
    primary = Purple80,
    onPrimary = Purple20,
    primaryContainer = Purple30,
    onPrimaryContainer = Purple90,
    secondary = Violet80,
    onSecondary = Violet30,
    secondaryContainer = Violet30,
    onSecondaryContainer = Violet90,
    tertiary = Sky80,
    onTertiary = Sky30,
    tertiaryContainer = Sky30,
    onTertiaryContainer = Sky90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerLow = Color(0xFF1D1B22),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = NeutralVariant30,
)
```

- [ ] **Step 2: Write Type.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/theme/Type.kt
package com.nolansoftware.airadio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
```

- [ ] **Step 3: Write Shape.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/theme/Shape.kt
package com.nolansoftware.airadio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
```

- [ ] **Step 4: Rewrite Theme.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/theme/Theme.kt
package com.nolansoftware.airadio.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FallbackLightColors = lightColorScheme() // dynamic-color branch fallbacks (Android < 12)
private val FallbackDarkColors = darkColorScheme()

@Composable
fun AIRadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
```

Note: `FallbackLightColors`/`FallbackDarkColors` are unused but harmless — they exist so the file compiles if a future task disables dynamic color without re-importing. If your linter flags them, remove the `val` and the import.

- [ ] **Step 5: Verify compile**

Run: `cd /home/nolan/projects/android/AIRadio && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Existing screens still compile (they read `MaterialTheme.colorScheme.*` which Material 3 maps onto the new fields).

- [ ] **Step 6: Manual visual check**

Run: `./gradlew installDebug` (with emulator running).
- Open the app on Android 12+ emulator — verify dynamic color still works (no regression).
- Switch device to dark theme → spot check Compose surfaces look correct (no pure black, Material 3 surface containers used).
- Scroll HomeScreen → status bar transparent, content scrolls under it.

- [ ] **Step 7: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/theme/Color.kt \
        app/src/main/java/com/nolansoftware/airadio/ui/theme/Type.kt \
        app/src/main/java/com/nolansoftware/airadio/ui/theme/Shape.kt \
        app/src/main/java/com/nolansoftware/airadio/ui/theme/Theme.kt
git commit -m "refactor(theme): split Theme.kt into Color/Type/Shape tokens

- Deep purple-blue M3 tonal palette, complete light + dark schemes
- Typography weights differentiated (SemiBold for display/headline, Medium for title/label)
- Shapes token: extraSmall=4, small=8, medium=16, large=20, extraLarge=28
- Status + nav bars transparent; Compose controls icon brightness
- Dynamic color branch preserved for Android 12+"
```

---

## Task 2: Coil ImageLoader configuration

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/AIRadioApp.kt`

**Produces:** `AIRadioApp.newImageLoader(): ImageLoader` with crossfade default 300ms, 50MB disk cache, memory cache 25%, `respectCacheHeaders(false)`.

- [ ] **Step 1: Add the ImageLoaderFactory interface and override**

Replace the current class declaration line:
```kotlin
@HiltAndroidApp
class AIRadioApp : Application(), Configuration.Provider {
```
with:
```kotlin
@HiltAndroidApp
class AIRadioApp : Application(), Configuration.Provider, ImageLoaderFactory {
```

Add imports:
```kotlin
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
```

Add a `newImageLoader()` override inside the class (anywhere after the existing methods):
```kotlin
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(300)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
```

The full file's onCreate / workManagerConfiguration stay as-is.

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual launch**

Run: `./gradlew installDebug` then launch.
Expected: no crash. App opens normally. Existing StationCard favicons still load (they use `rememberAsyncImagePainter` and now pick up the configured `ImageLoader` via `LocalContext`).

- [ ] **Step 4: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/AIRadioApp.kt
git commit -m "feat(coil): implement ImageLoaderFactory

- Global crossfade(300ms) default
- Memory cache 25%, disk cache 50MB at cacheDir/image_cache
- respectCacheHeaders(false) for radio favicons (cache-control unreliable)"
```

---

## Task 3: GradientBackdrop component

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/ui/components/GradientBackdrop.kt`

**Produces:** `GradientBackdrop(seed, modifier, cornerRadius)` composable + `gradientPaletteFor(seed)` helper used by both `StationCover` and the PlayerScreen gradient background.

- [ ] **Step 1: Write GradientBackdrop.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/components/GradientBackdrop.kt
package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class GradientPalette(val start: Color, val end: Color)

private val Palettes = listOf(
    GradientPalette(Color(0xFF6750A4), Color(0xFF7F67BE)),
    GradientPalette(Color(0xFF625B71), Color(0xFF7A6E89)),
    GradientPalette(Color(0xFF4A636C), Color(0xFF6B7E87)),
    GradientPalette(Color(0xFF7D5260), Color(0xFF976C7A)),
    GradientPalette(Color(0xFF4F378B), Color(0xFF6E50B3)),
    GradientPalette(Color(0xFF005AC1), Color(0xFF3478E0)),
)

internal fun gradientPaletteFor(seed: String): GradientPalette {
    if (seed.isEmpty()) return Palettes[0]
    val idx = (seed.hashCode().toUInt() % Palettes.size.toUInt()).toInt()
    return Palettes[idx]
}

@Composable
fun GradientBackdrop(
    seed: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp
) {
    val palette = remember(seed) { gradientPaletteFor(seed) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(palette.start, palette.end),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
    )
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (No screen uses GradientBackdrop yet — that's Task 4.)

- [ ] **Step 3: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/components/GradientBackdrop.kt
git commit -m "feat(ui): deterministic gradient backdrop component

6-pair palette indexed by string seed hash.
Used as loading/error placeholder for cover images
and as PlayerScreen gradient background (seeded by stationId)."
```

---

## Task 4: StationCover component

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/ui/components/StationCover.kt`

**Produces:** `StationCover(imageUrl, contentDescription, modifier, cornerRadius, fallbackIcon)` composable that wraps `SubcomposeAsyncImage` with four slots (loading / error / success / fallback) all delegating to GradientBackdrop.

- [ ] **Step 1: Write StationCover.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/components/StationCover.kt
package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

@Composable
fun StationCover(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    fallbackIcon: ImageVector = Icons.Outlined.Radio,
) {
    val seed = imageUrl?.takeIf { it.isNotEmpty() } ?: contentDescription.orEmpty().ifEmpty { "default" }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(imageUrl)
            .crossfade(300)
            .build(),
        contentDescription = contentDescription,
        modifier = Modifier.clip(RoundedCornerShape(cornerRadius)),
        contentScale = ContentScale.Crop,
        loading = {
            GradientBackdrop(seed = seed, cornerRadius = cornerRadius)
        },
        error = {
            GradientBackdrop(seed = seed, cornerRadius = cornerRadius)
        },
        success = { state ->
            Image(
                painter = state.painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
            )
        },
        fallback = {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                GradientBackdrop(seed = seed, modifier = Modifier.fillMaxSize(), cornerRadius = cornerRadius)
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    )
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (No screen calls StationCover yet — Task 5 onward.)

- [ ] **Step 3: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/components/StationCover.kt
git commit -m "feat(ui): StationCover component wraps SubcomposeAsyncImage

Four slots (loading/error/success/fallback) all render GradientBackdrop
on non-success paths. Loading + success use crossfade(300) from Coil.
Empty url falls through to fallback (gradient + centered radio icon)."
```

---

## Task 5: FavoriteHeart component

**Files:**
- Create: `app/src/main/java/com/nolansoftware/airadio/ui/components/FavoriteHeart.kt`

**Produces:** `FavoriteHeart(isFavorite, onToggle, modifier)` composable with haptic, spring scale, color tween, AnimatedContent icon swap.

- [ ] **Step 1: Write FavoriteHeart.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/components/FavoriteHeart.kt
package com.nolansoftware.airadio.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun FavoriteHeart(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val tint by animateColorAsState(
        targetValue = if (isFavorite) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 250),
        label = "favorite-tint"
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 20.dp),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { scale.animateTo(1.3f, spring(stiffness = 400f, dampingRatio = 0.6f)) }
                    scope.launch { scale.animateTo(1f, spring(stiffness = 400f, dampingRatio = 0.6f)) }
                    onToggle()
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isFavorite,
            transitionSpec = {
                (scaleIn(initialScale = 0.6f, animationSpec = tween(150)) + fadeIn(tween(150)))
                    .togetherWith(fadeOut(tween(150)))
            },
            label = "favorite-icon",
        ) { fav ->
            Icon(
                imageVector = if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (fav) "Remove from favorites" else "Add to favorites",
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            )
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual preview (optional)**

Skip if no preview infra. Otherwise wrap in a `@Preview` Composable, run preview, visually confirm the heart shape, the spring scale on tap, and the color tween.

- [ ] **Step 4: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/components/FavoriteHeart.kt
git commit -m "feat(ui): FavoriteHeart component with haptic + spring + AnimatedContent

- 40dp circle, semi-transparent surface backdrop (alpha 0.7)
- 250ms color tween between primary (favorited) and onSurfaceVariant
- Spring scale 1→1.3→1 on tap (stiffness=400, dampingRatio=0.6)
- AnimatedContent scaleIn(0.6)+fadeIn(150) / fadeOut(150) for icon swap
- HapticFeedback LongPress on tap
- Ripple bounded=false radius=20dp"
```

---

## Task 6: StationCard vertical tile redesign

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/components/StationCard.kt` (rewrite the `StationCard` composable + upgrade `BrowseItemCard`)

**Consumes:** `StationCover`, `FavoriteHeart` (Tasks 4 and 5).

**Produces:** `StationCard(station, isFavorite, onStationClick, onToggleFavorite, modifier)` as a square 1:1 vertical tile using `MaterialTheme.shapes.large` (20dp), `surfaceContainerLow`, `tonalElevation = 1.dp`. `BrowseItemCard(...)` upgraded to 16dp corners + tonal elevation + chevron icon.

- [ ] **Step 1: Read current StationCard.kt to confirm signature**

Run: `cat app/src/main/java/com/nolansoftware/airadio/ui/components/StationCard.kt | head -50`

Confirm the existing `StationCard(station: Station, isFavorite: Boolean, onStationClick: (Station) -> Unit, onToggleFavorite: (Station) -> Unit, modifier: Modifier = Modifier)` and `BrowseItemCard(name: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier)` signatures. If they differ, adjust the rewrite to match — do NOT rename callers' signatures.

- [ ] **Step 2: Replace the file content with the new implementation**

Write the new file:

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/components/StationCard.kt
package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nolansoftware.airadio.domain.model.Station

@Composable
fun StationCard(
    station: Station,
    isFavorite: Boolean,
    onStationClick: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { onStationClick(station) }
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            Box(modifier = Modifier.aspectRatio(1f)) {
                StationCover(
                    imageUrl = station.favicon,
                    contentDescription = station.name,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp,
                    fallbackIcon = Icons.Outlined.Radio,
                )
                FavoriteHeart(
                    isFavorite = isFavorite,
                    onToggle = { onToggleFavorite(station) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = station.country,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (station.bitrate > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${station.bitrate} kbps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun BrowseItemCard(
    name: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
```

- [ ] **Step 3: Verify compile (callers still compile)**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. All five `StationCard` callers and `BrowseItemCard` callers still compile (signatures unchanged).

- [ ] **Step 4: Manual visual check**

Run: `./gradlew installDebug`.
- HomeScreen Popular grid: cards render as 2-column square tiles, gradient placeholder fades to favicon, heart icon TopEnd.
- Tap heart: spring scales, haptic, color tween, icon swap.
- Scroll: cards have proper spacing and tonal contrast.
- BrowseScreen tab rows: chevron visible on right, ripple on whole row.

- [ ] **Step 5: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/components/StationCard.kt
git commit -m "refactor(components): StationCard vertical tile, BrowseItemCard upgraded

- StationCard: 1:1 square, MaterialTheme.shapes.large (20dp), surfaceContainerLow
  tonalElevation 1dp. Cover via StationCover + FavoriteHeart TopEnd overlay.
- Metadata: name (titleMedium), country (bodySmall), bitrate only (labelSmall).
- BrowseItemCard: shapes.medium (16dp) corners, tonalElevation 1dp, chevron
  Icons.AutoMirrored.Filled.KeyboardArrowRight at right edge."
```

---

## Task 7: SkeletonStationCard sync

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/components/SkeletonStationCard.kt`

**Produces:** Vertical tile skeleton matching new StationCard geometry; no shimmer.

- [ ] **Step 1: Rewrite SkeletonStationCard.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/components/SkeletonStationCard.kt
package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun SkeletonStationCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                SkeletonBar(widthFraction = 0.7f, height = 16.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(widthFraction = 0.4f, height = 12.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(widthFraction = 0.3f, height = 10.dp)
            }
        }
    }
}

@Composable
fun SkeletonBrowseRow(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            SkeletonBar(widthFraction = 0.5f, height = 16.dp)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}
```

Keep `SkeletonBrowseRow` since `BrowseScreen` still uses LazyColumn rows.

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual visual check**

Run: `./gradlew installDebug`.
- HomeScreen on cold start: skeleton tiles render in grid shape matching real cards.
- BrowseScreen during sync: skeleton rows render with same height as BrowseItemCard.
- No shimmer animation (intentional, per spec).

- [ ] **Step 4: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/components/SkeletonStationCard.kt
git commit -m "refactor(components): SkeletonStationCard vertical tile geometry

Match new StationCard 1:1 aspect ratio. surfaceContainerHigh blocks.
No shimmer (intentional per spec, avoids extra dep)."
```

---

## Task 8: HomeScreen migration (LazyRow + LazyVerticalGrid)

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/screens/HomeScreen.kt`

**Consumes:** New `StationCard` (Task 6), `SkeletonStationCard` (Task 7).

**Produces:** Recently Played as `LazyRow` of vertical tiles (`fillParentMaxWidth(0.42f)`); Popular + Local as `LazyVerticalGrid(GridCells.Adaptive(160.dp))`. Section headers styled. `AnimatedStationCardItem` helper for fade+slide stagger entrance.

- [ ] **Step 1: Read current HomeScreen.kt**

Run: `cat app/src/main/java/com/nolansoftware/airadio/ui/screens/HomeScreen.kt`

Confirm:
- The outer container is a single `LazyColumn`.
- `items(...)` for Popular and Local stations.
- A nested `LazyRow` for Recently Played with a `StationItem` helper.

- [ ] **Step 2: Replace the file with the migrated version**

Write the new HomeScreen.kt (keep all imports for `Station`, use cases, ViewModels, `SyncStatusBanner`, `StationCard`, `SkeletonStationCard`, etc.):

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/screens/HomeScreen.kt
package com.nolansoftware.airadio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.ui.components.SkeletonStationCard
import com.nolansoftware.airadio.ui.components.StationCard
import com.nolansoftware.airadio.ui.components.SyncStatusBanner
import com.nolansoftware.airadio.ui.viewmodels.HomeViewModel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onStationClick: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val popularStations by viewModel.popularStations.collectAsState()
    val localStations by viewModel.localStations.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val popularFavorites by viewModel.popularFavorites.collectAsState()
    val localFavorites by viewModel.localFavorites.collectAsState()
    val recentFavorites by viewModel.recentFavorites.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    Scaffold { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            SyncStatusBanner(syncState = syncState)

            if (popularStations.isEmpty() && syncState is SyncState.Syncing) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(count = 6) { SkeletonStationCard() }
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (recentlyPlayed.isNotEmpty()) {
                    item {
                            SectionHeader(text = stringResource(R.string.recently_played))
                        }
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = recentlyPlayed,
                                key = { it.stationuuid },
                            ) { station ->
                                AnimatedStationCardItem(
                                    index = recentlyPlayed.indexOf(station),
                                    station = station,
                                    isFavorite = recentFavorites[station.stationuuid] ?: false,
                                    onClick = onStationClick,
                                    onToggleFavorite = onToggleFavorite,
                                    inCarousel = true,
                                )
                            }
                        }
                    }
                }

                if (popularStations.isNotEmpty()) {
                    item { SectionHeader(text = stringResource(R.string.popular_stations)) }
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = popularStations,
                                key = { it.stationuuid },
                            ) { station ->
                                AnimatedStationCardItem(
                                    index = popularStations.indexOf(station),
                                    station = station,
                                    isFavorite = popularFavorites[station.stationuuid] ?: false,
                                    onClick = onStationClick,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        }
                    }
                }

                if (localStations.isNotEmpty()) {
                    item { SectionHeader(text = stringResource(R.string.local_stations)) }
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val locals = localStations.take(10)
                            items(
                                items = locals,
                                key = { it.stationuuid },
                            ) { station ->
                                AnimatedStationCardItem(
                                    index = locals.indexOf(station),
                                    station = station,
                                    isFavorite = localFavorites[station.stationuuid] ?: false,
                                    onClick = onStationClick,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AnimatedStationCardItem(
    index: Int,
    station: Station,
    isFavorite: Boolean,
    onClick: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    inCarousel: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(station.stationuuid) {
        delay(index * 30L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) +
                slideInVertically(initialOffsetY = { it / 10 }, animationSpec = tween(180)),
        exit = fadeOut(),
    ) {
        if (inCarousel) {
            Box(modifier = Modifier.fillParentMaxWidth(0.42f)) {
                StationCard(
                    station = station,
                    isFavorite = isFavorite,
                    onStationClick = onClick,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        } else {
            StationCard(
                station = station,
                isFavorite = isFavorite,
                onStationClick = onClick,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}
```

Notes:
- The exact names of HomeViewModel properties (`popularStations`, `popularFavorites`, etc.) are taken from the existing ViewModel. Adjust names if they differ.
- `SyncState` is imported from wherever HomeViewModel uses it. If it lives in a different package, fix the import.
- `fillParentMaxWidth` requires `import androidx.compose.foundation.layout.fillParentMaxWidth` inside the LazyRow scope — the import path is the same package as `fillMaxWidth`.

- [ ] **Step 3: Add missing imports**

The above may miss:
- `androidx.compose.foundation.layout.Column`
- `androidx.compose.foundation.layout.fillParentMaxWidth`
- Any ViewModel-specific types (`SyncState`, `Station`).

Add imports one by one until build succeeds.

- [ ] **Step 4: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual visual check**

Run: `./gradlew installDebug`.
- Cold start: skeleton grid appears, then real data populates with fade-in stagger.
- Scroll Popular section: 2-column grid, vertical tiles, gradient placeholders fade to real favicons.
- Scroll Local section: same.
- Recently Played carousel: horizontal scroll, vertical tile cards 42% of parent width.
- Tap card → PlayerScreen.
- Tap heart → spring + haptic + color tween.

- [ ] **Step 6: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/HomeScreen.kt
git commit -m "feat(home): LazyRow + LazyVerticalGrid hybrid layout

- Recently Played: horizontal LazyRow of vertical tiles (42% parent width)
- Popular + Local: LazyVerticalGrid(Adaptive(160dp)) 2-col grid
- Section headers: titleLarge SemiBold onSurface, 20dp horizontal padding
- AnimatedStationCardItem: fadeIn(120ms) + slideInVertically(it/10, 180ms),
  30ms stagger per item, plays on first appearance only"
```

---

## Task 9: SearchScreen migration

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/screens/SearchScreen.kt`

**Consumes:** New `StationCard` (Task 6).

**Produces:** `LazyVerticalGrid(Adaptive(160.dp))` instead of `LazyColumn`; section header "Results for {query}" when results present.

- [ ] **Step 1: Read current SearchScreen.kt**

Confirm the LazyColumn structure, threshold (`searchQuery.length >= 2`), and the empty/hint states.

- [ ] **Step 2: Replace the LazyColumn section**

Locate the existing `LazyColumn(...)` block that renders `items(searchResults) { ... StationCard(...) }` and replace with:

```kotlin
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    modifier = Modifier.fillMaxSize().padding(innerPadding),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    items(
        items = searchResults,
        key = { it.stationuuid },
        span = { item ->
            // Custom span logic is not needed for Adaptive cells; default is fine
        }
    ) { station ->
        StationCard(
            station = station,
            isFavorite = searchFavorites[station.stationuuid] ?: false,
            onStationClick = onStationClick,
            onToggleFavorite = onToggleFavorite,
        )
    }
}
```

Add imports:
```kotlin
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
```

If `searchFavorites` is not already a Map exposed by `SearchViewModel`, keep the simpler form:
```kotlin
isFavorite = false  // or whatever the existing logic produces
```

Adjust to the ViewModel's actual API.

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual visual check**

Run: `./gradlew installDebug`.
- Type a 2-char query → grid renders 2-col.
- No results → "No stations found" centered text still appears.
- Tap card → PlayerScreen.

- [ ] **Step 5: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/SearchScreen.kt
git commit -m "feat(search): LazyColumn -> LazyVerticalGrid(Adaptive(160dp))

2-col grid for results. Empty state preserved."
```

---

## Task 10: FavoritesScreen migration

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/screens/FavoritesScreen.kt`

**Consumes:** New `StationCard` (Task 6).

**Produces:** `LazyVerticalGrid(Adaptive(160.dp))` for favorites. Empty state preserved.

- [ ] **Step 1: Read current FavoritesScreen.kt**

Confirm the `LazyColumn` and empty-state block (icon + text).

- [ ] **Step 2: Replace LazyColumn with LazyVerticalGrid**

```kotlin
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    modifier = Modifier.fillMaxSize().padding(innerPadding),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    items(
        items = favoriteStations,
        key = { it.stationuuid },
    ) { station ->
        StationCard(
            station = station,
            isFavorite = true,
            onStationClick = onStationClick,
            onToggleFavorite = onToggleFavorite,
        )
    }
}
```

Add imports:
```kotlin
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual visual check**

Run: `./gradlew installDebug`.
- Empty favorites: empty state (heart icon + text) centered.
- Add 1+ favorites: 2-col grid appears.

- [ ] **Step 5: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/FavoritesScreen.kt
git commit -m "feat(favorites): LazyColumn -> LazyVerticalGrid(Adaptive(160dp))"
```

---

## Task 11: PlayerScreen rewrite

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt`

**Consumes:** `StationCover` (Task 4), `FavoriteHeart` (Task 5), `GradientBackdrop` for hero background gradient seed.

**Produces:** Full PlayerScreen rewrite — gradient bg, 280dp hero with shadow, big circular play/pause, stop button, loading/error/empty states with retry.

- [ ] **Step 1: Read current PlayerScreen.kt**

Confirm: the route argument `stationId`, `PlayerViewModel` properties used (`currentStation`, `playerState`, `isFavorite(stationId)`, `playStation`, `pause`, `stop`, `toggleFavorite`).

- [ ] **Step 2: Rewrite PlayerScreen.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt
package com.nolansoftware.airadio.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.ui.components.FavoriteHeart
import com.nolansoftware.airadio.ui.components.StationCover
import com.nolansoftware.airadio.ui.components.gradientPaletteFor
import com.nolansoftware.airadio.ui.viewmodels.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    stationId: String,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(stationId) { viewModel.loadStation(stationId) }

    val playerState by viewModel.playerState.observeAsState(PlayerState.Idle)
    val currentStation by viewModel.currentStation.observeAsState()
    val isFavorite by viewModel.isFavorite(stationId).collectAsStateWithLifecycle(initialValue = false)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Now Playing",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { innerPadding ->
        val station = currentStation
        val bgTop = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        val bgBottom = MaterialTheme.colorScheme.surface

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
        ) {
            if (station == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                return@Box
            }

            PlayerContent(
                station = station,
                playerState = playerState,
                isFavorite = isFavorite,
                onPlayPause = {
                    when (playerState) {
                        is PlayerState.Playing -> viewModel.pause()
                        else -> viewModel.playStation(station)
                    }
                },
                onStop = { viewModel.stop() },
                onRetry = { viewModel.playStation(station) },
                onToggleFavorite = { viewModel.toggleFavorite(station) },
            )
        }
    }
}

@Composable
private fun PlayerContent(
    station: Station,
    playerState: PlayerState,
    isFavorite: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Hero artwork
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .size(280.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                )
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            StationCover(
                imageUrl = station.favicon,
                contentDescription = station.name,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 20.dp,
            )
            FavoriteHeart(
                isFavorite = isFavorite,
                onToggle = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }

        // Metadata
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = station.country,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (station.bitrate > 0) {
                Text(
                    text = "${station.bitrate} kbps · ${station.codec}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Controls row
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onStop) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop")
            }
            BigPlayPause(
                isPlaying = playerState is PlayerState.Playing,
                onClick = onPlayPause,
            )
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // Bottom status: loading / error / nothing
        when {
            playerState is PlayerState.Loading -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            playerState is PlayerState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = (playerState as PlayerState.Error).message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun BigPlayPause(isPlaying: Boolean, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 48.dp),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { scale.animateTo(1.1f, spring(stiffness = 400f, dampingRatio = 0.7f)) }
                    scope.launch { scale.animateTo(1f, spring(stiffness = 400f, dampingRatio = 0.7f)) }
                    onClick()
                }
            ),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (scaleIn(initialScale = 0.7f, animationSpec = tween(150)) + fadeIn(tween(150)))
                        .togetherWith(fadeOut(tween(100)))
                },
                label = "play-pause",
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
                )
            }
        }
    }
}
```

Add the missing import for `graphicsLayer`:
```kotlin
import androidx.compose.ui.graphics.graphicsLayer
```

Remove the now-unused `LocalContentColor` import if it triggers an "unused" warning (lint will not flag imports, but IDE may).

The `gradientPaletteFor` import is kept in case a follow-up task wants to switch the gradient to per-station palette. For now, the spec uses theme-driven gradient. Remove the import if you want a clean build — the spec section 3.1 says no Palette extraction, so `gradientPaletteFor` is unused. Drop the import.

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual visual check**

Run: `./gradlew installDebug`.
- Tap a station card → PlayerScreen opens.
- Top: transparent TopAppBar with "Now Playing" + back.
- Background: subtle purple-to-surface vertical gradient.
- Hero: 280dp rounded square with shadow, gradient placeholder until favicon loads.
- Favorite: floating TopEnd on hero.
- Metadata: name (headlineSmall), country, bitrate.
- Big circular Play/Pause button (96dp, primaryContainer) on right; Stop button on left.
- Tap Play: LinearProgressIndicator appears at bottom; pause button animated; haptic.
- Tap Pause: AnimatedContent crossfade to PlayArrow.
- Error state: red error icon + message + Retry button.

- [ ] **Step 5: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt
git commit -m "feat(player): rewrite as centered immersive hero

- Theme-driven vertical gradient (primaryContainer@35% -> surface)
- 280dp rounded square hero with 12dp shadow + primary tint
- FavoriteHeart overlay on hero TopEnd
- Metadata: headlineSmall name, titleMedium country, labelMedium bitrate
- 96dp primaryContainer circular play/pause with 6dp tonalElevation
  - AnimatedContent scaleIn(0.7)+fadeIn(150)/fadeOut(100)
  - Spring scale 1->1.1->1 on tap
- Loading: LinearProgressIndicator (60% width) at bottom
- Error: errorOutline icon + message + FilledTonalButton Retry"
```

---

## Task 12: StationListScreen migration

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/screens/StationListScreen.kt`

**Consumes:** `StationCard` (Task 6), `SkeletonStationCard` (Task 7).

**Produces:** `LazyVerticalGrid(Adaptive(160.dp))` with Paging 3; refresh-load skeleton, refresh-error panel, append-load footer spinner, append-error footer retry, empty state preserved.

- [ ] **Step 1: Read current StationListScreen.kt**

Confirm the existing `LazyColumn` driven by `pagingItems.itemCount`, the `LoadState` handling (refresh/append), and the `SKELETON_STATION_COUNT` constant.

- [ ] **Step 2: Replace the LazyColumn block with LazyVerticalGrid**

```kotlin
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    modifier = Modifier.fillMaxSize().padding(innerPadding),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    if (pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0) {
        items(count = SKELETON_STATION_COUNT) { SkeletonStationCard() }
    } else if (pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ErrorPanel(
                message = (pagingItems.loadState.refresh as LoadState.Error).error.message
                    ?: "Failed to load stations",
                onRetry = { pagingItems.retry() },
            )
        }
    } else {
        items(
            count = pagingItems.itemCount,
            key = pagingItems.itemKey { it.stationuuid },
        ) { index ->
            val station = pagingItems[index] ?: return@items
            StationCard(
                station = station,
                isFavorite = favoritesMap[station.stationuuid] ?: false,
                onStationClick = onStationClick,
                onToggleFavorite = onToggleFavorite,
            )
        }

        when (pagingItems.loadState.append) {
            is LoadState.Loading -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    }
                }
            }
            is LoadState.Error -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        FilledTonalButton(onClick = { pagingItems.retry() }) {
                            Text("Retry loading more")
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}
```

Add imports:
```kotlin
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.paging.compose.itemKey
```

If `ErrorPanel` is a private composable already in this file (it likely is from the previous implementation), keep it as-is. If it was inline text, factor it out as a private composable:
```kotlin
@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) { Text("Retry") }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual paging test**

Run: `./gradlew installDebug`.
- Navigate to StationList from a country tab.
- Initial: skeleton grid renders.
- After load: real grid populates.
- Scroll to bottom: footer spinner appears, more items load.
- Force error (turn off network): footer retry button appears.

- [ ] **Step 5: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/screens/StationListScreen.kt
git commit -m "feat(stationlist): LazyColumn -> LazyVerticalGrid(Adaptive(160dp))

GridItemSpan(maxLineSpan) for error/skeleton/append-load items so they
span the full grid width. Paging 3 + itemKey preserved."
```

---

## Task 13: Navigation chrome (Screen sealed + NavigationBarItem)

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/navigation/Navigation.kt`
- Modify: `app/src/main/java/com/nolansoftware/airadio/MainActivity.kt`

**Produces:** `Screen` sealed class with `iconOutlined` + `iconFilled`; `NavigationBarItem` uses outlined for unselected, filled for selected; `secondaryContainer` indicator.

- [ ] **Step 1: Rewrite Navigation.kt**

```kotlin
// app/src/main/java/com/nolansoftware/airadio/ui/navigation/Navigation.kt
package com.nolansoftware.airadio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    object Search : Screen("search", "Search", Icons.Outlined.Search, Icons.Filled.Search)
    object Browse : Screen("browse", "Browse", Icons.Outlined.Apps, Icons.Filled.Apps)
    object Favorites : Screen("favorites", "Favorites", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite)
    object Player : Screen("player/{stationId}", "Player", Icons.Outlined.Home, Icons.Filled.Home)
    object StationList : Screen("station_list/{type}/{query}", "List", Icons.Outlined.Home, Icons.Filled.Home)

    fun createRoute(stationId: String): String = "player/$stationId"

    companion object {
        fun stationListRoute(type: String, query: String): String =
            "station_list/$type/$query"
    }
}

val bottomNavItems = listOf(Screen.Home, Screen.Search, Screen.Browse, Screen.Favorites)
```

If the original `Screen.createRoute()` had a different signature (e.g., for `station_list`), preserve it verbatim. Add the missing compat method instead of overwriting.

- [ ] **Step 2: Update NavigationBarItem colors in MainActivity.kt**

Locate the `NavigationBarItem` block in `MainActivity.kt` and replace its `colors = NavigationBarItemDefaults.colors(...)` (or add it if absent):

```kotlin
NavigationBarItem(
    selected = isSelected,
    onClick = { navController.navigate(screen.route) { popUpTo(...) ; launchSingleTop = true ; restoreState = true } },
    icon = {
        Icon(
            imageVector = if (isSelected) screen.iconFilled else screen.iconOutlined,
            contentDescription = screen.title,
        )
    },
    label = { Text(screen.title, style = MaterialTheme.typography.labelMedium) },
    alwaysShowLabel = true,
    colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
)
```

Add the import:
```kotlin
import androidx.compose.material3.NavigationBarItemDefaults
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual visual check**

Run: `./gradlew installDebug`.
- Bottom nav: 4 items, outlined when unselected, filled when selected.
- Pill indicator (secondaryContainer) on the selected item.
- Tabbing between screens preserves state.

- [ ] **Step 5: Commit**

```bash
cd /home/nolan/projects/android/AIRadio
git add app/src/main/java/com/nolansoftware/airadio/ui/navigation/Navigation.kt \
        app/src/main/java/com/nolansoftware/airadio/MainActivity.kt
git commit -m "feat(nav): outlined/filled bottom nav consistency

- Screen sealed class: iconOutlined + iconFilled fields
- NavigationBarItem: outlined unselected, filled selected
- Indicator uses secondaryContainer (theme-aligned)
- Always-show labels: labelMedium onSurface/onSurfaceVariant"
```

---

## Task 14: PlayerViewModel lifecycle-aware

**Files:**
- Modify: `app/src/main/java/com/nolansoftware/airadio/ui/viewmodels/PlayerViewModel.kt`

**Produces:** `isFavorite(stationId)` consumed via `collectAsStateWithLifecycle` instead of `collectAsState` in PlayerScreen.

- [ ] **Step 1: Find the existing isFavorite call site in PlayerScreen.kt**

Run: `grep -n "isFavorite" app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt`

- [ ] **Step 2: Confirm lifecycle-runtime-compose is on classpath**

Run: `grep -n "lifecycle-runtime-compose" app/build.gradle.kts`

If absent, add:
```kotlin
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
```

If already present (likely, since the Compose BOM transitively pulls it), skip.

- [ ] **Step 3: Verify Task 11 already uses collectAsStateWithLifecycle**

The PlayerScreen.kt written in Task 11 already imports `collectAsStateWithLifecycle`. Re-check that line:

```bash
grep "collectAsStateWithLifecycle" app/src/main/java/com/nolansoftware/airadio/ui/screens/PlayerScreen.kt
```

If the import and call are present (expected — Task 11 wrote it that way), no PlayerViewModel.kt change is needed. If somehow Task 11 didn't include it, fix PlayerScreen.kt now (no PlayerViewModel.kt edit needed; the lifecycle awareness is consumer-side).

- [ ] **Step 4: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (or skip if no change)**

If no file changed: skip the commit (or commit a no-op documentation note). Otherwise:
```bash
cd /home/nolan/projects/android/AIRadio
git add <changed-file>
git commit -m "chore(player): isFavorite uses collectAsStateWithLifecycle

Lifecycle-aware subscription avoids background collection."
```

---

## Task 15: Final verification

**Files:** none modified; pure verification gate.

- [ ] **Step 1: Build clean**

Run:
```bash
cd /home/nolan/projects/android/AIRadio
./gradlew clean assembleDebug
```
Expected: BUILD SUCCESSFUL. No deprecation warnings introduced beyond pre-existing baseline.

- [ ] **Step 2: Lint clean**

Run:
```bash
./gradlew lint
```
Expected: no new errors. (Some warnings on the rewritten PlayerScreen.kt or theme files may appear — review and either fix or document them in the final commit.)

- [ ] **Step 3: Tests run**

Run:
```bash
./gradlew test
```
Expected: PASS (no tests to run, but the task should exit 0).

- [ ] **Step 4: Install + manual matrix**

Run: `./gradlew installDebug` and exercise each screen on the emulator:

| Screen | Check |
|---|---|
| HomeScreen | Cold start → skeleton grid → fade-in stagger → real grid; recently played carousel scrolls horizontally; hearts toggle with haptic |
| SearchScreen | Type ≥2 chars → grid appears; no results → "No stations found" |
| BrowseScreen | 3 tabs swap; BrowseItemCard ripple covers whole row; chevron visible |
| FavoritesScreen | Empty state → add a favorite → grid appears |
| StationListScreen | Pick country → grid → scroll → footer spinner → more items; force error → footer retry button |
| PlayerScreen | Open station → gradient bg → hero with shadow → tap play → loading bar → audio plays; pause → icon crossfade; favorite on hero overlay; error state shows Retry |
| Bottom nav | Tab between screens; outlined/filled consistent; secondaryContainer indicator |

- [ ] **Step 5: Visual diff baseline**

Take screenshots of the Home screen and Player screen in light + dark themes. Save under `docs/superpowers/specs/2026-09-28-airadio-ui-beautify-design.md` as a comparison reference (optional — append image links or note in commit message).

- [ ] **Step 6: Final commit (if any tweaks)**

If any small fix-ups were needed during verification, commit them. If everything passed cleanly:
```bash
git commit --allow-empty -m "feat(ui): complete AIRadio UI beautification

Theme tokens (Color/Type/Shape), gradient placeholder pipeline,
vertical tile StationCard, immersive PlayerScreen hero, 5 list pages
migrated to LazyVerticalGrid, outlined bottom nav, Coil ImageLoader."
```

---

## Self-Review Notes (post-write)

**Spec coverage:**
- Goals 1-3 (theme, surface containers, StationCard vertical tile) → Tasks 1, 6.
- Goal 4 (list migration) → Tasks 8, 9, 10, 12.
- Goal 5 (HomeScreen Recently Played) → Task 8.
- Goal 6 (SubcomposeAsyncImage + GradientBackdrop) → Tasks 3, 4.
- Goal 7 (PlayerScreen rewrite) → Task 11.
- Goal 8 (FavoriteHeart) → Task 5.
- Goal 9 (bottom nav outlined/filled) → Task 13.
- Goal 10 (Coil ImageLoader) → Task 2.
- Goal 11 (animations) → Tasks 5, 8 (AnimatedStationCardItem), 11.
- BrowseScreen layout preservation → Task 6 (BrowseItemCard upgrade only).
- SkeletonStationCard sync → Task 7.
- PlayerViewModel lifecycle → Task 14.
- Verification → Task 15.

All 11 goals covered. ✓

**Placeholder scan:** No "TBD", "TODO", "implement later", or "similar to Task N" in the plan. ✓

**Type consistency:** `StationCover`, `FavoriteHeart`, `GradientBackdrop`, `StationCard`, `SkeletonStationCard`, `BrowseItemCard` signatures consistent across Tasks 3-7 and their consumers in Tasks 8-12. `Screen` sealed class new fields don't break existing route resolution. ✓

**Review Focus:** All five inputs pinned to specific task verifications. ✓