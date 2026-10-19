package org.francasio.sing.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.ui.theme.DividerDark
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.SurfaceContainerHigh
import org.francasio.sing.ui.theme.SurfaceDark
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary

sealed interface NavigationIcon {
    data class Morphing(val icon: Painter, val onBack: () -> Unit) : NavigationIcon
    data class Back(val onBack: () -> Unit) : NavigationIcon
    data class Fading(val icon: Painter) : NavigationIcon
    data object None : NavigationIcon
}

object CollapsingTopBarDefaults {
    val PinnedTopBarHeight: Dp = 64.dp
    val CollapseRangeDefault: Dp = 110.dp
    val CollapseRangeCompact: Dp = 100.dp
    val ContentPaddingExpanded: Dp = 196.dp
    val ContentPaddingCompact: Dp = 140.dp
}

@Composable
fun rememberCollapsingProgress(
    scrollState: ScrollState,
    collapseRange: Dp = CollapsingTopBarDefaults.CollapseRangeDefault
): Float {
    val density = LocalDensity.current
    val collapseRangePx = with(density) { collapseRange.toPx() }
    return if (collapseRangePx > 0f) {
        (scrollState.value.toFloat() / collapseRangePx).coerceIn(0f, 1f)
    } else 0f
}

@Composable
fun BoxScope.ExpressiveCollapsingTopBar(
    progress: Float,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: NavigationIcon = NavigationIcon.None,
    pinnedTopBarHeight: Dp = CollapsingTopBarDefaults.PinnedTopBarHeight,
    trailingAction: (@Composable BoxScope.(progress: Float) -> Unit)? = null
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val totalPinnedHeight = pinnedTopBarHeight + statusBarTop

    val hasBackButton = navigationIcon is NavigationIcon.Morphing || navigationIcon is NavigationIcon.Back
    val isCompactStyle = navigationIcon is NavigationIcon.Back && subtitle == null

    val startFontSize = if (isCompactStyle) 34f else 36f
    val endFontSize = 20f
    val titleFontSize = (startFontSize - (startFontSize - endFontSize) * progress).sp

    val titleStartX = 20.dp
    val titleEndX = if (hasBackButton) 68.dp else 20.dp
    val currentTitleX = titleStartX + (titleEndX - titleStartX) * progress

    val titleStartY = statusBarTop + (if (isCompactStyle) 68.dp else 86.dp)
    val titleEndY = statusBarTop + (pinnedTopBarHeight - 28.dp) / 2
    val currentTitleY = titleStartY - (titleStartY - titleEndY) * progress

    val iconAlpha = (1f - progress * 2.2f).coerceIn(0f, 1f)
    val iconScale = 1f - progress * 0.35f
    val subtitleAlpha = (1f - progress * 2.4f).coerceIn(0f, 1f)

    // 1. Pinned Top Bar Background (fades in as user scrolls)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalPinnedHeight)
            .background(SurfaceDark.copy(alpha = progress * 0.96f))
    ) {
        if (progress > 0.05f) {
            HorizontalDivider(
                color = DividerDark.copy(alpha = progress),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    // 2. Navigation Icon
    when (navigationIcon) {
        is NavigationIcon.Morphing -> {
            val circleSize = 52.dp - (12.dp * progress)
            val circleX = 20.dp - (4.dp * progress)
            val circleStartY = statusBarTop + 24.dp
            val circleEndY = statusBarTop + (pinnedTopBarHeight - 40.dp) / 2
            val circleY = circleStartY + (circleEndY - circleStartY) * progress

            Box(
                modifier = Modifier
                    .offset(x = circleX, y = circleY)
                    .size(circleSize)
                    .clip(CircleShape)
                    .background(
                        if (progress >= 0.5f) {
                            SurfaceContainerHigh.copy(alpha = 0.7f + progress * 0.3f)
                        } else {
                            Primary.copy(alpha = 0.16f * (1f - progress))
                        }
                    )
                    .clickable(onClick = navigationIcon.onBack),
                contentAlignment = Alignment.Center
            ) {
                if (progress < 0.6f) {
                    Icon(
                        painter = navigationIcon.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                alpha = (1f - progress * 2f).coerceIn(0f, 1f)
                            }
                    )
                }
                if (progress > 0.4f) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                alpha = ((progress - 0.4f) * 2.5f).coerceIn(0f, 1f)
                            }
                    )
                }
            }
        }
        is NavigationIcon.Back -> {
            // Static compact back button pinned in top bar (InfoScreen style)
            Box(
                modifier = Modifier
                    .offset(
                        x = 16.dp,
                        y = statusBarTop + (pinnedTopBarHeight - 40.dp) / 2
                    )
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SurfaceContainerHigh.copy(alpha = 0.7f + progress * 0.3f))
                    .clickable(onClick = navigationIcon.onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        is NavigationIcon.Fading -> {
            // Launcher mode icon fading on scroll (MainScreen launcher mode style)
            if (iconAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .offset(x = titleStartX, y = statusBarTop + 24.dp)
                        .size(52.dp)
                        .graphicsLayer {
                            alpha = iconAlpha
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = navigationIcon.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        NavigationIcon.None -> {}
    }

    // 3. Subtitle (fades out on scroll)
    if (subtitle != null && subtitleAlpha > 0f) {
        Text(
            text = subtitle,
            color = TextSecondary.copy(alpha = subtitleAlpha),
            fontSize = 13.5.sp,
            modifier = Modifier
                .offset(x = titleStartX, y = statusBarTop + 132.dp)
                .padding(end = 24.dp)
                .graphicsLayer {
                    alpha = subtitleAlpha
                }
        )
    }

    // 4. Large Page Title (glides and shrinks into the pinned top bar)
    Text(
        text = title,
        color = TextPrimary,
        fontSize = titleFontSize,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.offset(x = currentTitleX, y = currentTitleY)
    )

    // 5. Trailing Action Slot (e.g. pinned Info button)
    trailingAction?.invoke(this, progress)
}

@Composable
fun BoxScope.TopBarActionButton(
    icon: ImageVector,
    contentDescription: String?,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pinnedTopBarHeight: Dp = CollapsingTopBarDefaults.PinnedTopBarHeight
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .offset(
                x = (-16).dp,
                y = statusBarTop + (pinnedTopBarHeight - 40.dp) / 2
            )
            .size(40.dp)
            .clip(CircleShape)
            .background(SurfaceContainerHigh.copy(alpha = 0.7f + progress * 0.3f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

