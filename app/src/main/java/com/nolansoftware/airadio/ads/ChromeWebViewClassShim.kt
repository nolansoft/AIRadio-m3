// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import androidx.window.extensions.core.util.function.Consumer

/**
 * Forces AGP's library-class shrinker to keep
 * `androidx.window.extensions.core.util.function.Consumer` in the APK.
 *
 * Chrome 129+ (used as the WebView provider on BlueStacks and certain
 * emulators) references this class via JNI inside
 * `com.android.webview.chromium.WebViewChromium.loadUrl`. The AdMob SDK
 * 22.6.0's internal WebView (`com.google.android.gms.ads.internal.webview.*`)
 * triggers that code path during `MobileAds.initialize`. AGP cannot detect
 * the Chrome-side JNI reference, so it strips the class as "unused" — leaving
 * the WebView's classloader throwing `ClassNotFoundException` on every
 * `WebView.loadUrl` and breaking all `loadAd()` calls.
 *
 * Holding a `Class<*>` reference here makes the class reachable from app
 * code, which AGP's shrinker honors even when `isMinifyEnabled = false`
 * (library-mode shrinking still runs). The val is `private` so it does not
 * leak into the public API surface.
 *
 * The actual sidecar API classes live in `androidx.window.extensions.core`
 * (transitive dep of `androidx.window:window:1.3.0`); this is the package
 * Chrome's JNI imports.
 */
@Suppress("unused")
private val chromeWebViewConsumerRef: Class<*> = Consumer::class.java
