package org.francasio.sing.ui.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.francasio.sing.R
import org.francasio.sing.ui.components.GroupedCardItem
import org.francasio.sing.ui.components.SectionHeader
import org.francasio.sing.ui.components.SettingHardwareTargetRow
import org.francasio.sing.ui.components.SettingSliderRow
import org.francasio.sing.ui.components.SettingSwitchRow

@Composable
fun PerformanceSettingsCard(
    ecoMode: Boolean,
    hardwareTarget: String,
    isOnnxModel: Boolean,
    threadCount: Int,
    onEcoModeChange: (Boolean) -> Unit,
    onHardwareTargetChange: (String) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
    }
}

