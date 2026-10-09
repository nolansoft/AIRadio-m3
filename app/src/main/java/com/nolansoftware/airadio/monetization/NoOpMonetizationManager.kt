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