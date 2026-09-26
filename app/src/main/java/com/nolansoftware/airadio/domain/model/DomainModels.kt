package com.nolansoftware.airadio.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Station(
    val stationuuid: String,
    val name: String,
    val url: String,
    val urlResolved: String,
    val favicon: String,
    val country: String,
    val countryCode: String,
    val language: String,
    val tags: String,
    val codec: String,
    val bitrate: Int,
    val votes: Int,
    val lastCheckTime: Long
) : Parcelable

data class Country(
    val name: String,
    val stationCount: Int
)

data class Language(
    val name: String,
    val stationCount: Int
)

data class Tag(
    val name: String,
    val stationCount: Int
)

sealed class PlayerState {
    object Idle : PlayerState()
    object Loading : PlayerState()
    data class Playing(val station: Station) : PlayerState()
    data class Paused(val station: Station) : PlayerState()
    data class Error(val message: String) : PlayerState()
}

sealed class Result<out T> {
    object Loading : Result<Nothing>()
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
}