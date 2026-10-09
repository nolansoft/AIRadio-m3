// SPDX-License-Identifier: Apache-2.0

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
