// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import retrofit2.http.GET

/**
 * Read-only entry point for discovering the live list of Radio Browser
 * mirrors. Per the project docs the canonical hostname is
 * `all.api.radio-browser.info` — `all.` is supposed to round-robin via
 * DNS / load balancer to a healthy backend. The user has confirmed this
 * works in their browser, so we trust that signal over our CI environment
 * (the WSL2 test machine can't reach `all.` over TLS, but real-device
 * networks can — the IP behind `all.` differs by network). Kept separate
 * from [RadioBrowserApi] because this endpoint has a different schema
 * (one per host, not per region) and lives at a different layer.
 */
interface MirrorRegistryApi {
    @GET("json/servers")
    suspend fun getServers(): List<MirrorEntry>
}
