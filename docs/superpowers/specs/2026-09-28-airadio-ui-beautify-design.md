# AIRadio UI 全面美化 — 设计规格

**Date**: 2026-09-28
**Status**: Pending review
**Author**: Claude (brainstorming session)
**Scope**: 全局焕新,一次提交(方案 A)

---

## Context

AIRadio 当前 UI 层是**白纸状态**——`Theme.kt` 单文件 67 行,只填了浅色 9 个 + 深色 3 个 colorScheme 字段,无 Shape/Type/Color 分文件;**零动画**(全仓 `grep` `animate|tween|spring|Crossfade|AnimatedVisibility` 0 命中);**零 surface container hierarchy**;**零边框零渐变**;**零 LazyVerticalGrid**(全部 LazyColumn);**零 Coil ImageLoader 自定义**;底部导航只有 4 个图标,其中 1 个 outlined 其余 filled 状态不一致。`StationCard` 是横排小卡(64dp favicon + 3 行文本),`PlayerScreen` 是 200dp 圆角方图 + 两个 M3 默认 Button,完全没有沉浸感。

用户的核心诉求是"现代 Material 3 高级感、细腻动效、合理留白、精细卡片层次",本次改动覆盖 theme、组件、播放器、列表、底部导航、Coil 配置,**不动架构层**(不迁移 LiveData → StateFlow,不调整 Domain 模型层),不破坏 ViewModel 绑定与 Hilt 注入。

预期结果:**深紫蓝 Material 3 高级主题 + 渐变骨架封面加载 + 竖排 2 列 grid 卡片 + 居中沉浸式 hero 播放器 + 200-300ms 细腻动效 + outlined 一致化底部导航 + Coil 全局 image loader 配置**。

---

## Goals

1. 拆分 Theme.kt 为 Color / Type / Shape / Theme 四个文件,填充完整 dark scheme 字段
2. 引入 Material 3 surface container hierarchy,所有 elevated 容器改用 `surfaceContainer*` token
3. StationCard 升级为正方形 1:1 竖排 tile(20dp 圆角,tonalElevation 1dp),从横排小卡形态迁移
4. 所有列表页除 BrowseScreen(用 BrowseItemCard 行卡,不适合 grid)外,从 LazyColumn 改为 `LazyVerticalGrid(GridCells.Adaptive(minSize = 160.dp))`
5. HomeScreen 的 Recently Played 保留 LazyRow 横向 carousel,内部 card 用同样的竖排 tile + `fillParentMaxWidth(0.42f)` 适配
6. 引入 `SubcomposeAsyncImage` + `GradientBackdrop` 确定性渐变占位策略(6 套调色板,seed 哈希索引)
7. PlayerScreen 重写为居中沉浸式 hero:280dp 圆角方形封面(20dp 圆角 + 12dp shadow + 主色 ambient/spot tint) + 主色容器渐变背景 + 大圆形 96dp Play/Pause 按钮 + LinearProgressIndicator loading + 带 Retry 的 error state
8. 新增 `FavoriteHeart` 浮层按钮组件,在 StationCard 与 PlayerScreen 复用,支持 haptic + spring 1→1.3→1 缩放 + 颜色 250ms tween + AnimatedContent crossfade 图标切换
9. 底部导航 4 项图标 outlined/filled 一致化(`Icons.Outlined.*` 未选中、`Icons.Filled.*` 选中)
10. Coil 全局 ImageLoader 配置(crossfade 300ms 默认 + 50MB disk cache + `respectCacheHeaders(false)`)
11. 全部动效严格 200-300ms easing,无 spring 物理弹动(收藏按钮除外)

## Non-Goals

- **不迁移** LiveData → StateFlow、`observeAsState` → `collectAsStateWithLifecycle`(只对 `isFavorite(stationId)` 这一处改用 lifecycle-aware)
- **不重写** Domain 模型层 / Repository / UseCase / Mapper
- **不修改** `RadioPlayerService` / `SyncWorker` / 数据层任何代码
- **不引入** 新依赖(Compose BOM 2024.12.01、`material-icons-extended`、`coil-compose 2.7.0` 已在)
- **不修改** Compose BOM 与 Compose Compiler 版本(已验证与 Kotlin 1.9.20 兼容)
- **不重命名** 任何对外暴露的 Composable API,仅扩展 sealed `Screen` class 字段

---

## Architecture & File Structure

### 新增文件

```
app/src/main/java/com/nolansoftware/airadio/ui/
├── theme/
│   ├── Color.kt              (~85 行,命名颜色常量)
│   ├── Type.kt               (~50 行,完整 Typography)
│   └── Shape.kt              (~15 行,Shapes token)
└── components/
    ├── GradientBackdrop.kt   (~55 行,确定性渐变调色板)
    ├── StationCover.kt       (~80 行,封装 SubcomposeAsyncImage)
    └── FavoriteHeart.kt      (~80 行,动画收藏按钮)
```

### 改写文件

```
ui/theme/Theme.kt                 (67 行 → ~50 行,只保留 AIRadioTheme composable)
ui/components/StationCard.kt      (横排小卡 → 竖排 tile)
ui/components/SkeletonStationCard.kt (与新卡形状同步)
ui/screens/PlayerScreen.kt        (211 行 → ~280 行,全部重写)
ui/screens/HomeScreen.kt          (LazyColumn → grid + carousel)
ui/screens/SearchScreen.kt        (LazyColumn → grid)
ui/screens/FavoritesScreen.kt     (LazyColumn → grid)
ui/screens/StationListScreen.kt   (LazyColumn + paging → grid + paging)
ui/screens/BrowseScreen.kt         (BrowseItemCard 样式升级,layout 不动)
ui/navigation/Navigation.kt       (Screen 加 iconOutlined/iconFilled 字段)
MainActivity.kt                   (NavigationBarItem colors 调整)
AIRadioApp.kt                     (实现 ImageLoaderFactory)
ui/viewmodels/PlayerViewModel.kt  (isFavorite 改用 collectAsStateWithLifecycle)
```

---

## Theme System

### Color.kt

以 Material 3 tonal palette 为骨架,**基础色 = M3 默认紫色**(Purple40/Purple80),完整字段填充:

**Light scheme**:`primary = Purple40`,`onPrimary = Color.White`,`primaryContainer = Purple90`,`onPrimaryContainer = Purple10`;`secondary = Violet40` + `secondaryContainer = Violet90`;`tertiary = Sky40` + `tertiaryContainer = Sky90`;`background/surface = 0xFFFFFBFE`;`surfaceVariant = 0xFFE7E0EC`;**`surfaceContainer = 0xFFF3EDF7`,`surfaceContainerLow = 0xFFF7F2FA`,`surfaceContainerHigh = 0xFFECE6F0`,`surfaceContainerHighest = 0xFFE6E0E9`**;`onSurfaceVariant = 0xFF49454F`;`outline = 0xFF79747E`;`outlineVariant = 0xFFCAC4D0`;`onBackground/onSurface = 0xFF1C1B1F`。

**Dark scheme**:`primary = Purple80`,`onPrimary = Purple20`,`primaryContainer = Purple30`,`onPrimaryContainer = Purple90`;`secondary = Violet80` + `secondaryContainer = Violet30`;`tertiary = Sky80` + `tertiaryContainer = Sky30`;`background/surface = 0xFF1C1B1F`;`surfaceVariant = 0xFF49454F`;**`surfaceContainer = 0xFF211F26`,`surfaceContainerLow = 0xFF1D1B22`,`surfaceContainerHigh = 0xFF2B2930`,`surfaceContainerHighest = 0xFF36343B`**;`onSurfaceVariant = 0xFFCAC4D0`;`outline = 0xFF938F99`;`outlineVariant = 0xFF49454F`;`onBackground/onSurface = 0xFFE6E1E5`。

**关键变化**:
- 全部 surface container 字段首次填充,后续 Card/Surface 全部走这些 token
- 保留 `0xFFFFFBFE`/`0xFF1C1B1F` 作为 background/surface(非纯黑/纯白)
- Dark scheme 补齐全字段,不再依赖 M3 默认 fallback

### Type.kt

完整 M3 Typography,字重差异化:

```kotlin
display{Large,Medium,Small}: SemiBold, 57/45/36 sp
headline{Large,Medium,Small}: SemiBold, 32/28/24 sp
title{Large,Medium,Small}: Medium, 22/16/14 sp
body{Large,Medium,Small}: Normal, 16/14/12 sp(letterSpacing 0.5/0.25/0.4)
label{Large,Medium,Small}: Medium, 14/12/11 sp(letterSpacing 0.1/0.5/0.5)
```

letterSpacing 走 M3 spec 默认值;`fontFamily = FontFamily.Default`(系统 sans)。

### Shape.kt

```kotlin
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),  // 卡片主圆角
    extraLarge = RoundedCornerShape(28.dp)
)
```

所有 `RoundedCornerShape(8/12/16/20.dp)` 硬编码替换为 `MaterialTheme.shapes.{small,medium,large}`。

### Theme.kt

```kotlin
@Composable
fun AIRadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,  // 默认开启,Android 12+ 走 Material You
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

**关键变化**:
- 状态栏/导航栏改为 `Color.Transparent`,由 Compose 内 Scaffold/Box 背景决定视觉(配合 `enableEdgeToEdge()`)
- `isAppearanceLightStatusBars = !darkTheme`(原代码反向,修正)
- 新增 `isAppearanceLightNavigationBars = !darkTheme`

---

## Component Layer

### GradientBackdrop.kt

确定性 6 套调色板渐变背景,seed 哈希索引:

```kotlin
private data class GradientPalette(val start: Color, val end: Color)

private val palettes = listOf(
    GradientPalette(Color(0xFF6750A4), Color(0xFF7F67BE)),
    GradientPalette(Color(0xFF625B71), Color(0xFF7A6E89)),
    GradientPalette(Color(0xFF4A636C), Color(0xFF6B7E87)),
    GradientPalette(Color(0xFF7D5260), Color(0xFF976C7A)),
    GradientPalette(Color(0xFF4F378B), Color(0xFF6E50B3)),
    GradientPalette(Color(0xFF005AC1), Color(0xFF3478E0)),
)

fun gradientPaletteFor(seed: String): GradientPalette {
    val idx = (seed.hashCode().toUInt() % palettes.size.toUInt()).toInt()
    return palettes[idx]
}
```

对外 `GradientBackdrop(seed: String, modifier, cornerRadius: Dp)`:用 `Brush.linearGradient`,`Offset.Zero → Offset.Infinite`(45 度方向)。

### StationCover.kt

统一封面加载入口,封装 `SubcomposeAsyncImage` + 渐变占位 + fallback:

```kotlin
@Composable
fun StationCover(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    fallbackIcon: ImageVector = Icons.Outlined.Radio
)
```

四个 slot 全部走 `GradientBackdrop`:
- `loading`: 显示 url(stale),crossfade 300ms 进场
- `error`: 同 loading,显式表示失败
- `success`: `Image(painter = state.painter, ...)` 用同一 clip
- `fallback` (imageUrl 为 null): 渐变背景 + 中心 `Icons.Outlined.Radio`(48dp,`onSurfaceVariant` alpha 0.4f)

`ImageRequest`:`crossfade(300)` 显式声明,即使全局 default 改动也不退化。

### FavoriteHeart.kt

动画收藏按钮,StationCard 与 PlayerScreen 共用:

```kotlin
@Composable
fun FavoriteHeart(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
)
```

实现细节:
- 40dp `Box` + `CircleShape` 剪裁 + 半透明 `surface.copy(alpha=0.7f)` 圆底(防止压住封面图)
- 内部 20dp `Icon`,`AnimatedContent` 切换 `Icons.Filled.Favorite ↔ Icons.Outlined.FavoriteBorder`
- `animateColorAsState(tween(250))` 颜色 tween(primary ↔ onSurfaceVariant)
- 点击触发 `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` + `Animatable.animateTo(1.3f)` → `1f` 用 `spring(stiffness=400f, dampingRatio=0.6f)`
- `graphicsLayer { scaleX = scale.value; scaleY = scale.value }` 应用缩放
- `clickable(indication = ripple(bounded = false, radius = 20.dp))` 给 ripple 视觉

### StationCard.kt 重写

```kotlin
@Composable
fun StationCard(
    station: Station,
    isFavorite: Boolean,
    onStationClick: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit,
    modifier: Modifier = Modifier
)
```

结构:`Surface(MaterialTheme.shapes.large, surfaceContainerLow, tonalElevation=1dp)` → `Column` → `Box(Modifier.aspectRatio(1f))` 放 `StationCover` + `FavoriteHeart` 右上角浮层(`Modifier.align(TopEnd).padding(8.dp)`) → `Column(padding horizontal=12.dp, vertical=10.dp)` 三行 Text:

1. `station.name` — `titleMedium`, `onSurface`, `maxLines=1`, ellipsis
2. `station.country` — `bodySmall`, `onSurfaceVariant`, `maxLines=1`
3. `bitrate` — 仅 `bitrate > 0` 时显示,`labelSmall`, `onSurfaceVariant`,内容 `"${station.bitrate} kbps"`

`clickable` 走 `interactionSource + ripple` 组合,保留默认交互。

### SkeletonStationCard.kt 同步

整体形状与新 StationCard 一致:竖排 tile,`aspectRatio(1f)`,`MaterialTheme.shapes.large`,**无 shimmer**(保留现有注释 "intentional, no shimmer to avoid extra dep"),所有占位块用 `surfaceContainerHigh` 配色。

### BrowseItemCard 升级

`StationCard.kt` 内的 BrowseItemCard(lines 113-147)同步升级:
- 圆角 `RoundedCornerShape(8.dp)` → `MaterialTheme.shapes.medium` (16dp)
- `elevation = 2.dp` → `tonalElevation = 1.dp`
- 内边距 `16dp` → `20dp horizontal / 14dp vertical`
- count 文本 `bodyMedium` → `labelMedium`
- 右侧加 `Icons.AutoMirrored.Filled.KeyboardArrowRight`(onSurfaceVariant, 24dp)
- 整卡 `clickable` + ripple 范围扩展到全卡

---

## Layout Layer — 5 个列表页

### HomeScreen

外层 LazyColumn 结构保留,内部三段:

| 段 | 容器 | Card 形态 |
|---|---|---|
| Recently Played | `LazyRow(parentWidth, spacedBy 12dp)` | `StationCard(fillParentMaxWidth(0.42f))` |
| Popular | `LazyVerticalGrid(GridCells.Adaptive(minSize=160.dp), spacedBy 12dp)` | 完整竖排 tile |
| Local | 同 Popular,数据 `take(10)` | 同上 |

Section header:`Text(titleLarge SemiBold, padding start=20dp top=20dp bottom=8dp end=20dp)`。
Grid `contentPadding = PaddingValues(horizontal=16dp, vertical=8dp)`。
Skeleton: 6 个 `SkeletonStationCard` 在 Popular/Local 首次加载时显示;Recently Played 不显示 skeleton(基于 Room DAO 同步读)。

### SearchScreen

`Scaffold(topBar = SearchBar) + Column + LazyVerticalGrid(Adaptive(160dp), spacedBy 12dp)`。
保留 hint(<2 字符)、empty(no results)、无 loading 状态(数据从 Room Flow 同步获取)。
Section header 仅在有结果时显示"Results for \"$query\""。

### FavoritesScreen

`Scaffold(topBar = TopAppBar) + LazyVerticalGrid(Adaptive(160dp), spacedBy 12dp)`。
Empty state 保留(64dp `Icons.Outlined.FavoriteBorder` + 居中 `bodyLarge onSurfaceVariant` 文本)。

### StationListScreen

`LazyVerticalGrid(Adaptive(160dp), spacedBy 12dp)` + Paging 3。
`items(pagingItems.itemCount, key = pagingItems.itemKey { it.stationuuid })`。
LoadState 完整保留(已有):`refresh is Loading && itemCount==0` → skeleton;`refresh is Error && itemCount==0` → `ErrorPanel` + Retry;`append is Loading` → footer `CircularProgressIndicator`;`append is Error` → footer Retry Button;空成功 → "No stations found"。

### BrowseScreen

**保持 LazyColumn**(BrowseItemCard 是 row 形态,不适合 grid)。
Tab row 保持原状(Countries/Languages/Tags);应用 BrowseItemCard 升级;Skeleton row 在 syncState=Syncing 且 list 为空时显示。

---

## List Item Entry Animation (细腻克制)

Helper composable 包装 StationCard:

```kotlin
@Composable
private fun AnimatedStationCardItem(
    index: Int,
    station: Station,
    isFavorite: Boolean,
    onClick: (Station) -> Unit,
    onToggleFavorite: (Station) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 30L)  // stagger 30ms/项
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) +
                slideInVertically(initialOffsetY = { it / 10 }, animationSpec = tween(180)),
        exit = fadeOut()
    ) {
        StationCard(station, isFavorite, onClick, onToggleFavorite)
    }
}
```

**作用域**: 仅 grid 首次入场应用,Recently Played carousel 不应用(横向移动已自然动效)。

---

## PlayerScreen.kt

### 整体结构

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val playerState by viewModel.playerState.observeAsState()
    val currentStation by viewModel.currentStation.observeAsState()
    val stationId = ...
    val isFavorite by viewModel.isFavorite(stationId).collectAsStateWithLifecycle(initial = false)

    val bgTop = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    val bgBottom = MaterialTheme.colorScheme.surface

    Scaffold(
        topBar = { TopAppBar(...) },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
        ) {
            when {
                currentStation == null -> LoadingState()
                else -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ArtworkHero(...)
                    MetadataBlock(...)
                    Spacer(Modifier.weight(1f))
                    ControlsRow(...)
                    Spacer(Modifier.weight(0.5f))
                    BottomStatus(...)
                }
            }
        }
    }
}
```

### ArtworkHero(280dp 圆角方形 + 12dp shadow + 主色 ambient/spot tint)

```kotlin
Box(
    Modifier
        .size(280.dp)
        .shadow(
            elevation = 12.dp,
            shape = RoundedCornerShape(20.dp),
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        )
        .clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
) {
    StationCover(
        imageUrl = currentStation!!.favicon,
        contentDescription = currentStation!!.name,
        modifier = Modifier.fillMaxSize(),
        cornerRadius = 20.dp
    )
    FavoriteHeart(
        isFavorite = isFavorite,
        onToggle = { viewModel.toggleFavorite(currentStation!!) },
        modifier = Modifier.align(TopEnd).padding(12.dp)
    )
}
```

### MetadataBlock(标题/国家/码率,spacing=6dp)

`headlineSmall SemiBold` for name;`titleMedium onSurfaceVariant` for country;`labelMedium onSurfaceVariant` for `"${bitrate} kbps · ${codec}"`,仅 bitrate > 0 时显示。

### ControlsRow(Stop TextButton + 96dp Play/Pause Surface)

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = CenterVertically) {
    TextButton(onClick = { viewModel.stop() }) {
        Icon(Icons.Outlined.Stop, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Stop")
    }
    val isPlaying = playerState is PlayerState.Playing
    Surface(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .clickable(...) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch { scale.animateTo(1.1f, spring(stiffness=400f, dampingRatio=0.7f)) }
                scope.launch { scale.animateTo(1f, spring(stiffness=400f, dampingRatio=0.7f)) }
                if (isPlaying) viewModel.pause() else viewModel.playStation(station)
            },
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shape = CircleShape
    ) {
        Box(contentAlignment = Center) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (scaleIn(initialScale = 0.7f, tween(150)) + fadeIn(tween(150)))
                        .togetherWith(fadeOut(tween(100)))
                }
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                )
            }
        }
    }
}
```

### BottomStatus(Loading / Error / Spacer)

- `LoadingState`: 当 station 为 null 时,全屏居中 `CircularProgressIndicator(strokeWidth=4.dp, size=48.dp)`
- `Loading` state(有 station 但正在缓冲):底部 `LinearProgressIndicator(Modifier.fillMaxWidth(0.6f), primary)`。**Play/Pause 按钮保持 enabled**(避免给用户"按钮坏了"的错觉);Stop 按钮保持 enabled
- `Error` state: `Column(horizontalAlignment=Center)` `Icons.Outlined.ErrorOutline` 32dp `error` + `bodyMedium error` + `FilledTonalButton` Retry → `playStation(station)`

### TopAppBar

```kotlin
TopAppBar(
    title = { Text("Now Playing", style = titleMedium) },
    navigationIcon = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    },
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        titleContentColor = MaterialTheme.colorScheme.onSurface
    )
)
```

---

## Navigation Chrome

### Navigation.kt

`Screen` sealed class 扩展:

```kotlin
sealed class Screen(
    val route: String,
    val title: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector
) {
    object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    object Search : Screen("search", "Search", Icons.Outlined.Search, Icons.Filled.Search)
    object Browse : Screen("browse", "Browse", Icons.Outlined.Apps, Icons.Filled.Apps)
    object Favorites : Screen("favorites", "Favorites", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite)
    object Player : Screen("player/{stationId}", "Player", Icons.Outlined.Home, Icons.Filled.Home)
    object StationList : Screen("station_list/{type}/{query}", "List", Icons.Outlined.Home, Icons.Filled.Home)
}
```

`bottomNavItems = listOf(Home, Search, Browse, Favorite)` 不变。`createRoute(...)` 函数签名不变。

### MainActivity.kt NavigationBarItem

```kotlin
NavigationBarItem(
    selected = isSelected,
    onClick = { navController.navigate(screen.route) { ... } },
    icon = { Icon(if (isSelected) screen.iconFilled else screen.iconOutlined, contentDescription = screen.title) },
    label = { Text(screen.title, style = MaterialTheme.typography.labelMedium) },
    alwaysShowLabel = true,
    colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
)
```

---

## Coil Configuration

### AIRadioApp.kt

实现 `ImageLoaderFactory`:

```kotlin
@HiltAndroidApp
class AIRadioApp : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(300)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
```

`ImageLoaderFactory` 接口由 Coil 自动检测,无需手动 `Coil.setImageLoader(...)`。`lifecycle-runtime-compose` 用于 `collectAsStateWithLifecycle`,Material BOM 2024.12 已传递引入,如缺失需在 `app/build.gradle.kts` 显式加一行。

---

## Animation Specs Summary

| 动效 | 触发 | Spec |
|---|---|---|
| Favorite 颜色 | `isFavorite` 切换 | `animateColorAsState(tween(250))` |
| Favorite 图标 | `isFavorite` 切换 | `AnimatedContent` + scaleIn(0.6) + fadeIn(150ms)/fadeOut(150ms) |
| Favorite 缩放 | 点击 | `spring(stiffness=400f, dampingRatio=0.6f)` 1→1.3→1 |
| Play/Pause 图标 | `isPlaying` 切换 | `AnimatedContent` + scaleIn(0.7) + fadeIn(150ms)/fadeOut(100ms) |
| Play/Pause 按钮 | 点击 | `spring(stiff=400f, damping=0.7f)` 1→1.1→1 |
| Cover 加载 | Coil 状态变更 | `ImageRequest.crossfade(300)` + `newImageLoader.crossfade(300)` |
| Grid 卡片入场 | 首次出现 | `AnimatedVisibility(fadeIn(120ms) + slideInVertically(it/10, 180ms))`,stagger 30ms/项 |
| 点击 haptic | 收藏 / 播放 | `HapticFeedbackType.LongPress` |

**约束**: 所有 tween duration 严格在 100-300ms;spring 仅用于按钮点击反馈,**不用于入场或过渡**(避免"营销 app"感)。

---

## PlayerViewModel 微调

只改 1 行:`isFavorite(stationId)` 收集从 `collectAsState(initial=false)` → `collectAsStateWithLifecycle(initial=false)`。

**不迁移**:`playerState: LiveData<PlayerState>` → StateFlow(超出 scope);`isFavorite(stationId)` 仍是返回 `StateFlow` 的方法(每调一次新建一个,文案提示但不改)。

---

## Verification

### 编译与静态检查

```bash
cd /home/nolan/projects/android/AIRadio
./gradlew assembleDebug      # 必须 0 error,允许 0 warning
./gradlew lint                # 不出新错,允许本次新增的 suppression(若需要)
./gradlew test                # 0 test,跑通即可
```

### 设备实测清单(minSdk 26 emulator + Android 14 真机)

| 页面 | 检查点 |
|---|---|
| HomeScreen | Recently Played carousel 渲染;Popular/Local 2 列 grid 渲染;卡片点击 ripple 顺畅;卡片入场 stagger 可见 |
| SearchScreen | 输入 ≥2 字符触发搜索;grid 渲染;空结果 empty state |
| BrowseScreen | 3 个 tab 切换;BrowseItemCard ripple 范围覆盖全卡 |
| FavoritesScreen | 空状态正确显示;add favorite 后 grid 渲染;空 grid 不闪 |
| StationListScreen | paging 加载;grid + skeleton + retry 都正确;append footer 加载更多 |
| PlayerScreen | 点 station → 进入 hero 页面;点 Play → LinearProgressIndicator loading → 播放;点 Pause → 图标 AnimatedContent crossfade;stop → 回到 idle;favorite 浮在封面上 |
| 主题切换 | Settings → Dark theme,所有页面 gradient bg / surfaceContainer 正确;透明 status bar / nav bar 不闪 |
| 动态色 | Android 12+ 切换壁纸 → 重启 app,新配色生效 |
| 收藏动画 | 点卡片右上心形 → spring 缩放 + 颜色 tween + 图标 AnimatedContent;haptic 有触感 |

### 截图对比(用户视角主观比较"高级感")

重构前 vs 重构后:HomeScreen 截图、PlayerScreen 截图(dark + light 各一张)。

---

## Risks & Mitigations

| 风险 | 缓解 |
|---|---|
| `Modifier.shadow()` 在低端设备/某些 GPU 上性能损耗 | 回退方案:用 `tonalElevation=8.dp` + `surfaceContainerHighest` 配色代替 |
| `collectAsStateWithLifecycle` 需要 `lifecycle-runtime-compose` 依赖 | Material BOM 2024.12 已传递引入;若缺失在 `app/build.gradle.kts` 加 `implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")` |
| SubcomposeAsyncImage 在快速滚动 grid 时 bitmap 复用可能闪 | Painter 由 Coil 缓存层管理;若闪,把 StationCover 改回 AsyncImage + placeholder 用 GradientBackdrop snapshot(损失 fade-in loading slot) |
| LazyVerticalGrid + Paging 3 的 `items(pagingItems.itemCount, key = ...)` 在某些版本下表现 | 已是官方推荐写法,无需特殊处理 |
| `Modifier.shadow()` shape 与 clip 路径不匹配会闪边;测试低配 shadow corner case | shadow shape = `RoundedCornerShape(20.dp)` 与外层 `.clip(RoundedCornerShape(20.dp))` 严格一致 |

### 回滚策略

若整体评估失败:`git reset --hard HEAD~1`(方案 A 单提交,回滚干净)。
若局部问题:按文件 revert 到 HEAD。

---

## Out of Scope (后续可考虑)

- LazyVerticalGrid 内的"瀑布流"卡片高度(目前统一 aspectRatio=1f)
- Hilt-managed `ImageLoader` 注入(目前 Application 单例足够)
- LiveData → StateFlow 全面迁移(架构性工作,需独立 spec)
- Coil SVG / GIF 支持(目前 favicon 都是 PNG/ICO)
- 自定义 fontFamily(Material 3 Default 已够;若上 Google Sans / Roboto Flex 需单独 spec)
- 跨屏共享 ViewModel(PlayerViewModel 已经是 process-wide singleton,无需变)