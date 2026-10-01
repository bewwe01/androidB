package dev.dbexplorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dbexplorer.data.session.SessionManager
import dev.dbexplorer.ui.components.ColorDot
import dev.dbexplorer.ui.components.EmptyState
import javax.inject.Inject

@HiltViewModel
class ExplorerHomeViewModel @Inject constructor(sessions: SessionManager) : ViewModel() {
    val openSessions = sessions.openSessions
}

/** Explorer tab root: the currently open connections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerHomeScreen(onOpen: (Long) -> Unit, onGoToConnections: () -> Unit, viewModel: ExplorerHomeViewModel = hiltViewModel()) {
    val open by viewModel.openSessions.collectAsStateWithLifecycle()
    Scaffold(topBar = { ExplorerTopBar(title = "Explorer", subtitle = "Open connections", onBack = null) }) { padding ->
        if (open.isEmpty()) {
            EmptyState(
                title = "Nothing open",
                body = "Connect to a database from the Connections tab to browse its schemas and tables.",
                modifier = Modifier.padding(padding),
                action = { OutlinedButton(onClick = onGoToConnections) { Text("Go to connections") } },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(open.values.toList(), key = { it.profile.id }) { session ->
                    ElevatedCard(onClick = { onOpen(session.profile.id) }) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            leadingContent = { ColorDot(session.profile) },
                            headlineContent = { Text(session.profile.name) },
                            supportingContent = { Text(session.serverVersion, maxLines = 1) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        )
                    }
                }
            }
        }
    }
}
