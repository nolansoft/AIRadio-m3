import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("com.google.dagger.hilt.android")
    id("androidx.navigation.safeargs.kotlin")
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.nolansoftware.airadio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nolansoftware.airadio"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

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
    }

    val adProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(FileInputStream(f))
    }

    signingConfigs {
        create("release") {
            val sp = adProps["airadio.release.keystore.password"] as String? ?: ""
            val ka = adProps["airadio.release.key.alias"] as String? ?: ""
            val kp = adProps["airadio.release.key.password"] as String? ?: ""

            storeFile = rootProject.file(adProps["airadio.release.keystore.path"] as String? ?: "airadio-release.jks")
            storePassword = sp
            keyAlias = ka
            keyPassword = kp

            if (sp.isEmpty() || sp == "CHANGE_ME" ||
                kp.isEmpty() || kp == "CHANGE_ME" ||
                ka.isEmpty()) {
                throw GradleException(
                    "Release signing credentials missing or placeholder. Set airadio.release.* in local.properties."
                )
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["ADMOB_APPLICATION_ID"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "ADMOB_INTER_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")

            val prodAppId = (adProps["airadio.admob.application.id"] as String?) ?: "ca-app-pub-3940256099942544~3347511713"
            val prodBannerId = (adProps["airadio.admob.banner.id"] as String?) ?: "ca-app-pub-3940256099942544/9214589741"
            val prodInterId = (adProps["airadio.admob.interstitial.id"] as String?) ?: "ca-app-pub-3940256099942544/1033173712"

            manifestPlaceholders["ADMOB_APPLICATION_ID"] = prodAppId
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$prodBannerId\"")
            buildConfigField("String", "ADMOB_INTER_ID", "\"$prodInterId\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Compose Compiler 1.5.5 is the verified match for Kotlin 1.9.20. The
    // previous 1.5.3 only supports Kotlin 1.9.10.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
    // Robolectric Compose UI tests need real Android resources (themes, attrs,
    // MaterialTheme attributes). Without this, setContent throws at runtime.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

configure<com.jaredsburrows.license.LicenseReportExtension> {
    generateHtmlReport = true
    copyHtmlReportToAssets = false
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Provides androidx.compose.runtime.livedata.observeAsState used by PlayerViewModel.playerState.
    implementation("androidx.compose.runtime:runtime-livedata")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    // Room-Paging integration: required for DAOs that return
    // androidx.paging.PagingSource. Without this, kapt fails with
    // "To use PagingSource, you must add `room-paging` artifact...".
    implementation("androidx.room:room-paging:2.6.1")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-android-compiler:2.48")
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // ExoPlayer (Media3). media3-session alone does not pull in the
    // ExoPlayer class; RadioPlayerService / PlayerModule import ExoPlayer,
    // and Hilt refuses to process @Inject / @Provides sites unless the
    // type resolves on the compile classpath.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // media3-exoplayer-hls pulls in media3-datasource-hls transitively. Without
    // this, DefaultMediaSourceFactory's reflective HLS factory lookup throws
    // ClassNotFoundException for any .m3u8 stream ("China 华语金曲500首" and
    // similar). DASH / SmoothStreaming can be added the same way if a station
    // ever needs them.
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")

    // Legacy media support library — provides androidx.media.app.NotificationCompat.MediaStyle
    // used by RadioPlayerService for media-style notifications alongside the Media3 session.
    implementation("androidx.media:media:1.7.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Paging 3 — backs the infinite-scroll station list. paging-compose
    // provides collectAsLazyPagingItems() so the LazyColumn can read a
    // PagingData<Station> directly. Version 3.2.1 is compatible with the
    // Compose BOM 2024.12.01 used above; bumping either should re-check.
    implementation("androidx.paging:paging-runtime-ktx:3.2.1")
    implementation("androidx.paging:paging-compose:3.2.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Robolectric: powers JVM-side Android Context for AdmobBannerAdTest's
    // Application-construction sanity test. 4.11.1 works with AGP 8.6 +
    // Kotlin 1.9.20. No Mockito (fakes only) — Mockito would force us to add
    // mockito-inline for the AdMob final classes.
    testImplementation("org.robolectric:robolectric:4.11.1")
    // Compose UI testing on the JVM (via Robolectric). createComposeRule() +
    // onNodeWithTag / getBoundsInRoot / assertHeightIsEqualTo all live here.
    // Pinned to the same Compose BOM as the production Compose dep.
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-manifest is debugImplementation per Compose testing docs — it
    // ships a Compose host Activity used by createComposeRule, not just test
    // classes. Putting it as testImplementation fails the lint
    // TestManifestGradleConfiguration check.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Hilt instrumented-test support: provides @HiltAndroidTest and @TestInstallIn
    // (used by TestMonetizationModule to swap AdmobMonetizationManager for the
    // NoOp variant in androidTest runs).
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.48")

    // AdMob + UMP (M3 Lane A — DOWNGRADED to Kotlin 1.9-compatible versions per user decision)
    // 22.6.0 was the last 22.x release (May 2024) before the Google Mobile Ads SDK
    // bumped its Kotlin metadata to 2.x. Try UMP 3.2.0 first (closer to 4.0.0 API;
    // metadata compatibility re-verified at kapt step).
    implementation("com.google.android.gms:play-services-ads:22.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
    // androidx.window:window — AdMob 22.6.0's internal WebView (com.google.android.gms.ads.internal.webview.*)
    // calls WebView.loadUrl, which on WebView providers built from chromium-Monochrome (Chrome 129+
    // on BlueStacks / certain emulators) reaches a class that references
    // androidx.window.extensions.core.util.function.Consumer. Without this dep, MobileAds.initialize
    // fails with ClassNotFoundException and all subsequent loadAd() calls never fire. minSdk 26 is
    // supported across the 1.x line; 1.3.0 is the stable line that bundles the sidecar API without
    // pulling Kotlin 2.x metadata. Adding it is a no-op on devices that don't need it.
    implementation("androidx.window:window:1.3.0")
    // androidx.window.extensions.core:core — pulled by window:1.3.0 as `runtime` scope only, so it's
    // invisible to the Kotlin compiler. The Consumer class AGP strips is in THIS artifact, not in
    // window itself. Hoisting it to `implementation` makes the class reachable from our code so the
    // library-shrinker keeps it in the dex; combined with the stub reference in
    // ads/ChromeWebViewClassShim.kt, this avoids the AdMob-WebView-init crash on Chrome 129+
    // WebView providers.
    implementation("androidx.window.extensions.core:core:1.0.0")
}

kapt {
    correctErrorTypes = true
}