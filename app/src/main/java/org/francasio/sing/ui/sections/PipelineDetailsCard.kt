package org.francasio.sing.ui.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.BenchmarkUiState
import org.francasio.sing.ui.components.PipelineSpecRow
import org.francasio.sing.ui.components.SectionHeader
import org.francasio.sing.ui.theme.*
import org.francasio.sing.util.DeviceHelper
import org.francasio.sing.util.ModelLoader

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PipelineDetailsCard(
    benchmarkState: BenchmarkUiState,
    isBenchmarkEnabled: Boolean,
    isOnnxModel: Boolean,
    currentModel: ModelLoader.ModelInfo?,
    hardwareTarget: String,
    activeProvider: String,
    onRunBenchmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(id = R.string.title_engine_details))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_speedometer_70),
                        contentDescription = null,
                        tint = TextPrimary,
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

                val btnTextColor = if (isMaterialYou) Accent2_900 else TextPrimary

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
                    LinearWavyProgressIndicator(
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
                            isMaterialYou -> Accent2_900
                            else -> Primary
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(badgeBg)
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (result.rtf <= 0.0) stringResource(id = R.string.benchmark_failed) else stringResource(id = R.string.benchmark_rtf_format, result.rtf),
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
                        text = if (result.rtf <= 0.0) {
                            stringResource(id = R.string.benchmark_failed)
                        } else if (result.rtf >= 1.2) {
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

                // Technical readout specs
                PipelineSpecRow(
                    label = stringResource(id = R.string.label_neural_engine),
                    value = when {
                        currentModel?.isViNNet == true -> "Native Vi-NNet (NEON C++)"
                        isOnnxModel -> "ONNX Runtime QNN v1.29"
                        else -> "Alibaba MNN v3.6.1"
                    }
                )
                // The NPU/HTP backends force FP16 execution. Libraries do not report
                // on-the-fly quantization, so we infer it from an FP32 model running
                // on an NPU target (ONNX QNN HTP / MNN QNN).
                val precisionConverted = remember(currentModel, hardwareTarget, isOnnxModel) {
                    currentModel != null &&
                        currentModel.precision.equals("FP32", ignoreCase = true) &&
                        hardwareTarget == SingSettings.TARGET_NPU &&
                        (isOnnxModel || currentModel.isMnn)
                }
                PipelineSpecRow(
                    label = stringResource(id = R.string.label_compute_precision),
                    value = if (precisionConverted) "FP32→FP16" else (currentModel?.precision?.uppercase() ?: "FP16"),
                    valueColor = if (precisionConverted) DefaultYellowError else TextPrimary
                )

                val isHardwareFallback = remember(hardwareTarget, activeProvider) {
                    if (activeProvider.isNotBlank()) {
                        when (hardwareTarget) {
                            SingSettings.TARGET_NPU -> !activeProvider.contains("HTP", ignoreCase = true) && !activeProvider.contains("NPU", ignoreCase = true)
                            SingSettings.TARGET_GPU -> !activeProvider.contains("GPU", ignoreCase = true) && !activeProvider.contains("OpenCL", ignoreCase = true) && !activeProvider.contains("Vulkan", ignoreCase = true)
                            SingSettings.TARGET_NNAPI -> !activeProvider.contains("NNAPI", ignoreCase = true)
                            else -> false
                        }
                    } else hardwareTarget == SingSettings.TARGET_NPU && !DeviceHelper.isQualcommWithHexagonHtp
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
                    valueColor = if (isHardwareFallback) DefaultYellowError else TextPrimary
                )
                PipelineSpecRow(
                    label = stringResource(id = R.string.label_audio_pipeline),
                    value = if (currentModel?.isTimeDomain == true) "Time-Domain Frame Pacing (4096) ➔ NN" else "KissFFT STFT (6144) ➔ NN ➔ iSTFT"
                )
                PipelineSpecRow(
                    label = stringResource(id = R.string.label_audio_format),
                    value = "44,100 Hz • Stereo 2-Ch"
                )

                benchmarkState.result?.let { result ->
                    val isTimeDomain = currentModel?.isTimeDomain == true
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_chunk_latency),
                        value = if (isTimeDomain) "${result.totalMs} ms (Audio: ~93 ms)" else "${result.totalMs} ms (Audio: 5,921 ms)"
                    )
                    PipelineSpecRow(
                        label = stringResource(id = R.string.label_breakdown),
                        value = if (isTimeDomain) "Time-Domain NN: ${result.nnMs}ms" else "STFT: ${result.stftMs}ms • NN: ${result.nnMs}ms • iSTFT: ${result.istftMs}ms"
                    )
                }
            }
        }
    }
}

