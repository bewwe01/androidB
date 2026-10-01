package dev.dbexplorer.ui.explorer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dbexplorer.data.session.SessionManager
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.RoutineRef
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.TableRef
import dev.dbexplorer.domain.model.TableType
import dev.dbexplorer.ui.components.ConnectionBanner
import dev.dbexplorer.ui.components.EmptyState
import dev.dbexplorer.ui.components.ErrorCard
import dev.dbexplorer.ui.components.LoadingBox
import dev.dbexplorer.ui.components.SearchField
import dev.dbexplorer.ui.components.SecureWindow
import dev.dbexplorer.ui.theme.CodeTextStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Filter for the object list: a table type, or routines. */
sealed interface ObjectFilter {
    data class Tables(val type: TableType) : ObjectFilter

    data object Routines : ObjectFilter
}

data class ObjectListUiState(
    val profile: ConnectionProfile? = null,
    val schema: SchemaRef,
    val loading: Boolean = true,
    val tables: List<TableRef> = emptyList(),
    val routines: List<RoutineRef> = emptyList(),
    val filter: ObjectFilter? = null,
    val query: String = "",
    val error: UiError? = null,
) {
    /** Available filters with counts, in display order; empty categories are omitted. */
    val filters: List<Pair<ObjectFilter, Int>>
        get() = TableType.entries.mapNotNull { type ->
            tables.count { it.type == type }.takeIf { it > 0 }?.let { ObjectFilter.Tables(type) to it }
        } + listOfNotNull(routines.size.takeIf { it > 0 }?.let { ObjectFilter.Routines to it })

    private fun matches(name: String) = query.isBlank() || name.contains(query.trim(), ignoreCase = true)

    val visibleTables: List<TableRef>
        get() = when (val f = filter) {
            null -> tables
            is ObjectFilter.Tables -> tables.filter { it.type == f.type }
            ObjectFilter.Routines -> emptyList()
        }.filter { matches(it.name) }

    val visibleRoutines: List<RoutineRef>
        get() = if (filter == null || filter == ObjectFilter.Routines) routines.filter { matches(it.name) } else emptyList()
}

@HiltViewModel
class ObjectListViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val sessions: SessionManager) : ViewModel() {
    val connectionId: Long = checkNotNull(savedStateHandle.get<Long>("connId"))
    private val schemaRef = SchemaRef(savedStateHandle.get<String>("catalog"), savedStateHandle.get<String>("schema"))
    private val _state = MutableStateFlow(ObjectListUiState(schema = schemaRef))
    val state: StateFlow<ObjectListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val session = sessions.open(connectionId)
                Triple(
                    session.profile,
                    session.listTables(schemaRef, TableType.BROWSABLE + TableType.SYSTEM_TABLE),
                    session.listRoutines(schemaRef),
                )
            }.onSuccess { (profile, tables, routines) ->
                _state.update { it.copy(loading = false, profile = profile, tables = tables, routines = routines) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    fun setFilter(filter: ObjectFilter?) = _state.update { it.copy(filter = if (it.filter == filter) null else filter) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectListScreen(onBack: () -> Unit, onOpenTable: (Long, TableRef) -> Unit, viewModel: ObjectListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow(state.profile?.secureScreen == true)
    Scaffold(
        topBar = {
            ExplorerTopBar(
                title = state.schema.displayName,
                subtitle = state.profile?.name,
                onBack = onBack,
                actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(state.profile)
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorCard(state.error!!.message, state.error!!.sqlState, modifier = Modifier.padding(16.dp))
                state.tables.isEmpty() && state.routines.isEmpty() -> EmptyState(
                    "Empty schema",
                    "No tables, views, sequences or functions here.",
                )
                else -> {
                    SearchField(state.query, viewModel::setQuery, "Filter objects", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.filters.forEach { (filter, count) ->
                            val label = when (filter) {
                                is ObjectFilter.Tables -> filter.type.displayName
                                ObjectFilter.Routines -> "Functions"
                            }
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = { Text("$label  $count") },
                            )
                        }
                    }
                    ObjectList(state, onOpenTable = { onOpenTable(viewModel.connectionId, it) })
                }
            }
        }
    }
}

@Composable
private fun ObjectList(state: ObjectListUiState, onOpenTable: (TableRef) -> Unit) {
    val grouped = state.visibleTables.groupBy { it.type }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        TableType.entries.forEach { type ->
            val group = grouped[type] ?: return@forEach
            item(key = "header:$type") { SectionHeader(type.displayName) }
            items(group, key = { "t:${it.type}:${it.name}" }) { table ->
                ListItem(
                    headlineContent = { Text(table.name, style = CodeTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = optionalText(table.remarks),
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickableRow { onOpenTable(table) },
                )
                HorizontalDivider()
            }
        }
        if (state.visibleRoutines.isNotEmpty()) {
            item(key = "header:routines") { SectionHeader("Functions") }
            items(state.visibleRoutines, key = { "r:${it.specificName}" }) { routine ->
                ListItem(
                    headlineContent = { Text(routine.name, style = CodeTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = optionalText(routine.remarks),
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
