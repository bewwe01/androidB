package dev.dbexplorer.ui.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import dev.dbexplorer.domain.model.DbException

/** Slot content for an optional single-line text, e.g. a ListItem's supportingContent. */
fun optionalText(text: String?): (@Composable () -> Unit)? {
    if (text.isNullOrBlank()) return null
    return { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Error shown in explorer screens. */
data class UiError(val message: String, val sqlState: String?)

fun Throwable.toUiError(): UiError = UiError(message ?: javaClass.simpleName, (this as? DbException)?.sqlState)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
    )
}
