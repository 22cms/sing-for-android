package org.francasio.sing.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.francasio.sing.R
import org.francasio.sing.ui.theme.BackgroundDark
import org.francasio.sing.ui.theme.CardDark
import org.francasio.sing.ui.theme.DividerDark
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.SurfaceContainerHigh
import org.francasio.sing.ui.theme.SurfaceDark
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.getAccent2_900
import org.francasio.sing.util.DeviceHelper
import org.francasio.sing.util.ModelDownloader
import org.francasio.sing.util.ModelLoader

@Composable
fun ModelSelectionScreen(
    selectedModelId: String,
    scrollToSelected: Boolean = false,
    onModelSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val modelDefinitions = remember { mutableStateListOf<ModelLoader.ModelDefinition>() }

    LaunchedEffect(Unit) {
        val initial = ModelLoader.loadModels(context)
        modelDefinitions.clear()
        modelDefinitions.addAll(initial)

        ModelLoader.refreshModels(context) { updated ->
            modelDefinitions.clear()
            modelDefinitions.addAll(updated)
        }
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pinnedTopBarHeight = 64.dp
    val totalPinnedHeight = pinnedTopBarHeight + statusBarTop

    val collapseRange = 110.dp
    val collapseRangePx = with(density) { collapseRange.toPx() }
    val progress = if (collapseRangePx > 0f) {
        (scrollState.value.toFloat() / collapseRangePx).coerceIn(0f, 1f)
    } else 0f

    val subtitleAlpha = (1f - progress * 2.2f).coerceIn(0f, 1f)
    val titleFontSize = (34f - 14f * progress).sp
    val titleStartX = 20.dp
    val titleEndX = 68.dp
    val currentTitleX = titleStartX + (titleEndX - titleStartX) * progress
    val titleStartY = statusBarTop + 86.dp
    val titleEndY = statusBarTop + (pinnedTopBarHeight - 26.dp) / 2
    val currentTitleY = titleStartY - (titleStartY - titleEndY) * progress

    val cardPositions = remember { mutableMapOf<String, Int>() }
    val highlightPulse = remember { Animatable(0f) }

    LaunchedEffect(scrollToSelected, modelDefinitions.size) {
        if (scrollToSelected && modelDefinitions.isNotEmpty()) {
            delay(150)
            val targetDef = modelDefinitions.find { it.versions.any { v -> v.id == selectedModelId } || it.id == selectedModelId }
            if (targetDef != null) {
                val y = cardPositions[targetDef.id]
                if (y != null) {
                    val scrollTarget = (y - with(density) { (totalPinnedHeight + 16.dp).toPx() }.toInt()).coerceAtLeast(0)
                    scrollState.animateScrollTo(scrollTarget, tween(450, easing = FastOutSlowInEasing))
                }
            }
            delay(100)
            highlightPulse.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
            highlightPulse.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            // Space to clear the large expanded header with comfortable breathing room
            Spacer(modifier = Modifier.height(statusBarTop + 196.dp))

            // Split by Models First: Render a separate Card for each Model Definition
            modelDefinitions.forEach { modelDef ->
                val isSelectedGroup = modelDef.versions.any { it.id == selectedModelId } || modelDef.id == selectedModelId
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInParent().y.toInt()
                            cardPositions[modelDef.id] = y
                        }
                ) {
                    ModelGroupCard(
                        modelDef = modelDef,
                        selectedModelId = selectedModelId,
                        highlightPulse = if (isSelectedGroup) highlightPulse.value else 0f,
                        onVersionSelect = onModelSelect
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Hardware Acceleration Info Card (AOSP Details style)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.model_info_title),
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(id = R.string.model_info_desc),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // Bottom spacer with see-through navbar padding so cards glide beneath the transparent system navbar
            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Pinned Top Bar Background (fades in on scroll)
        Box(
            modifier = Modifier
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

        // Dynamic Back Circle: starts big with ic_ai_models, shrinks and morphs to back arrow on scroll
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
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            if (progress < 0.6f) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_ai_models),
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

        // Subtitle (fades out on scroll)
        if (subtitleAlpha > 0f) {
            Text(
                text = stringResource(id = R.string.desc_model_selection),
                color = TextSecondary.copy(alpha = subtitleAlpha),
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .offset(x = titleStartX, y = statusBarTop + 134.dp)
                    .padding(end = 24.dp)
                    .graphicsLayer {
                        alpha = subtitleAlpha
                    }
            )
        }

        // Large Page Title (glides and shrinks into the pinned top bar)
        Text(
            text = stringResource(id = R.string.title_model_selection),
            color = TextPrimary,
            fontSize = titleFontSize,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.offset(x = currentTitleX, y = currentTitleY)
        )
    }
}

@Composable
private fun StatusBadge(
    text: String,
    isPreferred: Boolean,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current
    val accent2_900 = getAccent2_900()

    val containerColor = when {
        !isPreferred -> SurfaceContainerHigh
        isMaterialYou -> Primary
        else -> Primary.copy(alpha = 0.16f)
    }

    val contentColor = when {
        !isPreferred -> TextSecondary
        isMaterialYou -> accent2_900
        else -> Primary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (isPreferred) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun ModelGroupCard(
    modelDef: ModelLoader.ModelDefinition,
    selectedModelId: String,
    highlightPulse: Float = 0f,
    onVersionSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Model Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = modelDef.displayName,
                        color = TextPrimary,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (modelDef.isDefault) {
                        StatusBadge(
                            text = stringResource(id = R.string.badge_recommended),
                            isPreferred = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Target Stem Badge: Instrumental preferred over Vocal
                    StatusBadge(
                        text = if (modelDef.isInstrumental) "Instrumental Output" else "Vocal Output",
                        isPreferred = modelDef.isInstrumental
                    )

                    // Architecture Badge (Neutral)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceContainerHigh)
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = if (modelDef.isTimeDomain) "Time-Domain" else "STFT (${modelDef.numBins} bins)",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (modelDef.description.isNotEmpty()) {
                    Text(
                        text = modelDef.description,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            HorizontalDivider(color = DividerDark.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth())

            // Model Versions
            modelDef.versions.forEachIndexed { vIndex, version ->
                val modelInfo = ModelLoader.getModelInfo(version.id) ?: return@forEachIndexed
                val isSelected = (version.id == selectedModelId)

                ModelVersionRow(
                    modelInfo = modelInfo,
                    isSelected = isSelected,
                    highlightPulse = if (isSelected) highlightPulse else 0f,
                    onSelect = { onVersionSelect(version.id) }
                )

                if (vIndex < modelDef.versions.size - 1) {
                    HorizontalDivider(
                        color = DividerDark.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelVersionRow(
    modelInfo: ModelLoader.ModelInfo,
    isSelected: Boolean,
    highlightPulse: Float = 0f,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val downloadState = ModelDownloader.getDownloadState(context, modelInfo)
    val isDownloaded = (downloadState is ModelDownloader.DownloadState.Completed)
    val isDownloading = (downloadState is ModelDownloader.DownloadState.Downloading)

    val isQualcomm = remember { DeviceHelper.isQualcommWithHexagonHtp }
    val isEnginePreferred = when {
        modelInfo.engine == "native" -> true
        isQualcomm -> modelInfo.engine == "onnx"
        else -> modelInfo.engine == "mnn"
    }
    val isPrecisionPreferred = modelInfo.precision.equals("fp16", ignoreCase = true) ||
            modelInfo.precisionBadge.equals("FP16", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlightPulse > 0.01f) {
                    Modifier.background(Primary.copy(alpha = highlightPulse * 0.28f))
                } else Modifier
            )
            .clickable(enabled = !isDownloading) {
                if (isDownloaded) {
                    onSelect()
                } else {
                    ModelDownloader.startDownload(context, modelInfo) {
                        onSelect()
                    }
                }
            }
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        // Top row: Radio button + Version title + Badges
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioButton(
                selected = isSelected && isDownloaded,
                onClick = {
                    if (isDownloaded) {
                        onSelect()
                    } else {
                        ModelDownloader.startDownload(context, modelInfo) {
                            onSelect()
                        }
                    }
                },
                enabled = isDownloaded && !isDownloading,
                colors = RadioButtonDefaults.colors(
                    selectedColor = Primary,
                    unselectedColor = TextSecondary.copy(alpha = 0.6f),
                    disabledSelectedColor = Primary.copy(alpha = 0.4f),
                    disabledUnselectedColor = TextSecondary.copy(alpha = 0.2f)
                ),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelInfo.displayName,
                    color = if (isDownloaded) TextPrimary else TextPrimary.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Model format row (Engine, Precision, File size)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Engine Badge
                    StatusBadge(
                        text = modelInfo.runtimeBadge,
                        isPreferred = isEnginePreferred
                    )

                    // Precision Badge
                    StatusBadge(
                        text = modelInfo.precisionBadge,
                        isPreferred = isPrecisionPreferred
                    )

                    Text(
                        text = "• ${modelInfo.fileSizeDesc}",
                        color = TextSecondary.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Recommended badge on its own line under model format so it never wraps or cuts off
                if (modelInfo.isRecommended) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(
                            text = stringResource(id = R.string.badge_recommended),
                            isPreferred = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isDownloaded && !isSelected && modelInfo.engine != "native") {
                IconButton(
                    onClick = { ModelDownloader.deleteModel(context, modelInfo) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.btn_delete_model),
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (isDownloaded && isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Active",
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Description text
        if (modelInfo.description.isNotEmpty()) {
            Text(
                text = modelInfo.description,
                color = TextSecondary.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 38.dp, top = 6.dp, end = 4.dp)
            )
        }

        if (downloadState is ModelDownloader.DownloadState.Error) {
            Text(
                text = "Download error: ${downloadState.message}",
                color = Color(0xFFEF5350),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(start = 38.dp, top = 4.dp)
            )
        }

        // Action Container
        when (downloadState) {
            is ModelDownloader.DownloadState.Downloading -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 38.dp, top = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading...",
                            color = Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        val percent = (downloadState.progress * 100).toInt()
                        Text(
                            text = if (percent > 0) "$percent%" else "...",
                            color = Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                    LinearWavyProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary,
                        trackColor = Primary.copy(alpha = 0.2f)
                    )
                }
            }

            is ModelDownloader.DownloadState.Idle, is ModelDownloader.DownloadState.Error -> {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        ModelDownloader.startDownload(context, modelInfo) {
                            onSelect()
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary.copy(alpha = 0.16f),
                        contentColor = Primary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 38.dp)
                        .height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Download (${modelInfo.fileSizeDesc})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            is ModelDownloader.DownloadState.Completed -> {
                // Downloaded: selection is handled by tapping row
            }
        }
    }
}
