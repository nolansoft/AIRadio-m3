// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.consent

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm.OnConsentFormDismissedListener
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.ConsentStatus
import com.google.android.ump.ConsentInformation.OnConsentInfoUpdateFailureListener
import com.google.android.ump.ConsentInformation.OnConsentInfoUpdateSuccessListener
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import com.nolansoftware.airadio.ads.BlueStacksWebViewDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class ConsentState {
    object Unknown : ConsentState()
    object NotRequired : ConsentState()
    data class Required(val canShowForm: Boolean) : ConsentState()
    data class Obtained(val personalized: Boolean) : ConsentState()
    /** R6: offline first-launch in regulated region — defer ads until next launch. */
    object OfflineFallback : ConsentState()

    /**
     * WebView provider on this device is incompatible with AdMob SDK 22.6.0's
     * init path (BlueStacks + Chrome 129 is the confirmed case). The app stays
     * usable but ads are disabled — calling `MobileAds.initialize` would
     * throw `NoClassDefFoundError` for
     * `androidx.window.extensions.core.util.function.Consumer` inside the
     * isolated WebView classloader.
     */
    object WebViewIncompatible : ConsentState()
}

@Singleton
class ConsentManager @Inject constructor() {
    private var consentInformation: ConsentInformation? = null
    private val _state = MutableStateFlow<ConsentState>(ConsentState.Unknown)
    val state: StateFlow<ConsentState> = _state

    // Mirrors ConsentInformation.privacyOptionsRequirementStatus == REQUIRED.
    // Only true on devices where UMP has confirmed the user can re-open the
    // privacy options form (typically EEA after consent is obtained). On
    // WebViewIncompatible / OfflineFallback / non-EEA devices this stays false
    // so the UI can disable the "Manage privacy options" button instead of
    // letting the user tap a button that silently closes the sheet.
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired

    fun initialize(app: Application) {
        // UMP 3.2.0: getConsentInformation(Context) is context-safe. The actual
        // requestConsentInfoUpdate requires an Activity, so we defer that call to
        // requestConsentFormIfNeeded().
        consentInformation = UserMessagingPlatform.getConsentInformation(app)
    }

    fun requestConsentFormIfNeeded(activity: Activity, onComplete: (ConsentState) -> Unit) {
        val info = consentInformation
            ?: UserMessagingPlatform.getConsentInformation(activity.applicationContext)
                .also { consentInformation = it }

        // R14: BlueStacks + Chrome 129 WebView — AdMob SDK 22.6.0's
        // MobileAds.initialize calls WebView.loadUrl through AdMob's internal
        // WebView, which on this combination throws NoClassDefFoundError for
        // androidx.window.extensions.core.util.function.Consumer (the class
        // lives in the WebView's own classloader scope, isolated from the
        // host app). Skip MobileAds + the consent form entirely so the app
        // stays usable; the rest of the UI does not render banners once the
        // state is WebViewIncompatible.
        if (BlueStacksWebViewDetector.isBrokenWebViewEnvironment(activity)) {
            _state.value = ConsentState.WebViewIncompatible
            // Explicit: on broken emulators UMP is bypassed entirely, so
            // privacyOptionsRequirementStatus is never read from the server.
            // The button stays disabled by default (false).
            _privacyOptionsRequired.value = false
            onComplete(ConsentState.WebViewIncompatible)
            return
        }

        val params = buildRequestParams(activity)

        info.requestConsentInfoUpdate(
            activity,
            params,
            OnConsentInfoUpdateSuccessListener {
                _privacyOptionsRequired.value =
                    info.privacyOptionsRequirementStatus ==
                        PrivacyOptionsRequirementStatus.REQUIRED
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                    activity,
                    OnConsentFormDismissedListener { _ ->
                        val state = mapStatus(info.consentStatus, info.canRequestAds())
                        _state.value = state
                        // R1: init MobileAds for any non-Required / non-Unknown state.
                        // OfflineFallback explicitly defers to next launch.
                        if (state is ConsentState.Obtained || state is ConsentState.NotRequired) {
                            initializeMobileAds(activity)
                        }
                        onComplete(state)
                    }
                )
            },
            OnConsentInfoUpdateFailureListener { formError ->
                val state = mapError(formError)
                _state.value = state
                if (state is ConsentState.Obtained || state is ConsentState.NotRequired) {
                    initializeMobileAds(activity)
                }
                onComplete(state)
            }
        )
    }

    fun showPrivacyOptions(activity: Activity, onComplete: () -> Unit) {
        val info = consentInformation ?: return onComplete()
        // UMP 3.2.0: privacyOptionsRequirementStatus is the enum, NOT isPrivacyOptionsRequired.
        if (info.privacyOptionsRequirementStatus == PrivacyOptionsRequirementStatus.REQUIRED) {
            UserMessagingPlatform.showPrivacyOptionsForm(
                activity,
                OnConsentFormDismissedListener { _ -> onComplete() }
            )
        } else {
            onComplete()
        }
    }

    private fun buildRequestParams(activity: Activity): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
        val app = activity.application
        if ((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            builder.setConsentDebugSettings(
                ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .build()
            )
        }
        return builder.build()
    }

    private fun initializeMobileAds(activity: Activity) {
        try {
            MobileAds.initialize(activity.applicationContext) { /* ready */ }
        } catch (e: Throwable) {
            // R13: degoogled device or missing deps — proceed without ads, no crash.
            Log.w(TAG, "Mobile Ads unavailable on this device", e)
        }
    }

    /**
     * Maps UMP 3.2.0 consentStatus Int + canRequestAds() to ConsentState.
     * UMP 3.2.0 lacks the personalized-vs-non-personalized discriminator from
     * later releases, so canRequestAds() is the coarse proxy — conservative:
     * non-personalized when in doubt.
     *
     * ConsentStatus.* are Int constants in an annotation interface.
     */
    private fun mapStatus(status: Int, canRequestAds: Boolean): ConsentState = when (status) {
        ConsentStatus.NOT_REQUIRED -> ConsentState.NotRequired
        ConsentStatus.REQUIRED -> ConsentState.Required(canShowForm = true)
        ConsentStatus.OBTAINED -> ConsentState.Obtained(personalized = canRequestAds)
        else -> ConsentState.Unknown
    }

    /** R6: distinguish INTERNET_ERROR from other failures. */
    private fun mapError(error: FormError): ConsentState =
        if (error.errorCode == FormError.ErrorCode.INTERNET_ERROR)
            ConsentState.OfflineFallback
        else
            ConsentState.NotRequired

    private companion object {
        const val TAG = "ConsentManager"
    }
}