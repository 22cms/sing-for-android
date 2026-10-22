package org.francasio.sing.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Default fixed colors (Classic Apple Music Sing theme)
val DefaultPrimary = Color(0xFF0091FF)
val DefaultPrimaryDark = Color(0xFF0069CC)
val DefaultAccent = Color(0xFF0091FF)
val DefaultBackgroundDark = Color(0xFF000000)
val DefaultSurfaceDark = Color(0xFF1C1C20)
val DefaultSurfaceContainer = Color(0xFF16161A)
val DefaultSurfaceContainerHigh = Color(0xFF222228)
val DefaultCardDark = Color(0xFF16161A)
val DefaultCardElevated = Color(0xFF1E1E24)
val DefaultTextPrimary = Color(0xFFFFFFFF)
val DefaultTextSecondary = Color(0xFF9E9EA4)
val DefaultDividerDark = Color(0xFF2C2C32)
val DefaultYellowError = Color(0xFFFFB74D)

// Dynamic theme accessors that adapt to MaterialTheme (e.g. Material You)
val Primary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val PrimaryDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

val Accent: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val BackgroundDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val SurfaceDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val SurfaceContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainer

val SurfaceContainerHigh: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainerHigh

val CardDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainer

val CardElevated: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainerHigh

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val DividerDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outlineVariant

