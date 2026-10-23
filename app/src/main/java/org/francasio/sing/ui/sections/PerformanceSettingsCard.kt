package org.francasio.sing.ui.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.francasio.sing.R
import org.francasio.sing.ui.components.GroupedSection
import org.francasio.sing.ui.components.SettingHardwareTargetRow
import org.francasio.sing.ui.components.SettingSliderRow
import org.francasio.sing.ui.components.SettingSwitchRow

@Composable
fun PerformanceSettingsCard(
    ecoMode: Boolean,
    hardwareTarget: String,
    isOnnxModel: Boolean,
    isViNNetModel: Boolean = false,
    threadCount: Int,
    onEcoModeChange: (Boolean) -> Unit,
    onHardwareTargetChange: (String) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    GroupedSection(
        title = stringResource(id = R.string.title_performance_settings),
        modifier = modifier
    ) {
        item {
            SettingSwitchRow(
                icon = painterResource(id = R.drawable.ic_thermometer_low),
                title = stringResource(id = R.string.setting_eco_mode_title),
                description = stringResource(id = R.string.setting_eco_mode_desc),
                checked = ecoMode,
                onCheckedChange = onEcoModeChange
            )
        }
        if (!isViNNetModel) {
            item {
                SettingHardwareTargetRow(
                    hardwareTarget = hardwareTarget,
                    isOnnx = isOnnxModel,
                    onHardwareTargetChange = onHardwareTargetChange
                )
            }
        }
        item {
            SettingSliderRow(
                icon = painterResource(id = R.drawable.ic_layers_3),
                title = stringResource(id = R.string.setting_thread_count_title),
                value = threadCount,
                valueRange = 1f..12f,
                onValueChange = onThreadCountChange,
                description = stringResource(id = R.string.setting_thread_count_desc)
            )
        }
    }
}
