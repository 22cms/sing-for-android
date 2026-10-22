package org.francasio.sing.ui.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.ui.components.GroupedCardItem
import org.francasio.sing.ui.components.StatusInfoRow
import org.francasio.sing.ui.theme.*
import org.francasio.sing.util.ModelLoader

@Composable
fun StatusCard(
    isModuleLoaded: Boolean,
    xposedManagerInfo: String,
    selectedModelId: String,
    currentModel: ModelLoader.ModelInfo?,
    threadCount: Int,
    onNavigateToModelSelection: (scrollToSelected: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current

    Column(modifier = modifier.fillMaxWidth()) {
        // Module Status Item
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
                            imageVector = if (isModuleLoaded) Icons.Outlined.CheckCircle else Icons.Outlined.Close,
                            contentDescription = null,
                            tint = if (isModuleLoaded) {
                                if (isMaterialYou) Accent2_900 else Primary
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

        // Active Model Item
        GroupedCardItem(index = 1, total = 2) {
            Column(modifier = Modifier.padding(18.dp)) {
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
    }
}

