// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [RadioBrowserApi] instance for a given host. Decouples the
 * repository from the Retrofit plumbing so the cold-start sync can build
 * one API per mirror returned by [MirrorRegistry] without baking the
 * base URL into the constructor.
 *
 * All instances share the singleton [OkHttpClient] (and therefore its
 * connection pool — OkHttp keeps separate connections per host) and the
 * singleton [Gson] parser.
 */
fun interface RadioBrowserApiFactory {
    fun create(host: String): RadioBrowserApi
}

@Singleton
class DefaultRadioBrowserApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : RadioBrowserApiFactory {
    override fun create(host: String): RadioBrowserApi =
        Retrofit.Builder()
            .baseUrl("https://$host/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(RadioBrowserApi::class.java)
}
