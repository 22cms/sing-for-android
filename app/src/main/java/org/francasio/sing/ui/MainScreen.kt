package org.francasio.sing.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.getAccent1_200
import org.francasio.sing.ui.theme.getAccent1_900
import org.francasio.sing.ui.theme.getAccent2_900
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.francasio.sing.R
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.theme.AppleMusicSingTheme
import org.francasio.sing.ui.theme.BackgroundDark
import org.francasio.sing.ui.theme.CardDark
import org.francasio.sing.ui.theme.DividerDark
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.PrimaryDark
import org.francasio.sing.ui.theme.SurfaceContainerHigh
import org.francasio.sing.ui.theme.SurfaceDark
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary
import org.francasio.sing.util.ModelLoader

data class BenchmarkUiState(
    val isRunning: Boolean = false,
    val result: BenchmarkResult? = null
)

data class BenchmarkResult(
    val rtf: Double,
    val totalMs: Long,
    val stftMs: Long,
    val nnMs: Long,
    val istftMs: Long,
    val threadCount: Int,
    val provider: String = ""
)

private fun groupedItemShape(index: Int, total: Int): RoundedCornerShape {
    return when {
        total <= 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        index == total - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(4.dp)
    }
}

@Composable
private fun GroupedCardItem(
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = groupedItemShape(index, total),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    isModuleLoaded: Boolean = false,
    xposedManagerInfo: String = "",
    preserveSide: Boolean,
    twoWaySlider: Boolean,
    allowZeroFloor: Boolean,
    forceSongEligibility: Boolean,
    ecoMode: Boolean,
    hardwareTarget: String,
    selectedModelId: String,
    threadCount: Int,
    benchmarkState: BenchmarkUiState,
    onPreserveSideChange: (Boolean) -> Unit,
    onTwoWaySliderChange: (Boolean) -> Unit,
    onAllowZeroFloorChange: (Boolean) -> Unit,
    onForceSongEligibilityChange: (Boolean) -> Unit,
    onEcoModeChange: (Boolean) -> Unit,
    onHardwareTargetChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onRunBenchmark: () -> Unit,
    activeProvider: String = "",
    openedFromMusic: Boolean = false,
    onNavigateToModelSelection: (scrollToSelected: Boolean) -> Unit = {},
    onNavigateToInfo: () -> Unit = {},
    onBackFromMusic: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val apkVersion = remember(context) {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "v${packageInfo.versionName}"
        } catch (_: Exception) {
            "v1.0"
        }
    }

    val catalogVersion = ModelLoader.catalogVersion
    val currentModel = remember(selectedModelId, catalogVersion) {
        if (selectedModelId.isNotEmpty()) ModelLoader.findCatalogModel(selectedModelId) else null
    }
    val isOnnxModel = currentModel?.engine?.equals(SingSettings.ENGINE_ONNX, ignoreCase = true) == true

    LaunchedEffect(isOnnxModel, hardwareTarget) {
        if (isOnnxModel && hardwareTarget != SingSettings.TARGET_NPU && hardwareTarget != SingSettings.TARGET_CPU) {
            onHardwareTargetChange(SingSettings.TARGET_NPU)
        }
    }

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pinnedTopBarHeight = 64.dp
    val totalPinnedHeight = pinnedTopBarHeight + statusBarTop

    // AOSP 16 Expressive style: generous breathing room for the large title before scrolling
    val collapseRange = 110.dp
    val collapseRangePx = with(density) { collapseRange.toPx() }
    val progress = if (collapseRangePx > 0f) {
        (scrollState.value.toFloat() / collapseRangePx).coerceIn(0f, 1f)
    } else 0f

    val iconAlpha = (1f - progress * 2.2f).coerceIn(0f, 1f)
    val iconScale = 1f - progress * 0.35f
    val subtitleAlpha = (1f - progress * 2.4f).coerceIn(0f, 1f)

    val titleFontSize = (36f - 16f * progress).sp
    val titleStartX = 20.dp
    val titleEndX = if (openedFromMusic) 68.dp else 20.dp
    val currentTitleX = titleStartX + (titleEndX - titleStartX) * progress
    val titleStartY = statusBarTop + 86.dp
    val titleEndY = statusBarTop + (pinnedTopBarHeight - 28.dp) / 2
    val currentTitleY = titleStartY - (titleStartY - titleEndY) * progress

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Main Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            // Spacing to clear the huge expanded title header with comfortable margin
            Spacer(modifier = Modifier.height(statusBarTop + 196.dp))

            val isMaterialYou = LocalIsMaterialYou.current
            val accent2_900 = getAccent2_900()

            // 1. Status Card (AOSP Wi-Fi Entity Header style - grouped items with thin gap)
            GroupedCardItem(index = 0, total = 2) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isModuleLoaded) {
                                        if (isMaterialYou) Primary else Primary.copy(alpha = 0.15f)
                                    } else {
                                        TextSecondary.copy(alpha = 0.15f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isModuleLoaded) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                                contentDescription = null,
                                tint = if (isModuleLoaded) {
                                    if (isMaterialYou) accent2_900 else Primary
                                } else {
                                    TextSecondary
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = if (isModuleLoaded) R.string.status_loaded else R.string.status_not_loaded),
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isModuleLoaded) {
                                Text(
                                    text = xposedManagerInfo.ifEmpty { "Active Service" },
                                    color = TextSecondary,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }

                    if (!isModuleLoaded) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(id = R.string.tip_not_loaded),
                            color = TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            GroupedCardItem(index = 1, total = 2) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Active Model Row
                    StatusInfoRow(
                        label = stringResource(id = R.string.label_neural_model),
                        value = when {
                            selectedModelId.isEmpty() -> stringResource(id = R.string.status_no_model_selected)
                            currentModel != null -> stringResource(
                                id = R.string.status_model_format,
                                "${currentModel.modelDisplayName} (${currentModel.displayName})",
                                threadCount
                            )
                            else -> stringResource(id = R.string.status_model_format, selectedModelId, threadCount)
                        },
                        onClick = { onNavigateToModelSelection(true) }
                    )
                }
            }

            // 2. Action Buttons Row (Material Design 3 ButtonGroup with Expressive animateWidth)
            ButtonGroup(
                overflowIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                try {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.apple.android.music")
                                    launchIntent?.let { context.startActivity(it) }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier
                                .animateWidth(interactionSource)
                                .weight(1f)
                                .height(44.dp),
                            interactionSource = interactionSource,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceContainerHigh,
                                contentColor = TextPrimary
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_music_app_icon),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = stringResource(id = R.string.open_apple_music_short),
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    menuContent = { menuState ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.open_apple_music_short)) },
                            onClick = {
                                try {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.apple.android.music")
                                    launchIntent?.let { context.startActivity(it) }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                menuState.dismiss()
                            }
                        )
                    }
                )

                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        Button(
                            onClick = { onNavigateToModelSelection(false) },
                            modifier = Modifier
                                .animateWidth(interactionSource)
                                .weight(1f)
                                .height(44.dp),
                            interactionSource = interactionSource,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceContainerHigh,
                                contentColor = TextPrimary
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_ai_models),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = stringResource(id = R.string.action_select_model),
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    menuContent = { menuState ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.action_select_model)) },
                            onClick = {
                                onNavigateToModelSelection(false)
                                menuState.dismiss()
                            }
                        )
                    }
                )

                val isBenchmarkEnabled = !benchmarkState.isRunning && selectedModelId.isNotEmpty() && currentModel != null
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                onRunBenchmark()
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                            },
                            enabled = isBenchmarkEnabled,
                            modifier = Modifier
                                .animateWidth(interactionSource)
                                .weight(1f)
                                .height(44.dp),
                            interactionSource = interactionSource,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceContainerHigh,
                                contentColor = TextPrimary,
                                disabledContainerColor = SurfaceContainerHigh.copy(alpha = 0.4f),
                                disabledContentColor = TextSecondary.copy(alpha = 0.6f)
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_speedometer_70),
                                    contentDescription = null,
                                    tint = if (isBenchmarkEnabled) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = stringResource(id = R.string.action_benchmark),
                                    color = if (isBenchmarkEnabled) TextPrimary else TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    menuContent = { menuState ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.action_benchmark)) },
                            enabled = isBenchmarkEnabled,
                            onClick = {
                                onRunBenchmark()
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                }
                                menuState.dismiss()
                            }
                        )
                    }
                )
            }

            // 3. Sing Settings Section
            SectionHeader(title = stringResource(id = R.string.title_sing_settings))

            GroupedCardItem(index = 0, total = 4) {
                SettingSwitchRow(
                    icon = painterResource(id = R.drawable.ic_preserve_sides),
                    title = stringResource(id = R.string.setting_preserve_side_title),
                    description = stringResource(id = R.string.desc_preserve_side),
                    checked = preserveSide,
                    onCheckedChange = onPreserveSideChange
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupedCardItem(index = 1, total = 4) {
                SettingSwitchRow(
                    icon = painterResource(id = R.drawable.ic_instrumental),
                    title = stringResource(id = R.string.setting_two_way_slider_title),
                    description = stringResource(id = R.string.desc_two_way_slider),
                    checked = twoWaySlider,
                    onCheckedChange = onTwoWaySliderChange
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupedCardItem(index = 2, total = 4) {
                SettingSwitchRow(
                    icon = painterResource(id = R.drawable.ic_slider_unlocked),
                    title = stringResource(id = R.string.setting_full_vocal_mute_title),
                    description = stringResource(id = R.string.setting_full_vocal_mute_desc),
                    checked = allowZeroFloor,
                    onCheckedChange = onAllowZeroFloorChange
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupedCardItem(index = 3, total = 4) {
                SettingSwitchRow(
                    icon = painterResource(id = R.drawable.ic_quarternotes),
                    title = stringResource(id = R.string.setting_force_song_eligibility_title),
                    description = stringResource(id = R.string.desc_force_song_eligibility),
                    checked = forceSongEligibility,
                    onCheckedChange = onForceSongEligibilityChange
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Performance & Hardware Section
            SectionHeader(title = stringResource(id = R.string.title_performance_settings))

            GroupedCardItem(index = 0, total = 3) {
                SettingSwitchRow(
                    icon = painterResource(id = R.drawable.ic_thermometer_low),
                    title = stringResource(id = R.string.setting_eco_mode_title),
                    description = stringResource(id = R.string.setting_eco_mode_desc),
                    checked = ecoMode,
                    onCheckedChange = onEcoModeChange
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupedCardItem(index = 1, total = 3) {
                SettingHardwareTargetRow(
                    hardwareTarget = hardwareTarget,
                    isOnnx = isOnnxModel,
                    onHardwareTargetChange = onHardwareTargetChange
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            GroupedCardItem(index = 2, total = 3) {
                SettingSliderRow(
                    icon = painterResource(id = R.drawable.ic_layers_3),
                    title = stringResource(id = R.string.setting_thread_count_title),
                    value = threadCount,
                    valueRange = 1f..12f,
                    onValueChange = onThreadCountChange,
                    description = stringResource(id = R.string.setting_thread_count_desc)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Processing & Pipeline Details (Wi-Fi details spec sheet style)
            SectionHeader(title = stringResource(id = R.string.title_engine_details))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_speedometer_70),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = stringResource(id = R.string.title_benchmark),
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(id = R.string.desc_benchmark_subtitle),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val isBenchmarkEnabled = !benchmarkState.isRunning && selectedModelId.isNotEmpty() && currentModel != null
                    val btnTextColor = if (isMaterialYou) accent2_900 else TextPrimary

                    Button(
                        onClick = onRunBenchmark,
                        enabled = isBenchmarkEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            disabledContainerColor = Primary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(
                            text = if (benchmarkState.isRunning) {
                                stringResource(id = R.string.btn_benchmarking)
                            } else {
                                stringResource(id = R.string.btn_run_benchmark)
                            },
                            color = if (isBenchmarkEnabled) btnTextColor else btnTextColor.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (benchmarkState.isRunning) {
                        Spacer(modifier = Modifier.height(16.dp))
                        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                        androidx.compose.material3.LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Primary,
                            trackColor = SurfaceContainerHigh
                        )
                    }

                    benchmarkState.result?.let { result ->
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = DividerDark.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.label_processing_speed),
                                color = TextSecondary,
                                fontSize = 13.5.sp
                            )
                            val isFast = result.rtf >= 1.2
                            val badgeBg = when {
                                !isFast -> SurfaceContainerHigh
                                isMaterialYou -> Primary
                                else -> Primary.copy(alpha = 0.16f)
                            }
                            val badgeTextColor = when {
                                !isFast -> TextSecondary
                                isMaterialYou -> accent2_900
                                else -> Primary
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(badgeBg)
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = stringResource(id = R.string.benchmark_rtf_format, result.rtf),
                                    color = badgeTextColor,
                                    fontSize = 14.sp,
                                    fontWeight = if (isFast) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val verdictColor = if (result.rtf >= 1.2) {
                            Primary
                        } else {
                            TextSecondary
                        }
                        Text(
                            text = if (result.rtf >= 1.2) {
                                stringResource(id = R.string.verdict_fast_enough)
                            } else {
                                stringResource(id = R.string.verdict_too_slow)
                            },
                            color = verdictColor,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DividerDark.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Wi-Fi specs style technical readout table
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_neural_engine),
                        value = if (isOnnxModel) "ONNX Runtime QNN v1.29" else "Alibaba MNN v3.6.1"
                    )
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_compute_precision),
                        value = currentModel?.precision?.uppercase() ?: "FP16"
                    )
                    val isHardwareFallback = remember(hardwareTarget, activeProvider) {
                        if (activeProvider.isNotBlank()) {
                            when (hardwareTarget) {
                                SingSettings.TARGET_NPU -> !activeProvider.contains("HTP", ignoreCase = true) && !activeProvider.contains("NPU", ignoreCase = true)
                                SingSettings.TARGET_GPU -> !activeProvider.contains("GPU", ignoreCase = true) && !activeProvider.contains("OpenCL", ignoreCase = true) && !activeProvider.contains("Vulkan", ignoreCase = true)
                                SingSettings.TARGET_NNAPI -> !activeProvider.contains("NNAPI", ignoreCase = true)
                                else -> false
                            }
                        } else hardwareTarget == SingSettings.TARGET_NPU && !org.francasio.sing.util.DeviceHelper.isQualcommWithHexagonHtp
                    }
                    val actualHardwareDisplay = remember(hardwareTarget, activeProvider, isHardwareFallback) {
                        if (isHardwareFallback) {
                            val fallbackTarget = when {
                                activeProvider.contains("CPU", ignoreCase = true) || activeProvider.contains("XNNPACK", ignoreCase = true) -> "CPU"
                                activeProvider.contains("GPU", ignoreCase = true) || activeProvider.contains("OpenCL", ignoreCase = true) || activeProvider.contains("Vulkan", ignoreCase = true) -> "GPU"
                                activeProvider.isNotBlank() -> activeProvider
                                else -> "CPU"
                            }
                            val requested = when (hardwareTarget) {
                                SingSettings.TARGET_NPU -> "NPU"
                                SingSettings.TARGET_GPU -> "GPU"
                                SingSettings.TARGET_NNAPI -> "NNAPI"
                                else -> hardwareTarget
                            }
                            "$fallbackTarget (Fallback from $requested)"
                        } else if (activeProvider.isNotBlank()) {
                            activeProvider
                        } else {
                            when (hardwareTarget) {
                                SingSettings.TARGET_NPU -> "Qualcomm Hexagon HTP NPU"
                                SingSettings.TARGET_GPU -> "OpenCL Mobile GPU"
                                SingSettings.TARGET_NNAPI -> "Android NNAPI"
                                else -> "Multi-threaded CPU"
                            }
                        }
                    }
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_target_hardware),
                        value = actualHardwareDisplay,
                        valueColor = if (isHardwareFallback) Color(0xFFFFB74D) else TextPrimary
                    )
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_audio_pipeline),
                        value = "KissFFT STFT (6144) ➔ NN ➔ iSTFT"
                    )
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_audio_format),
                        value = "44,100 Hz • Stereo 2-Ch"
                    )

                    benchmarkState.result?.let { result ->
                        PipelineSpecRow(
                            label = stringResource(id = R.string.label_chunk_latency),
                            value = "${result.totalMs} ms (Audio: 5,921 ms)"
                        )
                        PipelineSpecRow(
                            label = stringResource(id = R.string.label_breakdown),
                            value = "STFT: ${result.stftMs}ms • NN: ${result.nnMs}ms • iSTFT: ${result.istftMs}ms"
                        )
                    }
                }
            }

            // Bottom spacer with see-through navbar padding so cards glide beneath the transparent system navbar
            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Pinned Top Bar Background (fades in as user scrolls)
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

        // Header Circle (Dynamic back button when opened from Music shortcut, subtle icon when launched from launcher)
        if (openedFromMusic) {
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
                    .clickable(onClick = onBackFromMusic),
                contentAlignment = Alignment.Center
            ) {
                if (progress < 0.6f) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sing),
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
                        contentDescription = "Back to Apple Music",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                alpha = ((progress - 0.4f) * 2.5f).coerceIn(0f, 1f)
                            }
                    )
                }
            }
        } else {
            // Launcher Mode: Icon stays in header and fades out as user scrolls (no easter egg click)
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
                        painter = painterResource(id = R.drawable.ic_sing),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Subtitle (version text, fades out on scroll)
        if (subtitleAlpha > 0f) {
            Text(
                text = apkVersion,
                color = TextSecondary.copy(alpha = subtitleAlpha),
                fontSize = 13.5.sp,
                modifier = Modifier
                    .offset(x = titleStartX, y = statusBarTop + 132.dp)
                    .graphicsLayer {
                        alpha = subtitleAlpha
                    }
            )
        }

        // Huge Page Title (shrinks and glides up into the pinned compact TopAppBar)
        Text(
            text = stringResource(id = R.string.app_name),
            color = TextPrimary,
            fontSize = titleFontSize,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.offset(x = currentTitleX, y = currentTitleY)
        )

        // Info Button pinned at Top Right (accessible at all times)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(
                    x = (-16).dp,
                    y = statusBarTop + (pinnedTopBarHeight - 40.dp) / 2
                )
                .size(40.dp)
                .clip(CircleShape)
                .background(SurfaceContainerHigh.copy(alpha = 0.7f + progress * 0.3f))
                .clickable(onClick = onNavigateToInfo),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(id = R.string.action_info),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val isMaterialYou = LocalIsMaterialYou.current
    Text(
        text = title,
        color = if (isMaterialYou) Primary else TextSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
private fun PipelineSpecRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.6f, fill = false)
        )
    }
}

@Composable
private fun StatusInfoRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onClick() }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
                }
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(2f, fill = false)
        ) {
            Text(
                text = value,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (onClick != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun SettingSwitchRow(
    icon: Painter,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current
    val accent1_200 = getAccent1_200()
    val accent1_900 = getAccent1_900()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Material 3 Expressive Switch with check icon in thumb when ON, X icon when OFF
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = {
                if (checked) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                        tint = if (isMaterialYou) accent1_200 else PrimaryDark
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                        tint = Color(0xFF2C2C32)
                    )
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (isMaterialYou) accent1_900 else Color.White,
                checkedTrackColor = if (isMaterialYou) accent1_200 else Primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color(0xFFB0B0B4),
                uncheckedTrackColor = Color(0xFF2C2C32),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingHardwareTargetRow(
    hardwareTarget: String,
    isOnnx: Boolean = false,
    onHardwareTargetChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val targets = remember(isOnnx) {
        if (isOnnx) {
            listOf(
                SingSettings.TARGET_NPU,
                SingSettings.TARGET_CPU
            )
        } else {
            listOf(
                SingSettings.TARGET_NPU,
                SingSettings.TARGET_GPU,
                SingSettings.TARGET_NNAPI,
                SingSettings.TARGET_CPU
            )
        }
    }
    val effectiveTarget = if (hardwareTarget in targets) hardwareTarget else SingSettings.TARGET_NPU
    val isMaterialYou = LocalIsMaterialYou.current
    val checkedTextColor = if (isMaterialYou) getAccent2_900() else Color.White

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_engine_sparkles),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.setting_hardware_target_title),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (effectiveTarget) {
                        SingSettings.TARGET_NPU -> stringResource(id = R.string.hardware_target_npu_desc)
                        SingSettings.TARGET_GPU -> stringResource(id = R.string.hardware_target_gpu_desc)
                        SingSettings.TARGET_NNAPI -> stringResource(id = R.string.hardware_target_nnapi_desc)
                        SingSettings.TARGET_CPU -> stringResource(id = R.string.hardware_target_cpu_desc)
                        else -> stringResource(id = R.string.setting_hardware_target_desc)
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            targets.forEachIndexed { index, target ->
                val isSelected = (effectiveTarget == target)
                ToggleButton(
                    checked = isSelected,
                    onCheckedChange = { onHardwareTargetChange(target) },
                    modifier = Modifier.weight(1f),
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        targets.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.toggleButtonColors(
                        containerColor = SurfaceContainerHigh,
                        contentColor = TextSecondary,
                        checkedContainerColor = Primary,
                        checkedContentColor = checkedTextColor
                    )
                ) {
                    Text(
                        text = target,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) checkedTextColor else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSliderRow(
    icon: Painter,
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.setting_thread_count_badge_format, value),
                    color = Primary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange,
            steps = (valueRange.endInclusive - valueRange.start - 1).toInt(),
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary,
                inactiveTrackColor = SurfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = description,
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    AppleMusicSingTheme {
        MainScreen(
            isModuleLoaded = true,
            xposedManagerInfo = "LSPosed v1.9.2",
            preserveSide = false,
            twoWaySlider = true,
            allowZeroFloor = true,
            forceSongEligibility = true,
            ecoMode = true,
            hardwareTarget = SingSettings.TARGET_NPU,
            selectedModelId = "uvr_mdxnet_fp16",
            threadCount = 4,
            benchmarkState = BenchmarkUiState(
                isRunning = false,
                result = BenchmarkResult(
                    rtf = 3.2,
                    totalMs = 1850,
                    stftMs = 12,
                    nnMs = 1820,
                    istftMs = 18,
                    threadCount = 4,
                    provider = "ONNX QNN (Hexagon HTP NPU)"
                )
            ),
            onPreserveSideChange = {},
            onTwoWaySliderChange = {},
            onAllowZeroFloorChange = {},
            onForceSongEligibilityChange = {},
            onEcoModeChange = {},
            onHardwareTargetChange = {},
            onModelSelect = {},
            onThreadCountChange = {},
            onRunBenchmark = {}
        )
    }
}
