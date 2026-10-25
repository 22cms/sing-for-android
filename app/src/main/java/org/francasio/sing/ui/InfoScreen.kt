package org.francasio.sing.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.ui.components.CollapsingTopBarDefaults
import org.francasio.sing.ui.components.ExpressiveCollapsingTopBar
import org.francasio.sing.ui.components.GroupedCardItem
import org.francasio.sing.ui.components.NavigationIcon
import org.francasio.sing.ui.components.SectionHeader
import org.francasio.sing.ui.components.SettingActionRow
import org.francasio.sing.ui.components.SettingSwitchRow
import org.francasio.sing.ui.components.rememberCollapsingProgress
import org.francasio.sing.ui.theme.Accent1_100
import org.francasio.sing.ui.theme.BackgroundDark
import org.francasio.sing.ui.theme.CardDark
import org.francasio.sing.ui.theme.DividerDark
import org.francasio.sing.ui.theme.LocalIsMaterialYou
import org.francasio.sing.ui.theme.Neutral1_800
import org.francasio.sing.ui.theme.Primary
import org.francasio.sing.ui.theme.SurfaceContainerHigh
import org.francasio.sing.ui.theme.TextPrimary
import org.francasio.sing.ui.theme.TextSecondary
import org.francasio.sing.util.DeviceHelper

private data class ThirdPartyProject(
    val name: String,
    val authors: String,
    val description: String
)

@Composable
fun InfoScreen(
    materialYou: Boolean,
    onToggleMaterialYou: (Boolean) -> Unit,
    onVersionClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val progress = rememberCollapsingProgress(
        scrollState = scrollState,
        collapseRange = CollapsingTopBarDefaults.CollapseRangeCompact
    )

    val appVersion = DeviceHelper.getAppVersion(context)

    val isMaterialYou = LocalIsMaterialYou.current

    val thirdPartyProjects = buildList {
        if (org.francasio.sing.util.ModelLoader.isOnnxRuntimeAvailable) {
            add(
                ThirdPartyProject(
                    name = "ONNX Runtime & QNN Execution Provider",
                    authors = "Microsoft Corporation & Qualcomm Technologies",
                    description = "Neural network inference with Qualcomm Hexagon HTP NPU hardware acceleration and multithreaded CPU fallback."
                )
            )
        }
        add(
            ThirdPartyProject(
                name = "Alibaba MNN",
                authors = "Alibaba Group",
                description = "Lightweight deep learning engine for cross-platform GPU, NNAPI, and multi-core CPU execution."
            )
        )
        add(
            ThirdPartyProject(
                name = "SF Symbols",
                authors = "Apple Inc.",
                description = "Iconography and glyph styling inspired by Apple system design language."
            )
        )
        add(
            ThirdPartyProject(
                name = "MDX-Net & Ultimate Vocal Remover",
                authors = "KimberleyJSN & UVR Development Team",
                description = "Vocal and instrumental stem separation model weights."
            )
        )
        add(
            ThirdPartyProject(
                name = "KISS FFT",
                authors = "Mark Borgerding (mborgerding)",
                description = "Fast Fourier Transform (FFT) library that tries to Keep It Simple, Stupid"
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(statusBarTop + 140.dp))

            // 1. App Identity
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = if (isMaterialYou) {
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Neutral1_800)
                    } else {
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0xFF222123), Color(0xFF101011))
                                )
                            )
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sing),
                        contentDescription = null,
                        tint = if (isMaterialYou) Accent1_100 else Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(id = R.string.app_name),
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(id = R.string.info_app_subtitle),
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceContainerHigh)
                        .clickable(onClick = onVersionClick)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = appVersion,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Theming Section
            SectionHeader(
                title = stringResource(id = R.string.info_theming_title),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            GroupedCardItem(index = 0, total = 1) {
                SettingSwitchRow(
                    icon = painterResource(id = R.drawable.ic_palette),
                    title = stringResource(id = R.string.info_material_you_title),
                    description = stringResource(id = R.string.info_material_you_desc),
                    checked = materialYou,
                    onCheckedChange = onToggleMaterialYou
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Source Code / GitHub
            GroupedCardItem(index = 0, total = 1) {
                SettingActionRow(
                    icon = painterResource(id = R.drawable.ic_code),
                    title = stringResource(id = R.string.info_github_title),
                    description = stringResource(id = R.string.info_github_desc),
                    trailingIcon = rememberVectorPainter(Icons.AutoMirrored.Filled.ExitToApp),
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/22cms/sing-for-android"))
                            context.startActivity(intent)
                        } catch (_: Throwable) {}
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Third-Party Projects Card
            SectionHeader(
                title = stringResource(id = R.string.info_third_party_title),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    thirdPartyProjects.forEachIndexed { index, project ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = project.name,
                                color = TextPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = project.authors,
                                color = Primary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = project.description,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        if (index < thirdPartyProjects.lastIndex) {
                            HorizontalDivider(
                                color = DividerDark.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Top Bar
        ExpressiveCollapsingTopBar(
            progress = progress,
            title = stringResource(id = R.string.title_info),
            navigationIcon = NavigationIcon.Morphing(
                icon = rememberVectorPainter(Icons.Outlined.Info),
                onBack = onBack
            )
        )
    }
}
