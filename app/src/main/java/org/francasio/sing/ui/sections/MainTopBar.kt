package org.francasio.sing.ui.sections

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.francasio.sing.R
import org.francasio.sing.ui.components.ExpressiveCollapsingTopBar
import org.francasio.sing.ui.components.NavigationIcon
import org.francasio.sing.ui.components.TopBarActionButton

@Composable
fun BoxScope.MainTopBar(
    progress: Float,
    openedFromMusic: Boolean,
    apkVersion: String,
    onBackFromMusic: () -> Unit,
    onNavigateToInfo: () -> Unit
) {
    ExpressiveCollapsingTopBar(
        progress = progress,
        title = stringResource(id = R.string.app_name),
        subtitle = apkVersion,
        navigationIcon = if (openedFromMusic) {
            NavigationIcon.Morphing(
                icon = painterResource(id = R.drawable.ic_sing),
                onBack = onBackFromMusic
            )
        } else {
            NavigationIcon.Fading(painterResource(id = R.drawable.ic_sing))
        },
        trailingAction = { actionProgress ->
            TopBarActionButton(
                icon = Icons.Outlined.Info,
                contentDescription = stringResource(id = R.string.action_info),
                progress = actionProgress,
                onClick = onNavigateToInfo
            )
        }
    )
}
