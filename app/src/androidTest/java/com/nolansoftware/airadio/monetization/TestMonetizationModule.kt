// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import dagger.Binds
import dagger.Module
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
