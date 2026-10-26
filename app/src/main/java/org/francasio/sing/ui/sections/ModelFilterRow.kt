package org.francasio.sing.ui.sections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.ui.theme.Accent2_900
import org.francasio.sing.ui.theme.DividerDark
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.SurfaceContainerHigh
import org.francasio.sing.ui.theme.SurfaceDark
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary

@Composable
fun ModelFilterRow(
    availableFamilies: List<String>,
    selectedFamilyFilter: String?,
    onFamilyFilterChange: (String?) -> Unit,
    selectedStemFilter: String?,
    onStemFilterChange: (String?) -> Unit,
    backingRetentionOnly: Boolean,
    onBackingRetentionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current
    var isFamilyMenuExpanded by remember { mutableStateOf(false) }

    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = SurfaceContainerHigh.copy(alpha = 0.5f),
        labelColor = TextPrimary,
        iconColor = TextSecondary,
        selectedContainerColor = if (isMaterialYou) Primary else Primary.copy(alpha = 0.18f),
        selectedLabelColor = if (isMaterialYou) Accent2_900 else Primary,
        selectedLeadingIconColor = if (isMaterialYou) Accent2_900 else Primary,
        selectedTrailingIconColor = if (isMaterialYou) Accent2_900 else Primary
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Model Family (Expandable Filter Chip)
        Box {
            FilterChip(
                selected = selectedFamilyFilter != null,
                onClick = { isFamilyMenuExpanded = true },
                label = {
                    Text(
                        text = selectedFamilyFilter ?: stringResource(R.string.filter_family),
                        fontSize = 13.sp,
                        fontWeight = if (selectedFamilyFilter != null) FontWeight.Medium else FontWeight.Normal
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_neural_network),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                },
                trailingIcon = {
                    val rotation by animateFloatAsState(
                        targetValue = if (isFamilyMenuExpanded) 180f else 0f,
                        animationSpec = tween(200),
                        label = "familyDropdownRotation"
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(FilterChipDefaults.IconSize)
                            .rotate(rotation)
                    )
                },
                colors = chipColors,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedFamilyFilter != null,
                    borderColor = DividerDark.copy(alpha = 0.6f),
                    selectedBorderColor = if (isMaterialYou) Color.Transparent else Primary.copy(alpha = 0.4f)
                )
            )

            DropdownMenu(
                expanded = isFamilyMenuExpanded,
                onDismissRequest = { isFamilyMenuExpanded = false },
                modifier = Modifier.background(SurfaceDark)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filter_all_families), color = TextPrimary) },
                    onClick = {
                        onFamilyFilterChange(null)
                        isFamilyMenuExpanded = false
                    },
                    leadingIcon = if (selectedFamilyFilter == null) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = Primary
                            )
                        }
                    } else null
                )
                availableFamilies.forEach { family ->
                    val isSelected = selectedFamilyFilter.equals(family, ignoreCase = true)
                    DropdownMenuItem(
                        text = { Text(family, color = TextPrimary) },
                        onClick = {
                            onFamilyFilterChange(if (isSelected) null else family)
                            isFamilyMenuExpanded = false
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            }
                        } else null
                    )
                }
            }
        }

        // 2. Vocal Output Filter Chip
        FilterChip(
            selected = selectedStemFilter == "vocal",
            onClick = {
                onStemFilterChange(if (selectedStemFilter == "vocal") null else "vocal")
            },
            label = {
                Text(
                    text = stringResource(R.string.filter_vocal),
                    fontSize = 13.sp,
                    fontWeight = if (selectedStemFilter == "vocal") FontWeight.Medium else FontWeight.Normal
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_sing),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            },
            colors = chipColors,
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedStemFilter == "vocal",
                borderColor = DividerDark.copy(alpha = 0.6f),
                selectedBorderColor = if (isMaterialYou) Color.Transparent else Primary.copy(alpha = 0.4f)
            )
        )

        // 3. Instrumental Output Filter Chip
        FilterChip(
            selected = selectedStemFilter == "instrumental",
            onClick = {
                onStemFilterChange(if (selectedStemFilter == "instrumental") null else "instrumental")
            },
            label = {
                Text(
                    text = stringResource(R.string.filter_instrumental),
                    fontSize = 13.sp,
                    fontWeight = if (selectedStemFilter == "instrumental") FontWeight.Medium else FontWeight.Normal
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_instrumental),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            },
            colors = chipColors,
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedStemFilter == "instrumental",
                borderColor = DividerDark.copy(alpha = 0.6f),
                selectedBorderColor = if (isMaterialYou) Color.Transparent else Primary.copy(alpha = 0.4f)
            )
        )

        // 4. Backing Vocals Retention Filter Chip
        FilterChip(
            selected = backingRetentionOnly,
            onClick = {
                onBackingRetentionChange(!backingRetentionOnly)
            },
            label = {
                Text(
                    text = stringResource(R.string.filter_backing_retention),
                    fontSize = 13.sp,
                    fontWeight = if (backingRetentionOnly) FontWeight.Medium else FontWeight.Normal
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_preserve_sides),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            },
            colors = chipColors,
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = backingRetentionOnly,
                borderColor = DividerDark.copy(alpha = 0.6f),
                selectedBorderColor = if (isMaterialYou) Color.Transparent else Primary.copy(alpha = 0.4f)
            )
        )
    }
}

