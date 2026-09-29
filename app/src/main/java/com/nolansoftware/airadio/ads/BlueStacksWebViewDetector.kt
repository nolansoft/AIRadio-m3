// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Detects emulators / WebView configurations that are known to break
 * `MobileAds.initialize` because the WebView provider references
 * `androidx.window.extensions.core.util.function.Consumer` from a JNI path
 * but its classloader cannot resolve the class.
 *
 * Confirmed-broken configurations observed on emulator images:
 *   - BlueStacks (com.bluestacks.* installed)
 *   - uncube / cloud emulators (com.uncube.launcher3 installed) — the
 *     device spoofs OnePlus NE2211 build props but ships Chrome 129 as the
 *     WebView provider with the same isolated-classloader behavior.
 *   - Any image where the WebView provider is com.android.chrome:>=129 and
 *     the host app's androidx.window extensions cannot reach the WebView's
 *     own classloader.
 *
 * Used by [com.nolansoftware.airadio.consent.ConsentManager] to short-circuit
 * `MobileAds.initialize` on broken emulators so the app stays usable (just
 * ad-free) instead of crashing inside the AdMob WebView init.
 */
object BlueStacksWebViewDetector {

    private const val TAG = "EmuWV"

    // Package fingerprints for the two known-broken emulator families.
    // Add more entries here when new emulator images are reported.
    private val BROKEN_EMU_PACKAGES = listOf(
        // BlueStacks
        "com.bluestacks.settings",
        "com.bluestacks.launcher",
        "com.bluestacks.appfinder",
        // uncube / cloud Android emulators
        "com.uncube.launcher3"
    )

    /**
     * Returns true if the current environment's WebView provider is known
     * to be incompatible with AdMob SDK 22.6.0's WebView init path.
     *
     * Conservative — false positives only disable ads (no behavioral risk);
     * false negatives just leave the existing ClassNotFoundError in place
     * (the user will see no ads, same as before the check was added).
     */
    fun isBrokenWebViewEnvironment(context: Context): Boolean {
        if (hasBrokenEmuPackages(context.packageManager)) {
            Log.i(TAG, "Broken-emulator package(s) detected; flagging WebView as broken")
            return true
        }
        return false
    }

    private fun hasBrokenEmuPackages(pm: PackageManager): Boolean =
        BROKEN_EMU_PACKAGES.any { name ->
            try {
                pm.getPackageInfo(name, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
}