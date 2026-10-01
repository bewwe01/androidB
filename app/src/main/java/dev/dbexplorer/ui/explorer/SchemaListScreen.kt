package dev.dbexplorer.ui.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dbexplorer.data.session.SessionManager
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.Schema
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.ui.components.ConnectionBanner
import dev.dbexplorer.ui.components.EmptyState
import dev.dbexplorer.ui.components.ErrorCard
import dev.dbexplorer.ui.components.LoadingBox
import dev.dbexplorer.ui.components.SearchField
import dev.dbexplorer.ui.components.SecureWindow
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SchemaListUiState(
    val profile: ConnectionProfile? = null,
    val serverVersion: String? = null,
    val loading: Boolean = true,
    val schemas: List<Schema> = emptyList(),
    val showSystem: Boolean = false,
    val query: String = "",
    val error: UiError? = null,
) {
    val visible: List<Schema>
        get() = schemas
            .filter { showSystem || !isSystemSchema(profile?.kind, it.name) }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    val hasSystemSchemas: Boolean get() = schemas.any { isSystemSchema(profile?.kind, it.name) }

    companion object {
        fun isSystemSchema(kind: DbKind?, name: String): Boolean = when (kind) {
            DbKind.POSTGRES -> name.startsWith("pg_") || name == "information_schema"
            DbKind.MARIADB -> name in setOf("information_schema", "performance_schema", "mysql", "sys")
            else -> false
        }
    }
}

@HiltViewModel
class SchemaListViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val sessions: SessionManager) : ViewModel() {
    val connectionId: Long = checkNotNull(savedStateHandle.get<Long>("connId"))
    private val _state = MutableStateFlow(SchemaListUiState())
    val state: StateFlow<SchemaListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val session = sessions.open(connectionId)
                Triple(session.profile, session.serverVersion, session.listSchemas(null))
            }.onSuccess { (profile, version, schemas) ->
                _state.update { it.copy(loading = false, profile = profile, serverVersion = version, schemas = schemas) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleSystem() = _state.update { it.copy(showSystem = !it.showSystem) }

    fun disconnect(then: () -> Unit) {
        viewModelScope.launch {
            sessions.close(connectionId)
            then()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaListScreen(onBack: () -> Unit, onOpenSchema: (Long, SchemaRef) -> Unit, viewModel: SchemaListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow(state.profile?.secureScreen == true)
    Scaffold(
        topBar = {
            ExplorerTopBar(
                title = state.profile?.name ?: "Schemas",
                subtitle = state.serverVersion,
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::load) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    TextButton(onClick = { viewModel.disconnect(onBack) }) { Text("Disconnect") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(state.profile)
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorCard(state.error!!.message, state.error!!.sqlState, modifier = Modifier.padding(16.dp))
                state.schemas.isEmpty() -> EmptyState("No schemas", "This database has no schemas visible to your user.")
                else -> {
                    SearchField(
                        state.query,
                        viewModel::setQuery,
                        placeholder = "Filter schemas",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (state.hasSystemSchemas) {
                        FilterChip(
                            selected = state.showSystem,
                            onClick = viewModel::toggleSystem,
                            label = { Text("Show system schemas") },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(state.visible, key = { "${it.catalog}.${it.name}" }) { schema ->
                            ListItem(
                                headlineContent = { Text(schema.name) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                                modifier = Modifier.clickableRow { onOpenSchema(viewModel.connectionId, schema.ref) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
