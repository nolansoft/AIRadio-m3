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
 * Renders nothing for [SyncState.Idle] / [SyncState.Success] — the screens
 * assume the data flow has produced real content and there's no overlay to
 * show.
 *
 * For [SyncState.Syncing] renders an indeterminate linear progress bar and
 * a stage-aware message. For [SyncState.Failed] with [SyncState.Failed.willRetry]
 * it stays as a progress bar (WorkManager will retry); without willRetry it
 * surfaces a "tap to retry" CTA that calls [onRetry].
 */
@Composable
fun SyncStatusBanner(
    syncState: SyncState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (syncState) {
        SyncState.Idle, SyncState.Success -> Unit
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
