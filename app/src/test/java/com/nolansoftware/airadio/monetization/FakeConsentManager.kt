// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import com.nolansoftware.airadio.consent.ConsentManager
import com.nolansoftware.airadio.consent.ConsentState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-rolled test double for [ConsentManager].
 *
 * The brief assumed ConsentManager was an interface (the `: ConsentManager`
 * inheritance pattern) — in fact it is a final class with a private `_state`
 * MutableStateFlow, so subclassing is rejected by the Kotlin compiler.
 * Driving the existing real ConsentManager via reflection on `_state` keeps
 * the test source set independent of Mockito (no final-class inline-mock
 * gymnastics) and avoids touching production code outside this task's scope.
 *
 * Public surface used by every `AdmobMonetizationManager` test (Tasks 6, 7,
 * 9, …):
 *
 *     val mgr = AdmobMonetizationManager(
 *         context = ...,
 *         consents = FakeConsentManager(initial = true).consents,
 *     )
 *
 *     fake.setValue(false) // flip at runtime
 */
class FakeConsentManager(initial: Boolean = false) {
    val consents: ConsentManager = ConsentManager().also { applyState(it, initial) }

    fun setValue(v: Boolean) { applyState(consents, v) }

    private fun applyState(target: ConsentManager, canRequestAds: Boolean) {
        val field = ConsentManager::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(target) as MutableStateFlow<ConsentState>
        state.value =
            if (canRequestAds) ConsentState.Obtained(personalized = true)
            else ConsentState.Unknown
    }
}
