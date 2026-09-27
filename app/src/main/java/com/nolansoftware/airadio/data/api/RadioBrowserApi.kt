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
        // Default 300 — the UI only ever displays the top 50 (DAO queries all
        // cap at LIMIT 50), and 300 fits in ~1.5 MB of JSON instead of the
        // previous ~10 MB. The 300 ceiling still allows per-country/language/
        // tag queries to find their share of popular stations. Daily sync
        // passes `fullSync = true` to bump this up if a fuller list is needed.
        @Query("limit") limit: Int = 300,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true
    ): List<ApiStation>

    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("name") name: String? = null,
        @Query("country") country: String? = null,
        @Query("language") language: String? = null,
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