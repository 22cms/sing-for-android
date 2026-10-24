package org.francasio.sing.ui.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.francasio.sing.R
import org.francasio.sing.ui.components.GroupedSection
import org.francasio.sing.ui.components.SettingSwitchRow

@Composable
fun LyricsSettingsCard(
    blurUnfocusedLyrics: Boolean,
    centerGroupLyrics: Boolean,
    englishTranslationFallback: Boolean,
    onBlurUnfocusedLyricsChange: (Boolean) -> Unit,
    onCenterGroupLyricsChange: (Boolean) -> Unit,
    onEnglishTranslationFallbackChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    GroupedSection(
        title = stringResource(id = R.string.title_lyrics),
        modifier = modifier
    ) {
        item {
            SettingSwitchRow(
                icon = painterResource(id = R.drawable.ic_lyrics_blur),
                title = stringResource(id = R.string.setting_blur_unfocused_lyrics_title),
                description = stringResource(id = R.string.desc_blur_unfocused_lyrics),
                checked = blurUnfocusedLyrics,
                onCheckedChange = onBlurUnfocusedLyricsChange
            )
        }
        item {
            SettingSwitchRow(
                icon = painterResource(id = R.drawable.ic_text_align_center),
                title = stringResource(id = R.string.setting_center_group_lyrics_title),
                description = stringResource(id = R.string.desc_center_group_lyrics),
                checked = centerGroupLyrics,
                onCheckedChange = onCenterGroupLyricsChange
            )
        }
        item {
            SettingSwitchRow(
                icon = painterResource(id = R.drawable.ic_translate_missing),
                title = stringResource(id = R.string.setting_english_translation_fallback_title),
                description = stringResource(id = R.string.desc_english_translation_fallback),
                checked = englishTranslationFallback,
                onCheckedChange = onEnglishTranslationFallbackChange
            )
        }
    }
}
