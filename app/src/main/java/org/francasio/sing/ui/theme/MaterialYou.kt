package org.francasio.sing.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource

/**
 * CompositionLocal indicating whether the dynamic Material You theme is active.
 */
val LocalIsMaterialYou = compositionLocalOf { false }

@Composable
@ReadOnlyComposable
private fun systemColor(resId: Int, fallback: Color): Color {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorResource(resId)
    } else {
        fallback
    }
}

// ---------------------------------------------------------------------------
// Material You Monet Tonal Palettes
// Each palette contains the full set of tonal steps (0..900) from android.R.color
// ---------------------------------------------------------------------------

// Accent 1 (Primary dynamic tone)
val Accent1_0: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_0, Color(0xFF000000))
val Accent1_10: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_10, Color(0xFF21005D))
val Accent1_50: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_50, Color(0xFF381E72))
val Accent1_100: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_100, Color(0xFF4F378B))
val Accent1_200: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_200, MaterialTheme.colorScheme.primary)
val Accent1_300: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_300, Color(0xFFB69DF8))
val Accent1_400: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_400, Color(0xFF9A82DB))
val Accent1_500: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_500, Color(0xFF7F67BE))
val Accent1_600: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_600, Color(0xFF6750A4))
val Accent1_700: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_700, Color(0xFF4F378B))
val Accent1_800: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_800, Color(0xFF381E72))
val Accent1_900: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent1_900, Color(0xFF1E1E24))

// Accent 2 (Secondary dynamic tone)
val Accent2_0: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_0, Color(0xFF000000))
val Accent2_10: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_10, Color(0xFF1D192B))
val Accent2_50: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_50, Color(0xFF332D41))
val Accent2_100: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_100, Color(0xFF4A4458))
val Accent2_200: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_200, Color(0xFFCCC2DC))
val Accent2_300: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_300, Color(0xFFB0A7C0))
val Accent2_400: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_400, Color(0xFF958DA5))
val Accent2_500: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_500, Color(0xFF7A7289))
val Accent2_600: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_600, Color(0xFF625B71))
val Accent2_700: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_700, Color(0xFF4A4458))
val Accent2_800: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_800, Color(0xFF332D41))
val Accent2_900: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent2_900, Color(0xFF1E1E24))

// Accent 3 (Tertiary dynamic tone)
val Accent3_0: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_0, Color(0xFF000000))
val Accent3_10: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_10, Color(0xFF31111D))
val Accent3_50: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_50, Color(0xFF492532))
val Accent3_100: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_100, Color(0xFF633B48))
val Accent3_200: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_200, Color(0xFFEFB8C8))
val Accent3_300: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_300, Color(0xFFD29DAC))
val Accent3_400: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_400, Color(0xFFB58392))
val Accent3_500: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_500, Color(0xFF986977))
val Accent3_600: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_600, Color(0xFF7D5260))
val Accent3_700: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_700, Color(0xFF633B48))
val Accent3_800: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_800, Color(0xFF492532))
val Accent3_900: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_accent3_900, Color(0xFF31111D))

// Neutral 1 (Surface / background tone)
val Neutral1_0: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_0, Color(0xFF000000))
val Neutral1_10: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_10, Color(0xFF141218))
val Neutral1_50: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_50, Color(0xFF1D1B20))
val Neutral1_100: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_100, Color(0xFF211F26))
val Neutral1_200: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_200, Color(0xFFE6E0E9))
val Neutral1_300: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_300, Color(0xFFCAC4D0))
val Neutral1_400: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_400, Color(0xFFAEAAAE))
val Neutral1_500: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_500, Color(0xFF939094))
val Neutral1_600: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_600, Color(0xFF787579))
val Neutral1_700: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_700, Color(0xFF605D62))
val Neutral1_800: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_800, Color(0xFF2E2C33))
val Neutral1_900: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral1_900, Color(0xFF1C1B1F))

// Neutral 2 (Surface variant / outline tone)
val Neutral2_0: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_0, Color(0xFF000000))
val Neutral2_10: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_10, Color(0xFF1D1A22))
val Neutral2_50: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_50, Color(0xFF26232A))
val Neutral2_100: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_100, Color(0xFF322F37))
val Neutral2_200: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_200, Color(0xFFE7E0EC))
val Neutral2_300: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_300, Color(0xFFCBC4CF))
val Neutral2_400: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_400, Color(0xFFAFABA4))
val Neutral2_500: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_500, Color(0xFF948F99))
val Neutral2_600: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_600, Color(0xFF79747E))
val Neutral2_700: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_700, Color(0xFF605B66))
val Neutral2_800: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_800, Color(0xFF48454E))
val Neutral2_900: Color @Composable @ReadOnlyComposable get() = systemColor(android.R.color.system_neutral2_900, Color(0xFF322F37))

// ---------------------------------------------------------------------------
// Fallback dynamic dark color scheme for Android < 12
// ---------------------------------------------------------------------------
val FallbackDynamicDarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF44474E)
)
