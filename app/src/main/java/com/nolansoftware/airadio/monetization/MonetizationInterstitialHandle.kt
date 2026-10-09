// app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationInterstitialHandle.kt
package com.nolansoftware.airadio.monetization

import android.app.Activity
import com.google.android.gms.ads.FullScreenContentCallback

interface MonetizationInterstitialHandle {
    fun show(activity: Activity)
    fun setFullScreenContentListener(listener: FullScreenContentCallback)
}
