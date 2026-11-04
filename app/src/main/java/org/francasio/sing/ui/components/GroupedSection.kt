package org.francasio.sing.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class GroupedSectionItem(
    val key: Any?,
    val content: @Composable () -> Unit
)

class GroupedSectionScope {
    val items = mutableListOf<GroupedSectionItem>()

    fun item(key: Any? = null, content: @Composable () -> Unit) {
        items.add(GroupedSectionItem(key, content))
    }
}

@Composable
fun GroupedSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    key: Any? = null,
    content: @Composable GroupedSectionScope.() -> Unit
) {
    key(key) {
        val scope = GroupedSectionScope()
        scope.content()
        val items = scope.items

        Column(modifier = modifier.fillMaxWidth()) {
            if (title != null) {
                SectionHeader(title = title)
            }

            items.forEachIndexed { index, itemEntry ->
                key(itemEntry.key ?: index) {
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    GroupedCardItem(index = index, total = items.size) {
                        itemEntry.content()
                    }
                }
            }
        }
    }
}
