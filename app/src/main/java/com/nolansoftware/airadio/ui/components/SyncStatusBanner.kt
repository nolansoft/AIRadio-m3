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
 * Bug #1 UX safety net: surfaces an explicit "tap to sync" CTA when
 * `syncState` is [SyncState.Idle] or [SyncState.Success] AND the caller
 * reports [hasData] is false. Without this, a sync that's blocked on its
 * `NetworkType.CONNECTED` WorkManager constraint (or one that's silently
 * failed with no exceptions) leaves the user staring at a blank grid
 * with no feedback that anything is wrong.
 *
 * Renders nothing for [SyncState.Idle] / [SyncState.Success] when data has
 * already loaded (the screen is then fully populated and the banner would
 * be visual noise).
 *
 * For [SyncState.Syncing] renders an indeterminate linear progress bar and
 * a stage-aware message. For [SyncState.Failed] with [SyncState.Failed.willRetry]
 * it stays as a progress bar (WorkManager will retry); without willRetry it
 * surfaces a "tap to retry" CTA that calls [onRetry].
 *
 * @param hasData true if any of the screen's Room flows have produced
 *   non-empty content. When false and sync hasn't surfaced an error of
 *   its own, we assume the sync is queued-but-not-running and offer a
 *   manual retry.
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
                // Give the user an action rather than a silent blank grid.
                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .clickable { onRetry() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.sync_status_not_yet_run),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.sync_status_tap_to_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
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
