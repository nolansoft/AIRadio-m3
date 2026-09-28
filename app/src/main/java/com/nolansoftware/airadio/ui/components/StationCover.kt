package com.nolansoftware.airadio.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

@Composable
fun StationCover(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    fallbackIcon: ImageVector = Icons.Outlined.Radio,
) {
    val seed = imageUrl?.takeIf { it.isNotEmpty() } ?: contentDescription.orEmpty().ifEmpty { "default" }
    // imageUrl = null (or blank) has no separate slot in Coil 2.7.0's
    // SubcomposeAsyncImage — the only slots are loading/error/success, and a null
    // model falls into the error slot. Render the fallback Box at the call site
    // so we can produce a centered icon on top of the gradient when there is no URL.
    if (imageUrl.isNullOrEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            GradientBackdrop(seed = seed, modifier = Modifier.fillMaxSize(), cornerRadius = cornerRadius)
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp),
            )
        }
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(imageUrl)
            .crossfade(300)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        contentScale = ContentScale.Crop,
        loading = {
            GradientBackdrop(seed = seed, cornerRadius = cornerRadius)
        },
        error = {
            GradientBackdrop(seed = seed, cornerRadius = cornerRadius)
        },
        success = { state ->
            Image(
                painter = state.painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
            )
        },
    )
}
