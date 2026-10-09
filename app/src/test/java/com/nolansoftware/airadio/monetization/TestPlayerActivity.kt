// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

/**
 * Minimal real Activity used to drive the manager's ActivityLifecycleCallbacks
 * in unit tests via Robolectric. The class is intentionally empty — the test
 * only needs a real Activity to attach to, not a working screen.
 */
class TestPlayerActivity : android.app.Activity()