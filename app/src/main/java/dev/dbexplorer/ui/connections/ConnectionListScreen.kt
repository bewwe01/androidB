package dev.dbexplorer.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.ui.components.ColorDot
import dev.dbexplorer.ui.components.EmptyState
import dev.dbexplorer.ui.components.ErrorCard
import dev.dbexplorer.ui.components.LoadingBox
import dev.dbexplorer.ui.theme.ProductionRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onConnected: (Long) -> Unit,
    viewModel: ConnectionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.connected.collect(onConnected) }
    var pendingDelete by remember { mutableStateOf<ConnectionProfile?>(null) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Connections") }, scrollBehavior = scrollBehavior) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New connection") })
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.groups.isEmpty() -> EmptyState(
                title = "No connections yet",
                body = "Add a PostgreSQL server or a SQLite file to start exploring.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    item(key = "error") {
                        ErrorCard(
                            message = "Could not connect to ${error.connectionName}:\n${error.message}",
                            sqlState = error.sqlState,
                            onDismiss = viewModel::dismissError,
                        )
                    }
                }
                state.groups.forEach { (folder, items) ->
                    if (folder.isNotEmpty()) {
                        item(key = "folder:$folder") {
                            Text(
                                folder,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            )
                        }
                    }
                    items(items, key = { it.profile.id }) { item ->
                        ConnectionRow(
                            item = item,
                            connecting = state.connectingId == item.profile.id,
                            onClick = { viewModel.connect(item.profile) },
                            onEdit = { onEdit(item.profile.id) },
                            onDisconnect = { viewModel.disconnect(item.profile.id) },
                            onDelete = { pendingDelete = item.profile },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete “${profile.name}”?") },
            text = { Text("The saved connection and its stored password are removed. The database itself is not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(profile.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    state.passwordPrompt?.let { profile ->
        PasswordDialog(profile = profile, onConfirm = viewModel::connectWithPassword, onDismiss = viewModel::dismissPasswordPrompt)
    }
}

@Composable
private fun ConnectionRow(
    item: ConnectionItem,
    connecting: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val profile = item.profile
    var menuOpen by remember { mutableStateOf(false) }
    val overline: (@Composable () -> Unit)? = if (profile.production) {
        { Text("PRODUCTION", color = ProductionRed) }
    } else {
        null
    }
    ElevatedCard(onClick = onClick, enabled = !connecting) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            leadingContent = { ColorDot(profile) },
            headlineContent = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    "${profile.kind.displayName} · ${profile.target}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            overlineContent = overline,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        item.isOpen -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Connected",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                menuOpen = false
                                onEdit()
                            })
                            if (item.isOpen) {
                                DropdownMenuItem(text = { Text("Disconnect") }, onClick = {
                                    menuOpen = false
                                    onDisconnect()
                                })
                            }
                            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                menuOpen = false
                                onDelete()
                            })
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PasswordDialog(profile: ConnectionProfile, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password for ${profile.username.ifEmpty { profile.name }}") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
