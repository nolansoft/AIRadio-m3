package com.nolansoftware.airadio.data.api

import com.nolansoftware.airadio.data.api.model.ApiCountry
import com.nolansoftware.airadio.data.api.model.ApiLanguage
import com.nolansoftware.airadio.data.api.model.ApiStation
import com.nolansoftware.airadio.data.api.model.ApiTag
import retrofit2.http.GET
import retrofit2.http.Query

interface RadioBrowserApi {

    @GET("json/stations")
    suspend fun getStations(
        // Default 2000 — limit=10000 returns ~11 MB of JSON, which is slow to
        // download, slow to parse, and not meaningfully useful (the UI only
        // shows the top 50 in Popular Stations anyway).
        @Query("limit") limit: Int = 2000,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true
    ): List<ApiStation>

    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("name") name: String? = null,
        @Query("country") country: String? = null,
        @Query("tag") tag: String? = null,
        @Query("limit") limit: Int = 1000
    ): List<ApiStation>

    @GET("json/countries")
    suspend fun getCountries(): List<ApiCountry>

    @GET("json/languages")
    suspend fun getLanguages(): List<ApiLanguage>

    @GET("json/tags")
    suspend fun getTags(): List<ApiTag>

    companion object {
        // The bare "api.radio-browser.info" hostname GeoIP-resolves to a regional
        // backend, but in many networks (and on this emulator) it returns 404 because
        // no A record is published. Pin to a known-good regional server instead.
        // See https://de1.api.radio-browser.info/ — other regions: nl1, at1, etc.
        const val BASE_URL = "https://de1.api.radio-browser.info/"
    }
}