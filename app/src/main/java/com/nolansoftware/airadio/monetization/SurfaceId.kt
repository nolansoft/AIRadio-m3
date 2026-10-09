// app/src/main/java/com/nolansoftware/airadio/monetization/SurfaceId.kt
package com.nolansoftware.airadio.monetization

sealed class SurfaceId(val adUnitSuffix: String) {
    object Player      : SurfaceId("player")
    object Home        : SurfaceId("home")
    object Search      : SurfaceId("search")
    object Browse      : SurfaceId("browse")
    object Favorites   : SurfaceId("favorites")
    object StationList : SurfaceId("station_list")
}
