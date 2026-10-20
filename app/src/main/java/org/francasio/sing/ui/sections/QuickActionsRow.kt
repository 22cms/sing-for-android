package org.francasio.sing.ui.sections

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.ui.theme.*

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuickActionsRow(
    isBenchmarkEnabled: Boolean,
    onNavigateToModelSelection: (scrollToSelected: Boolean) -> Unit,
    onRunBenchmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    ButtonGroup(
        overflowIndicator = {},
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // Apple Music action
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
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_music_app_icon),
                            contentDescription = null,
                            tint = TextPrimary,
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
                DropdownMenuItem(
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

        // Model Selection action
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
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_ai_models),
                            contentDescription = null,
                            tint = TextPrimary,
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
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.action_select_model)) },
                    onClick = {
                        onNavigateToModelSelection(false)
                        menuState.dismiss()
                    }
                )
            }
        )

        // Benchmark action
        customItem(
            buttonGroupContent = {
                val interactionSource = remember { MutableInteractionSource() }
                Button(
                    onClick = onRunBenchmark,
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
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_speedometer_70),
                            contentDescription = null,
                            tint = if (isBenchmarkEnabled) TextPrimary else TextPrimary.copy(alpha = 0.35f),
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
                DropdownMenuItem(
                    text = { Text(stringResource(id = R.string.action_benchmark)) },
                    enabled = isBenchmarkEnabled,
                    onClick = {
                        onRunBenchmark()
                        menuState.dismiss()
                    }
                )
            }
        )
    }
}

