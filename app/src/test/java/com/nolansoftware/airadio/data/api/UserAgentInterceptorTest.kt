// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * RB-002 regression guard. Every Radio Browser HTTP request — including the
 * mirror-discovery call (`MirrorRegistryApi` against `all.api.radio-browser.info`)
 * — must identify itself as AIRadio/<version> (Android) so the project's
 * upstream operators can triage traffic in their access logs.
 *
 * The interceptor lives in [UserAgentInterceptor] and is added to the
 * singleton OkHttpClient in `AppModule.provideOkHttpClient`. Both the
 * per-mirror `RadioBrowserApi` Retrofit instances and the discovery
 * `MirrorRegistryApi` share that client, so one interceptor covers both.
 */
class UserAgentInterceptorTest {

    /** Captures the request the interceptor handed downstream and returns 200. */
    private class CapturingChain(
        private val request: Request,
    ) : Interceptor.Chain {
        var capturedRequest: Request? = null
            private set

        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun connection(): okhttp3.Connection? = null
        override fun proceed(request: Request): Response {
            capturedRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }
        override fun readTimeoutMillis(): Int = 0
        override fun request(): Request = request
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
    }

    private fun makeRequest(url: String) =
        Request.Builder().url(url).build()

    @Test
    fun setsUserAgentHeader_inAIRAadioFormat_withProvidedVersion() {
        val interceptor = UserAgentInterceptor(versionName = "1.2.0")
        val chain = CapturingChain(makeRequest("https://de1.api.radio-browser.info/json/stations"))

        interceptor.intercept(chain)

        val ua = chain.capturedRequest?.header("User-Agent")
        assertEquals("AIRadio/1.2.0 (Android)", ua)
    }

    @Test
    fun overwritesAnyExistingUserAgentHeader_onTheRequest() {
        // Defense-in-depth: a caller (or a future contributor) might attach
        // a UA on the request itself. The interceptor is the single source
        // of truth for our identity, so it must replace, not append.
        val interceptor = UserAgentInterceptor(versionName = "1.2.0")
        val request = Request.Builder()
            .url("https://de1.api.radio-browser.info/")
            .header("User-Agent", "leaked-identity/0.1")
            .build()
        val chain = CapturingChain(request)

        interceptor.intercept(chain)

        assertEquals("AIRadio/1.2.0 (Android)", chain.capturedRequest?.header("User-Agent"))
    }

    @Test
    fun preservesAllOtherRequestFields() {
        val interceptor = UserAgentInterceptor(versionName = "1.0")
        val request = Request.Builder()
            .url("https://all.api.radio-browser.info/json/servers")
            .header("Accept", "application/json")
            .header("X-Trace-Id", "abc-123")
            .build()
        val chain = CapturingChain(request)

        interceptor.intercept(chain)

        val forwarded = chain.capturedRequest
        assertNotNull(forwarded)
        assertEquals("https://all.api.radio-browser.info/json/servers", forwarded!!.url.toString())
        assertEquals("application/json", forwarded.header("Accept"))
        assertEquals("abc-123", forwarded.header("X-Trace-Id"))
        assertEquals("AIRadio/1.0 (Android)", forwarded.header("User-Agent"))
    }
}
