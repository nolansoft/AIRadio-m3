// SPDX-License-Identifier: Apache-2.0

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
        // Paging: offset is the 0-based row index of the first returned
        // station; the API returns up to `limit` rows after it. Order by
        // votes descending matches Home's popular-stations surface so the
        // paged list and Home agree on ranking.
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true
    ): List<ApiStation>

    @GET("json/countries")
    suspend fun getCountries(): List<ApiCountry>

    @GET("json/languages")
    suspend fun getLanguages(): List<ApiLanguage>

    @GET("json/tags")
    suspend fun getTags(): List<ApiTag>

    companion object {
        /**
         * Single default base URL for callers that don't need multi-region
         * failover (e.g. one-off tools, instrumented tests). The production
         * cold-start sync goes through `MirrorRegistry` + `RadioBrowserApiFactory`
         * which build a per-host Retrofit on demand from the live registry —
         * see `AppModule.provideMirrorRegistryApi` and
         * `DefaultRadioBrowserApiFactory`. The fallback used by
         * [com.nolansoftware.airadio.data.api.MirrorRegistry] when both the
         * live fetch and the on-disk cache fail is `["de1.api.radio-browser.info"]`
         * (see `MirrorRegistry.DEFAULT_FALLBACK`).
         *
         * Updated 2026-10-02 against the live registry at
         * `https://de1.api.radio-browser.info/json/servers` — the project
         * currently exposes only `de1` (plus its IPv6 alias `de2`, same IP).
         * Historical `nl1` / `at1` / `fr1` / `ch1` / `us1` / `uk1` mirrors
         * all return NXDOMAIN. When new mirrors come online, no code change
         * is needed — the registry will discover them automatically.
         */
        const val BASE_URL = "https://de1.api.radio-browser.info/"
    }
}