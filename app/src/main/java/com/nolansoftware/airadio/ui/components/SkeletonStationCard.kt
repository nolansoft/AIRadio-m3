package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Placeholder card matching the visual footprint of [StationCard]. Uses
 * surfaceContainerHigh blocks for the cover and surfaceContainerHighest
 * bars for text — no shimmer animation so we avoid pulling in an extra
 * dependency. Static blocks read clearly as "loading" even at rest.
 *
 * Geometry mirrors [StationCard]: large shape, 1:1 cover aspect ratio,
 * 12.dp horizontal / 10.dp vertical metadata padding.
 */
@Composable
fun SkeletonStationCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                SkeletonBar(widthFraction = 0.7f, height = 16.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(widthFraction = 0.4f, height = 12.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(widthFraction = 0.3f, height = 10.dp)
            }
        }
    }
}

/**
 * Placeholder row matching the visual footprint of [BrowseItemCard] while
 * the countries / languages / tags lists are still loading.
 *
 * [BrowseScreen] still uses a LazyColumn of rows for browse entries, so this
 * keeps the old horizontal footprint intentionally.
 */
@Composable
fun SkeletonBrowseRow(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            SkeletonBar(widthFraction = 0.5f, height = 16.dp)
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    )
}