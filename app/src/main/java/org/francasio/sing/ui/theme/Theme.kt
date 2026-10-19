package org.francasio.sing.ui.theme

import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.francasio.sing.R

val GoogleSansFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_bold, FontWeight.Bold),
    Font(R.font.google_sans_bold, FontWeight.SemiBold)
)

val LocalIsMaterialYou = compositionLocalOf { false }

@Composable
fun getAccent1_200(): Color {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorResource(android.R.color.system_accent1_200)
    } else {
        MaterialTheme.colorScheme.primary
    }
}

@Composable
fun getAccent1_900(): Color {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorResource(android.R.color.system_accent1_900)
    } else {
        Color(0xFF1E1E24)
    }
}

@Composable
fun getAccent2_900(): Color {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorResource(android.R.color.system_accent2_900)
    } else {
        Color(0xFF1E1E24)
    }
}

private val ClassicDarkColorScheme = darkColorScheme(
    primary = DefaultPrimary,
    onPrimary = DefaultTextPrimary,
    primaryContainer = DefaultPrimaryDark,
    onPrimaryContainer = DefaultTextPrimary,
    background = DefaultBackgroundDark,
    onBackground = DefaultTextPrimary,
    surface = DefaultSurfaceDark,
    onSurface = DefaultTextPrimary,
    surfaceVariant = DefaultSurfaceContainerHigh,
    onSurfaceVariant = DefaultTextSecondary,
    surfaceContainer = DefaultCardDark,
    surfaceContainerHigh = DefaultSurfaceContainerHigh,
    outline = DefaultDividerDark,
    outlineVariant = DefaultDividerDark
)

// Dynamic fallback dark color scheme for Android < 12
private val FallbackDynamicDarkColorScheme = darkColorScheme(
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

private fun getTypography(fontFamily: FontFamily?): Typography {
    if (fontFamily == null) return Typography()
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily)
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppleMusicSingTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(context)
        }
        dynamicColor -> FallbackDynamicDarkColorScheme
        else -> ClassicDarkColorScheme
    }

    val typography = if (dynamicColor) getTypography(GoogleSansFontFamily) else Typography()

    CompositionLocalProvider(
        LocalIsMaterialYou provides dynamicColor,
        LocalTextStyle provides (if (dynamicColor) LocalTextStyle.current.copy(fontFamily = GoogleSansFontFamily) else LocalTextStyle.current)
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
