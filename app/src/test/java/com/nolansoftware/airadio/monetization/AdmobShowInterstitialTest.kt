// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

// Robolectric 4.11.1's maxSdkVersion=34 conflicts with this project's
// targetSdk=36 (see [Task 13 report] § pre-existing baseline). Pinning the
// SDK pins Robolectric to API 33, which it fully supports, and decouples the
// unit test runner from the prod targetSdk until Robolectric adds SDK 36.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
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
            parkShowOn?.let { runBlocking { it.await() } }
        }
        override fun setFullScreenContentListener(listener: FullScreenContentCallback) {
            fullScreenListener = listener
        }
        fun triggerOnAdFailedToShowFullScreenContent() {
            fullScreenListener?.onAdFailedToShowFullScreenContent(AdError(0, "test", "test"))
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
            consents = FakeConsentManager(consentValue).consents,
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

        // Trigger the FullScreenContentCallback.onAdFailedToShowFullScreenContent.
        handle.triggerOnAdFailedToShowFullScreenContent()
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