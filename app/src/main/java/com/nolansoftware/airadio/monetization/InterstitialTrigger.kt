// app/src/main/java/com/nolansoftware/airadio/monetization/InterstitialTrigger.kt
package com.nolansoftware.airadio.monetization

sealed class InterstitialTrigger(val adUnitSuffix: String) {
    object ExitFromPlayer : InterstitialTrigger("exit_player")
    object AppForeground  : InterstitialTrigger("foreground")
}
