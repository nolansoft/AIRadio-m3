package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class GradientPalette(val start: Color, val end: Color)

private val Palettes = listOf(
    GradientPalette(Color(0xFF6750A4), Color(0xFF7F67BE)),
    GradientPalette(Color(0xFF625B71), Color(0xFF7A6E89)),
    GradientPalette(Color(0xFF4A636C), Color(0xFF6B7E87)),
    GradientPalette(Color(0xFF7D5260), Color(0xFF976C7A)),
    GradientPalette(Color(0xFF4F378B), Color(0xFF6E50B3)),
    GradientPalette(Color(0xFF005AC1), Color(0xFF3478E0)),
)

internal fun gradientPaletteFor(seed: String): GradientPalette {
    if (seed.isEmpty()) return Palettes[0]
    val idx = (seed.hashCode().toUInt() % Palettes.size.toUInt()).toInt()
    return Palettes[idx]
}

@Composable
fun GradientBackdrop(
    seed: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp
) {
    val palette = remember(seed) { gradientPaletteFor(seed) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(palette.start, palette.end),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
    )
}