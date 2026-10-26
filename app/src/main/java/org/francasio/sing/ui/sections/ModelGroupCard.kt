package org.francasio.sing.ui.sections

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.theme.Accent2_900
import org.francasio.sing.ui.theme.CardDark
import org.francasio.sing.ui.theme.DividerDark
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.SurfaceContainerHigh
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary
import org.francasio.sing.util.DeviceHelper
import org.francasio.sing.util.ModelDownloader
import org.francasio.sing.util.ModelLoader
import org.francasio.sing.util.NativeLibDownloader

@Composable
fun StatusBadge(
    text: String,
    isPreferred: Boolean,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current

    val containerColor = when {
        !isPreferred -> SurfaceContainerHigh
        isMaterialYou -> Primary
        else -> Primary.copy(alpha = 0.16f)
    }

    val contentColor = when {
        !isPreferred -> TextSecondary
        isMaterialYou -> Accent2_900
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
fun ModelGroupCard(
    modelDef: ModelLoader.ModelDefinition,
    selectedModelId: String,
    highlightPulse: Float = 0f,
    selectedStemFilter: String? = null,
    backingRetentionOnly: Boolean = false,
    selectedFamilyFilter: String? = null,
    onVersionSelect: (String) -> Unit
) {
    val availableEngines = remember(modelDef.versions) {
        modelDef.versions.map { it.engine.lowercase() }.distinct()
    }
    val selectedVersion = modelDef.versions.find { it.id == selectedModelId }
    val recommendedVersion = modelDef.versions.find { it.isRecommended } ?: modelDef.versions.firstOrNull()

    // Default to the tab containing the selected model (if belonging to this family)
    // or the tab containing the recommended model
    val initialEngine = remember(modelDef.id, selectedModelId) {
        selectedVersion?.engine?.lowercase()
            ?: recommendedVersion?.engine?.lowercase()
            ?: availableEngines.firstOrNull()
            ?: "onnx"
    }

    var currentEngine by remember(modelDef.id) { mutableStateOf(initialEngine) }

    LaunchedEffect(selectedModelId) {
        selectedVersion?.let {
            currentEngine = it.engine.lowercase()
        }
    }

    var isExpanded by remember { mutableStateOf(false) }
    val hasMultipleVersions = modelDef.versions.size > 1

    val displayedVersions = if (!isExpanded) {
        listOfNotNull(selectedVersion ?: recommendedVersion)
    } else if (availableEngines.size > 1) {
        modelDef.versions.filter { it.engine.equals(currentEngine, ignoreCase = true) }
    } else {
        modelDef.versions
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
        ) {
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

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Model Family Badge: highlighted when family filter matches
                    val isFamilyHighlighted = selectedFamilyFilter != null &&
                            modelDef.resolvedFamily.equals(selectedFamilyFilter, ignoreCase = true)
                    StatusBadge(
                        text = modelDef.resolvedFamily,
                        isPreferred = isFamilyHighlighted
                    )

                    // Target Stem Badge: highlighted when output filter matches
                    val isStemHighlighted = selectedStemFilter != null &&
                            modelDef.targetStem.equals(selectedStemFilter, ignoreCase = true)
                    StatusBadge(
                        text = if (modelDef.isInstrumental) "Instrumental Output" else "Vocal Output",
                        isPreferred = isStemHighlighted
                    )

                    // Backing Vocals Retention Badge: highlighted when backing retention filter is active
                    if (modelDef.keepsBackingVocals) {
                        StatusBadge(
                            text = stringResource(id = R.string.badge_keeps_backing_vocals),
                            isPreferred = backingRetentionOnly
                        )
                    }

                    // Architecture Badge (Neutral)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceContainerHigh)
                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Text(
                            text = if (modelDef.isTimeDomain) "Time-Domain" else if (modelDef.isVr3Band) "VR 3-Band (769 bins)" else "STFT (${modelDef.numBins} bins)",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (modelDef.vocalSdr != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceContainerHigh)
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sing),
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.1f", modelDef.vocalSdr)} dB",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    if (modelDef.instSdr != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceContainerHigh)
                                .padding(horizontal = 7.dp, vertical = 2.5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_instrumental),
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.1f", modelDef.instSdr)} dB",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
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

            if (isExpanded && availableEngines.size > 1) {
                val selectedTabIndex = availableEngines.indexOf(currentEngine).coerceAtLeast(0)
                SecondaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent,
                    contentColor = Primary,
                    divider = {
                        HorizontalDivider(
                            color = DividerDark.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                ) {
                    availableEngines.forEachIndexed { index, engine ->
                        val isTabSelected = (currentEngine == engine)
                        Tab(
                            selected = isTabSelected,
                            onClick = { currentEngine = engine },
                            text = {
                                Text(
                                    text = when (engine) {
                                        "onnx" -> "ONNX"
                                        "mnn" -> "MNN"
                                        "native" -> "Native"
                                        else -> engine.uppercase()
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            selectedContentColor = Primary,
                            unselectedContentColor = TextSecondary
                        )
                    }
                }
            }

            // Model Versions
            displayedVersions.forEachIndexed { vIndex, version ->
                val modelInfo = ModelLoader.findCatalogModel(version.id) ?: return@forEachIndexed
                val isSelected = (version.id == selectedModelId)

                ModelVersionRow(
                    modelInfo = modelInfo,
                    isSelected = isSelected,
                    highlightPulse = if (isSelected) highlightPulse else 0f,
                    onSelect = { onVersionSelect(version.id) }
                )

                if (vIndex < displayedVersions.size - 1) {
                    HorizontalDivider(
                        color = DividerDark.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                }
            }

            if (hasMultipleVersions) {
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "arrowRotation"
                )

                HorizontalDivider(
                    color = DividerDark.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = if (isExpanded) R.string.show_less else R.string.show_more),
                        color = Primary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation)
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
        modelInfo.isViNNet || modelInfo.engine.equals(SingSettings.ENGINE_NATIVE, ignoreCase = true) -> false
        isQualcomm && ModelLoader.isOnnxRuntimeAvailable -> modelInfo.engine.equals(SingSettings.ENGINE_ONNX, ignoreCase = true)
        else -> modelInfo.engine.equals(SingSettings.ENGINE_MNN, ignoreCase = true)
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

            if (isDownloaded && !isSelected) {
                IconButton(
                    onClick = { ModelDownloader.deleteModel(context, modelInfo) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
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
                        val downloadingLabel = if (modelInfo.engine.equals(SingSettings.ENGINE_ONNX, ignoreCase = true) &&
                            NativeLibDownloader.currentLabel.isNotEmpty()
                        ) NativeLibDownloader.currentLabel else stringResource(id = R.string.downloading)
                        Text(
                            text = downloadingLabel,
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
                        painter = painterResource(id = R.drawable.ic_cloud_download),
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

