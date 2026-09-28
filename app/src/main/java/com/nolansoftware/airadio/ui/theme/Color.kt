// SPDX-License-Identifier: Apache-2.0

// app/src/main/java/com/nolansoftware/airadio/ui/theme/Color.kt
package com.nolansoftware.airadio.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Purple tones (Material 3 tonal palette baseline)
val Purple10 = Color(0xFF21005D)
val Purple20 = Color(0xFF381E72)
val Purple30 = Color(0xFF4F378B)
val Purple40 = Color(0xFF6750A4)
val Purple80 = Color(0xFFD0BCFF)
val Purple90 = Color(0xFFEADDFF)

// Violet tones
val Violet30 = Color(0xFF4A4458)
val Violet40 = Color(0xFF625B71)
val Violet80 = Color(0xFFCCC2DC)
val Violet90 = Color(0xFFEEDFE7)

// Sky (tertiary) tones
val Sky30 = Color(0xFF1F3A5F)
val Sky40 = Color(0xFF2E5C8A)
val Sky80 = Color(0xFFB5D5F0)
val Sky90 = Color(0xFFD4E7F8)

// Neutral palette
val Neutral10 = Color(0xFF1C1B1F)
val Neutral20 = Color(0xFF2B2930)
val Neutral90 = Color(0xFFE6E1E5)
val Neutral95 = Color(0xFFF1ECF4)
val Neutral99 = Color(0xFFFFFBFE)

// Variant neutrals
val NeutralVariant30 = Color(0xFF49454F)
val NeutralVariant50 = Color(0xFF79747E)
val NeutralVariant80 = Color(0xFFCAC4D0)

val LightColors = lightColorScheme(
    primary = Purple40,
    onPrimary = Neutral99,
    primaryContainer = Purple90,
    onPrimaryContainer = Purple10,
    secondary = Violet40,
    onSecondary = Neutral99,
    secondaryContainer = Violet90,
    onSecondaryContainer = Violet30,
    tertiary = Sky40,
    onTertiary = Neutral99,
    tertiaryContainer = Sky90,
    onTertiaryContainer = Sky30,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = NeutralVariant30,
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
)

val DarkColors = darkColorScheme(
    primary = Purple80,
    onPrimary = Purple20,
    primaryContainer = Purple30,
    onPrimaryContainer = Purple90,
    secondary = Violet80,
    onSecondary = Violet30,
    secondaryContainer = Violet30,
    onSecondaryContainer = Violet90,
    tertiary = Sky80,
    onTertiary = Sky30,
    tertiaryContainer = Sky30,
    onTertiaryContainer = Sky90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerLow = Color(0xFF1D1B22),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = NeutralVariant30,
)
