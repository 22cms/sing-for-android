package org.francasio.sing

import androidx.compose.ui.unit.dp
import org.francasio.sing.ui.components.CollapsingTopBarDefaults
import org.francasio.sing.ui.components.CollapsingTopBarLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsingTopBarLayoutTest {
    @Test
    fun contentGapIsIndependentOfTextHeight() {
        for (titleHeight in listOf(44.dp, 88.dp)) {
            for (subtitleHeight in listOf(0.dp, 20.dp, 40.dp)) {
                val layout = CollapsingTopBarLayout(320.dp, titleHeight, subtitleHeight)
                val textBottom = if (subtitleHeight > 0.dp) {
                    layout.subtitleTop + subtitleHeight
                } else {
                    CollapsingTopBarDefaults.ExpandedTitleTop + titleHeight
                }
                assertEquals(24.dp, layout.contentTop - textBottom)
                assertEquals(
                    CollapsingTopBarDefaults.PinnedTopBarHeight + 24.dp,
                    layout.contentTop - layout.collapseRange
                )
            }
        }
    }
}
