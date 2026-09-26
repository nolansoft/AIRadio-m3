package com.nolansoftware.airadio.data.api.model

import com.google.gson.annotations.SerializedName

data class ApiStation(
    @SerializedName("stationuuid")
    val stationuuid: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("url")
    val url: String,
    @SerializedName("url_resolved")
    val url_resolved: String,
    @SerializedName("favicon")
    val favicon: String,
    @SerializedName("country")
    val country: String,
    @SerializedName("countrycode")
    val countrycode: String,
    @SerializedName("language")
    val language: String,
    @SerializedName("tags")
    val tags: String,
    @SerializedName("codec")
    val codec: String,
    @SerializedName("bitrate")
    val bitrate: Int,
    @SerializedName("votes")
    val votes: Int,
    // The Radio Browser API returns this as "yyyy-MM-dd HH:mm:ss" (e.g.
    // "2026-09-25 03:40:39") in its regional endpoints, not as a Unix timestamp.
    // Declared as String here; the mapper parses it into a Long for Room.
    @SerializedName("lastchecktime")
    val lastchecktime: String
)

data class ApiCountry(
    @SerializedName("name")
    val name: String,
    @SerializedName("stationcount")
    val stationcount: Int
)

data class ApiLanguage(
    @SerializedName("name")
    val name: String,
    @SerializedName("stationcount")
    val stationcount: Int
)

data class ApiTag(
    @SerializedName("name")
    val name: String,
    @SerializedName("stationcount")
    val stationcount: Int
)