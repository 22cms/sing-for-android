package org.francasio.sing.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.francasio.sing.R
import org.francasio.sing.ui.theme.Accent2_900
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.SurfaceDark
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary
import org.francasio.sing.util.XposedStatus

private data class WhatsNewFeature(val icon: Int, val title: Int, val description: Int)

private val releaseFeatures = listOf(
    WhatsNewFeature(R.drawable.ic_cloud_download, R.string.whats_new_title_1, R.string.whats_new_desc_1),
    WhatsNewFeature(R.drawable.ic_lyrics_blur, R.string.whats_new_title_2, R.string.whats_new_desc_2),
    WhatsNewFeature(R.drawable.ic_preserve_sides, R.string.whats_new_title_3, R.string.whats_new_desc_3),
    WhatsNewFeature(R.drawable.ic_update_notice, R.string.whats_new_title_4, R.string.whats_new_desc_4)
)

// Add minor change string IDs here in later releases; omit the disclosure when empty.
private val minorChanges: List<Int> = emptyList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val appVersion = remember(context) { XposedStatus.getAppVersion(context) }
    val buttonTextColor = if (LocalIsMaterialYou.current) Accent2_900 else TextPrimary
    var showMinorChanges by remember { mutableStateOf(false) }
    var isConfirming by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = { if (!isConfirming) onDismiss() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = SurfaceDark
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.whats_new_heading),
                    color = TextPrimary,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.whats_new_version, appVersion),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                releaseFeatures.forEach { feature ->
                    FeatureRow(feature)
                }

                if (minorChanges.isNotEmpty()) {
                    Column(modifier = Modifier.animateContentSize()) {
                        TextButton(onClick = { showMinorChanges = !showMinorChanges }) {
                            Text(stringResource(R.string.whats_new_minor_changes), color = Primary)
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.rotate(if (showMinorChanges) 180f else 0f)
                            )
                        }
                        AnimatedVisibility(visible = showMinorChanges) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                minorChanges.forEach { change ->
                                    Text(stringResource(change), color = TextSecondary, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))
            Button(
                onClick = {
                    if (!isConfirming) {
                        isConfirming = true
                        coroutineScope.launch {
                            sheetState.hide()
                            onConfirm()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Text(
                    text = stringResource(R.string.whats_new_close),
                    color = buttonTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(feature: WhatsNewFeature) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(feature.icon),
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(feature.title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(feature.description),
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}
