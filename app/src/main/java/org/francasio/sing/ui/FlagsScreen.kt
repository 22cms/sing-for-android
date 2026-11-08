package org.francasio.sing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.ui.components.CollapsingTopBarDefaults
import org.francasio.sing.ui.components.ExpressiveCollapsingTopBar
import org.francasio.sing.ui.components.GroupedSection
import org.francasio.sing.ui.components.NavigationIcon
import org.francasio.sing.ui.components.rememberCollapsingProgress
import org.francasio.sing.ui.theme.Accent1_100
import org.francasio.sing.ui.theme.Accent1_200
import org.francasio.sing.ui.theme.Accent1_900
import org.francasio.sing.ui.theme.BackgroundDark
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.PrimaryDark
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary

@Composable
fun FlagsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val progress = rememberCollapsingProgress(
        scrollState = scrollState,
        collapseRange = CollapsingTopBarDefaults.CollapseRangeCompact
    )

    var forceNoQnnSoc by remember {
        mutableStateOf(SingSettings.getForceNoQnnSocState(context))
    }
    var forceUnknownSoc by remember {
        mutableStateOf(SingSettings.getForceUnknownQcomSocState(context))
    }
    var debugModelFetching by remember {
        mutableStateOf(SingSettings.getDebugModelFetching(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CollapsingTopBarDefaults.DefaultMaxContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(statusBarTop + CollapsingTopBarDefaults.ContentPaddingCompact))

                GroupedSection(title = stringResource(id = R.string.flag_section)) {
                    item {
                        FlagSwitchRow(
                            title = stringResource(id = R.string.flag_force_no_qnn_soc_title),
                            codeName = SingSettings.KEY_FORCE_NO_HTP_SOC_STATE,
                            description = stringResource(id = R.string.flag_force_no_qnn_soc_desc),
                            checked = forceNoQnnSoc,
                            onCheckedChange = { isChecked ->
                                forceNoQnnSoc = isChecked
                                SingSettings.setForceNoQnnSocState(context, isChecked)
                            }
                        )
                    }
                    item {
                        FlagSwitchRow(
                            title = stringResource(id = R.string.flag_force_unknown_qcom_soc_title),
                            codeName = SingSettings.KEY_FORCE_UNKNOWN_QCOM_SOC_STATE,
                            description = stringResource(id = R.string.flag_force_unknown_qcom_soc_desc),
                            checked = forceUnknownSoc,
                            onCheckedChange = { isChecked ->
                                forceUnknownSoc = isChecked
                                SingSettings.setForceUnknownQcomSocState(context, isChecked)
                            }
                        )
                    }
                    item {
                        FlagSwitchRow(
                            title = stringResource(id = R.string.flag_debug_model_fetching_title),
                            codeName = SingSettings.KEY_DEBUG_MODEL_FETCHING,
                            description = stringResource(id = R.string.flag_debug_model_fetching_desc),
                            checked = debugModelFetching,
                            onCheckedChange = { isChecked ->
                                debugModelFetching = isChecked
                                SingSettings.setDebugModelFetching(context, isChecked)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }

        ExpressiveCollapsingTopBar(
            progress = progress,
            title = stringResource(id = R.string.title_flags),
            maxContentWidth = CollapsingTopBarDefaults.DefaultMaxContentWidth,
            navigationIcon = NavigationIcon.Back(onBack = onBack)
        )
    }
}

@Composable
private fun FlagSwitchRow(
    title: String,
    codeName: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = codeName,
                color = if (isMaterialYou) Primary else Accent1_100,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = {
                if (checked) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                        tint = if (isMaterialYou) Accent1_200 else PrimaryDark
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
                checkedThumbColor = if (isMaterialYou) Accent1_900 else Color.White,
                checkedTrackColor = if (isMaterialYou) Accent1_200 else Primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color(0xFFB0B0B4),
                uncheckedTrackColor = Color(0xFF2C2C32),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
