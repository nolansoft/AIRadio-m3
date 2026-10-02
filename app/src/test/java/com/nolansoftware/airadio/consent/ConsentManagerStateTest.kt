// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.consent

import com.google.android.ump.ConsentInformation.ConsentStatus
import com.google.android.ump.FormError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-logic unit tests for [ConsentManager] state-machine mappers.
 *
 * The hard dependency on `UserMessagingPlatform.getConsentInformation(...)` is a
 * static method on a `final` class, which cannot be faked without Mockito-Inline
 * or Robolectric. To keep the test surface dependency-free (per the M4 brief
 * "prefer fakes / manual test doubles over new deps"), we exercise the pure
 * private helpers `mapStatus(Int, Boolean)` and `mapError(FormError)` via
 * reflection. Production code is not modified — visibility stays `private`.
 *
 * The end-to-end consent flow (initialize + requestConsentFormIfNeeded +
 * showPrivacyOptions) is covered by instrumented tests in the M1 plan §5.2,
 * which require a connected Android device.
 */
class ConsentManagerStateTest {

    private lateinit var manager: ConsentManager

    @Before
    fun setUp() {
        manager = ConsentManager()
    }

    // ---------- initial state ----------

    @Test
    fun initialState_isUnknown() {
        // Fresh ConsentManager exposes Unknown before any UMP callback has fired.
        assertSame(ConsentState.Unknown, manager.state.value)
    }

    @Test
    fun stateFlow_isNonNull_andObservesCurrentValue() {
        // StateFlow contract: never null, initial value is the constructor arg.
        val flow = manager.state
        assertNotNull(flow)
        assertSame(ConsentState.Unknown, flow.value)
    }

    // ---------- mapStatus(Int, Boolean) — five cases ----------

    @Test
    fun mapStatus_unknownStatus_returnsUnknown() {
        // 99 is outside the ConsentStatus annotation constants; should hit else.
        assertSame(ConsentState.Unknown, invokeMapStatus(99, canRequestAds = true))
    }

    @Test
    fun mapStatus_notRequiredStatus_returnsNotRequired() {
        // ConsentStatus.NOT_REQUIRED is the standard non-EEA path.
        assertSame(
            ConsentState.NotRequired,
            invokeMapStatus(ConsentStatus.NOT_REQUIRED, canRequestAds = false)
        )
    }

    @Test
    fun mapStatus_requiredStatus_returnsRequiredWithCanShowFormTrue() {
        // REQUIRED is the EEA pre-consent state; canShowForm should always be true
        // because UMP only emits REQUIRED when a form is available to show.
        val state = invokeMapStatus(ConsentStatus.REQUIRED, canRequestAds = false)
        assertTrue("Expected Required but was $state", state is ConsentState.Required)
        assertTrue((state as ConsentState.Required).canShowForm)
    }

    @Test
    fun mapStatus_obtainedStatus_personalizedTrueWhenCanRequestAdsTrue() {
        // OBTAINED + canRequestAds=true → personalized ads.
        val state = invokeMapStatus(ConsentStatus.OBTAINED, canRequestAds = true)
        assertTrue("Expected Obtained but was $state", state is ConsentState.Obtained)
        assertTrue((state as ConsentState.Obtained).personalized)
    }

    @Test
    fun mapStatus_obtainedStatus_personalizedFalseWhenCanRequestAdsFalse() {
        // OBTAINED + canRequestAds=false → NPA ads. This is the conservative path
        // when in doubt (per the comment in ConsentManager.mapStatus).
        val state = invokeMapStatus(ConsentStatus.OBTAINED, canRequestAds = false)
        assertTrue("Expected Obtained but was $state", state is ConsentState.Obtained)
        assertFalse((state as ConsentState.Obtained).personalized)
    }

    // ---------- mapError(FormError) — exhaustive coverage ----------

    @Test
    fun mapError_internetError_returnsOfflineFallback() {
        // R6 fix: INTERNET_ERROR maps to OfflineFallback (defer ads to next launch).
        val state = invokeMapError(FormError.ErrorCode.INTERNET_ERROR, "no network")
        assertSame(ConsentState.OfflineFallback, state)
    }

    @Test
    fun mapError_internalError_returnsNotRequired() {
        // Non-internet errors fall back to NotRequired (proceed without consent form).
        val state = invokeMapError(FormError.ErrorCode.INTERNAL_ERROR, "boom")
        assertSame(ConsentState.NotRequired, state)
    }

    @Test
    fun mapError_invalidOperation_returnsNotRequired() {
        val state = invokeMapError(FormError.ErrorCode.INVALID_OPERATION, "bad op")
        assertSame(ConsentState.NotRequired, state)
    }

    @Test
    fun mapError_timeout_returnsNotRequired() {
        val state = invokeMapError(FormError.ErrorCode.TIME_OUT, "timed out")
        assertSame(ConsentState.NotRequired, state)
    }

    // ---------- reflection helpers ----------

    /**
     * Reflectively invoke the private `mapStatus(Int, Boolean): ConsentState`.
     * The Int parameter is the unmasked `ConsentStatus` annotation constant; the
     * Boolean is `canRequestAds()` — these are exactly what `requestConsentFormIfNeeded`
     * passes after the UMP success listener fires.
     */
    private fun invokeMapStatus(status: Int, canRequestAds: Boolean): ConsentState {
        val method = ConsentManager::class.java.getDeclaredMethod(
            "mapStatus",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(manager, status, canRequestAds) as ConsentState
    }

    /**
     * Reflectively invoke the private `mapError(FormError): ConsentState`.
     * Uses the real `FormError(int, String)` ctor (public in UMP 3.2.0 —
     * verified via javap on the AAR).
     */
    private fun invokeMapError(code: Int, message: String): ConsentState {
        val method = ConsentManager::class.java.getDeclaredMethod(
            "mapError",
            FormError::class.java
        )
        method.isAccessible = true
        return method.invoke(manager, FormError(code, message)) as ConsentState
    }

    // ---------- extra coverage: all 4 FormError codes are NOT OfflineFallback except INTERNET_ERROR ----------

    @Test
    fun mapError_onlyInternetErrorMapsToOfflineFallback_allOthersMapToNotRequired() {
        // Defensive: locks down the discriminator so a future refactor can't
        // accidentally route TIME_OUT / INTERNAL_ERROR / INVALID_OPERATION to
        // OfflineFallback (which would suppress ads indefinitely).
        val nonInternetCodes = listOf(
            FormError.ErrorCode.INTERNAL_ERROR,
            FormError.ErrorCode.INVALID_OPERATION,
            FormError.ErrorCode.TIME_OUT
        )
        for (code in nonInternetCodes) {
            assertSame(
                "code=$code must map to NotRequired, not OfflineFallback",
                ConsentState.NotRequired,
                invokeMapError(code, "msg")
            )
        }
        // And the inverse: INTERNET_ERROR specifically maps to OfflineFallback.
        assertSame(
            ConsentState.OfflineFallback,
            invokeMapError(FormError.ErrorCode.INTERNET_ERROR, "no net")
        )
    }

    @Test
    fun consentState_dataClassEquality_holds() {
        // Sanity check on the data class contract so the npa mapping in
        // BannerAd.kt can rely on `state is ConsentState.Obtained(state.personalized)`
        // being stable.
        val a = ConsentState.Obtained(personalized = true)
        val b = ConsentState.Obtained(personalized = true)
        val c = ConsentState.Obtained(personalized = false)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun consentState_requiredDataClass_carriesCanShowForm() {
        val r1 = ConsentState.Required(canShowForm = true)
        val r2 = ConsentState.Required(canShowForm = true)
        val r3 = ConsentState.Required(canShowForm = false)
        assertEquals(r1, r2)
        assertFalse(r1 == r3)
    }
}