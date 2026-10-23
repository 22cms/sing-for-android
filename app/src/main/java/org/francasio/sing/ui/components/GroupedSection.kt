package org.francasio.sing.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class GroupedSectionScope {
    val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items.add(content)
    }
}

@Composable
fun GroupedSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable GroupedSectionScope.() -> Unit
) {
    val scope = GroupedSectionScope()
    scope.content()
    val items = scope.items

    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            SectionHeader(title = title)
        }

        items.forEachIndexed { index, item ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(2.dp))
            }
            GroupedCardItem(index = index, total = items.size) {
                item()
            }
        }
    }
}
