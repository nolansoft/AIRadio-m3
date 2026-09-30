// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.SyncState

/**
 * Surfaces sync progress and failure to the user as a Material 3 banner
 * above the home grid.
 *
 * Bug #1 + #3: in the [SyncState.Idle] / [SyncState.Success] branch with
 * [hasData] = false we no longer render a clickable "Tap to retry" CTA.
 * `HomeViewModel.startAutoRetryLoop` now drives the actual sync in the
 * background; the banner is a non-interactive loading indicator that
 * tells the user something is happening without inviting a manual
 * action. The failed-retry CTA in the [SyncState.Failed] branch is
 * unchanged — that branch is reached when auto-retry has exhausted its
 * attempt cap or the user wants to force a refresh after a hard
 * failure.
 *
 * Renders nothing for [SyncState.Idle] / [SyncState.Success] when data
 * has already loaded (the screen is then fully populated and the banner
 * would be visual noise).
 *
 * For [SyncState.Syncing] renders an indeterminate linear progress bar
 * and a stage-aware message. For [SyncState.Failed] with
 * [SyncState.Failed.willRetry] it stays as a progress bar (WorkManager
 * will retry); without willRetry it surfaces a "tap to retry" CTA that
 * calls [onRetry].
 *
 * @param hasData true if any of the screen's Room flows have produced
 *   non-empty content. When false and sync hasn't surfaced an error of
 *   its own, we show a passive loading indicator — the auto-retry loop
 *   in `HomeViewModel` is responsible for driving the actual sync.
 */
@Composable
fun SyncStatusBanner(
    syncState: SyncState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    hasData: Boolean = true,
) {
    when (syncState) {
        SyncState.Idle, SyncState.Success -> {
            if (!hasData) {
                // Sync hasn't produced data yet (queued, blocked on
                // NetworkType.CONNECTED, or completed with empty result).
                // `HomeViewModel.startAutoRetryLoop` is driving the actual
                // sync — show a passive loading indicator rather than a
                // clickable CTA so the user sees something is happening
                // without being asked to take an action the loop is already
                // taking for them (Bug #3). The string `sync_status_loading`
                // is also kept stable across syncing / idle-empty so the
                // banner doesn't visibly flicker when `syncState` flips
                // briefly to `Syncing` and back.
                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sync_status_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is SyncState.Syncing -> {
            Column(modifier = modifier.fillMaxWidth()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = syncingMessage(syncState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        is SyncState.Failed -> {
            if (syncState.willRetry) {
                Column(modifier = modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.sync_status_retrying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            } else {
                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { onRetry() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.sync_status_failed_retry),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.sync_status_tap_to_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun syncingMessage(state: SyncState.Syncing): String {
    return when (state.stage) {
        SyncState.Syncing.Stage.COUNTRIES ->
            stringResource(R.string.sync_status_stages_countries)
        SyncState.Syncing.Stage.LANGUAGES ->
            stringResource(R.string.sync_status_stages_languages)
        SyncState.Syncing.Stage.TAGS ->
            stringResource(R.string.sync_status_stages_tags)
        SyncState.Syncing.Stage.STATIONS ->
            stringResource(
                R.string.sync_status_stations_progress,
                state.processed,
                state.total
            )
    }
}
