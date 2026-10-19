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
import org.francasio.sing.ui.components.SettingSwitchRow

@Composable
fun SingSettingsCard(
    preserveSide: Boolean,
    twoWaySlider: Boolean,
    allowZeroFloor: Boolean,
    forceSongEligibility: Boolean,
    onPreserveSideChange: (Boolean) -> Unit,
    onTwoWaySliderChange: (Boolean) -> Unit,
    onAllowZeroFloorChange: (Boolean) -> Unit,
    onForceSongEligibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
    }
}

