// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * RB-002: identifies AIRadio in every HTTP request sent to the Radio Browser
 * API. Both the per-mirror `RadioBrowserApi` instances and the discovery
 * `MirrorRegistryApi` (against `all.api.radio-browser.info`) share the same
 * `@Singleton OkHttpClient` in `AppModule`, so adding this interceptor once
 * covers every endpoint.
 *
 * Format mirrors the convention the Radio Browser operators already see
 * from other clients: `<AppName>/<version> (<platform>)`. Version is
 * injected at construction (read from `BuildConfig.VERSION_NAME`) so this
 * class stays free of Android / Gradle dependencies and is trivially
 * unit-testable.
 */
class UserAgentInterceptor(
    private val versionName: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val tagged = chain.request().newBuilder()
            .header("User-Agent", headerValue())
            .build()
        return chain.proceed(tagged)
    }

    private fun headerValue(): String = "AIRadio/$versionName (Android)"
}
