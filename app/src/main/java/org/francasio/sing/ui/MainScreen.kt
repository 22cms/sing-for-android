package org.francasio.sing.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.components.rememberCollapsingProgress
import org.francasio.sing.ui.sections.MainTopBar
import org.francasio.sing.ui.sections.PerformanceSettingsCard
import org.francasio.sing.ui.sections.PipelineDetailsCard
import org.francasio.sing.ui.sections.QuickActionsRow
import org.francasio.sing.ui.sections.SingSettingsCard
import org.francasio.sing.ui.sections.StatusCard
import org.francasio.sing.ui.theme.AppleMusicSingTheme
import org.francasio.sing.ui.theme.BackgroundDark
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
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val progress = rememberCollapsingProgress(scrollState)

    val isBenchmarkEnabled = !benchmarkState.isRunning && selectedModelId.isNotEmpty() && currentModel != null

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
            // Spacing to clear the huge expanded title header
            Spacer(modifier = Modifier.height(statusBarTop + 196.dp))

            // 1. Status Card
            StatusCard(
                isModuleLoaded = isModuleLoaded,
                xposedManagerInfo = xposedManagerInfo,
                selectedModelId = selectedModelId,
                currentModel = currentModel,
                threadCount = threadCount,
                onNavigateToModelSelection = onNavigateToModelSelection
            )

            // 2. Action Buttons Row
            QuickActionsRow(
                isBenchmarkEnabled = isBenchmarkEnabled,
                onNavigateToModelSelection = onNavigateToModelSelection,
                onRunBenchmark = {
                    onRunBenchmark()
                    coroutineScope.launch {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }
            )

            // 3. Sing Settings Section
            SingSettingsCard(
                preserveSide = preserveSide,
                twoWaySlider = twoWaySlider,
                allowZeroFloor = allowZeroFloor,
                forceSongEligibility = forceSongEligibility,
                onPreserveSideChange = onPreserveSideChange,
                onTwoWaySliderChange = onTwoWaySliderChange,
                onAllowZeroFloorChange = onAllowZeroFloorChange,
                onForceSongEligibilityChange = onForceSongEligibilityChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Performance & Hardware Section
            PerformanceSettingsCard(
                ecoMode = ecoMode,
                hardwareTarget = hardwareTarget,
                isOnnxModel = isOnnxModel,
                threadCount = threadCount,
                onEcoModeChange = onEcoModeChange,
                onHardwareTargetChange = onHardwareTargetChange,
                onThreadCountChange = onThreadCountChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Processing & Pipeline Details
            PipelineDetailsCard(
                benchmarkState = benchmarkState,
                isBenchmarkEnabled = isBenchmarkEnabled,
                isOnnxModel = isOnnxModel,
                currentModel = currentModel,
                hardwareTarget = hardwareTarget,
                activeProvider = activeProvider,
                onRunBenchmark = onRunBenchmark
            )

            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Pinned Top Bar & Animated Header
        MainTopBar(
            progress = progress,
            openedFromMusic = openedFromMusic,
            apkVersion = apkVersion,
            onBackFromMusic = onBackFromMusic,
            onNavigateToInfo = onNavigateToInfo
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
