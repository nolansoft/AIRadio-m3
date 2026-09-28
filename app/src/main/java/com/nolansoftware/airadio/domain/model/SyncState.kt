// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.domain.model

/**
 * Single source of truth for in-flight / completed sync runs.
 * Repository writes; ViewModels read; never read from Composables directly.
 *
 * Stages are emitted in order during a successful sync. The UI renders a
 * skeleton while Syncing is observed and the underlying data flow is empty,
 * and surfaces a retry CTA while Failed is observed.
 */
sealed class SyncState {
    object Idle : SyncState()

    data class Syncing(
        val stage: Stage,
        val processed: Int = 0,
        val total: Int = 0
    ) : SyncState() {
        enum class Stage { COUNTRIES, LANGUAGES, TAGS, STATIONS }
    }

    /** willRetry = true means WorkManager will retry; UI should keep showing skeleton. */
    data class Failed(val message: String, val willRetry: Boolean) : SyncState()

    /** Set after a successful sync run finishes. */
    object Success : SyncState()
}
