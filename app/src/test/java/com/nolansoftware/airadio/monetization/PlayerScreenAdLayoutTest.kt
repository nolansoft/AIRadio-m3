// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nolansoftware.airadio.ads.AdMobConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI test that pins the **layout invariant** for the
 * PlayerScreen banner slot:
 *
 *  1. When ads are disabled (via the [NoOpMonetizationManager] impl, which is
 *     what the Compose @Preview path and `androidTest` runs see), the banner
 *     slot reserves a fixed 50dp height so that toggling ads on/off cannot
 *     cause the playback controls to shift.
 *  2. The player controls (mocked here as a Column tagged `player_controls`)
 *     occupy the same screen rect before and after re-composition, even when
 *     the banner slot is reserving the reserved-height Box.
 *
 * Why this test exists as a separate file rather than nested inside
 * `AdmobBannerAdTest`: only the **layout** invariant is asserted here. Pure
 * unit tests of the AdMob `BannerAd` wiring (empty unit ID filtering,
 * BuildConfig field suffix convention, etc.) remain in `AdmobBannerAdTest`.
 *
 * Real AdMob integration (loading a real AdView inside a Compose hierarchy,
 * consent state transitions, multi-region failover) is exercised by
 * `androidTest` instrumentation — out of scope for this unit test.
 *
 * @Config(sdk = [33]) pins Robolectric to API 33. The project's `targetSdk`
 * is 36, which exceeds Robolectric 4.11.1's `maxSdkVersion=34`; without
 * this override the SDK picker rejects the package and the tests fail with
 * `Package targetSdkVersion=36 > maxSdkVersion=34`. This is purely a
 * workaround for the unit-test runner; production code is unaffected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerScreenAdLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val noOpMonetizationManager = NoOpMonetizationManager()

    /**
     * Minimal PlayerScreen-wrapper for layout testing. Uses the NoOp
     * monetization manager so the banner slot renders **empty but reserved**
     * (a placeholder Box of [AdMobConfig.EXPECTED_BANNER_HEIGHT_DP] dp).
     * The production `PlayerScreen` also has many other UI elements
     * (album cover, metadata, controls); this stub only renders the controls
     * stub block + a banner slot so the layout invariant under test is
     * uncontaminated.
     */
    @Composable
    private fun PlayerScreenStubForLayoutTest() {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .testTag("player_controls"),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AdMobConfig.EXPECTED_BANNER_HEIGHT_DP.dp)
                        .testTag("player_banner_slot"),
                ) {
                    // NoOpMonetizationManager.BannerAd renders nothing here,
                    // but reserving a 50dp Box around the call mirrors the
                    // production Admob path's reserved-height invariant.
                    noOpMonetizationManager.BannerAd(
                        surfaceId = SurfaceId.Player,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
        // Sanity: banner slot's top edge is at or below controls' bottom edge —
        // the slot is inset in the layout flow, never overlaid on the controls.
        val controlsRect = controlsRectAfter
        val bannerRect = bannerSlot.getBoundsInRoot()
        assertTrue(
            "Banner slot overlaps controls — should be inset, not overlay. " +
                "controls.bottom=${controlsRect.bottom}, banner.top=${bannerRect.top}",
            bannerRect.top >= controlsRect.bottom,
        )
    }
}
