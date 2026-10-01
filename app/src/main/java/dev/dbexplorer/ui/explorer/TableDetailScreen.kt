package dev.dbexplorer.ui.explorer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import dev.dbexplorer.domain.model.ColumnInfo
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.ForeignKey
import dev.dbexplorer.domain.model.ReferentialAction
import dev.dbexplorer.domain.model.TableDetails
import dev.dbexplorer.domain.model.TableRef
import dev.dbexplorer.domain.model.TableType
import dev.dbexplorer.ui.components.ConnectionBanner
import dev.dbexplorer.ui.components.EmptyState
import dev.dbexplorer.ui.components.ErrorCard
import dev.dbexplorer.ui.components.LoadingBox
import dev.dbexplorer.ui.components.SecureWindow
import dev.dbexplorer.ui.theme.CodeTextStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DetailTab(val title: String) { COLUMNS("Columns"), KEYS("Keys"), INDEXES("Indexes"), DDL("DDL") }

data class TableDetailUiState(
    val table: TableRef,
    val profile: ConnectionProfile? = null,
    val tab: DetailTab = DetailTab.COLUMNS,
    val loading: Boolean = true,
    val details: TableDetails? = null,
    val error: UiError? = null,
    val ddl: String? = null,
    val ddlLoading: Boolean = false,
    val ddlError: UiError? = null,
) {
    val tabs: List<DetailTab>
        get() = if (table.type == TableType.SEQUENCE) listOf(DetailTab.DDL) else DetailTab.entries
}

@HiltViewModel
class TableDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val sessions: SessionManager) : ViewModel() {
    val connectionId: Long = checkNotNull(savedStateHandle.get<Long>("connId"))
    private val table = TableRef(
        catalog = savedStateHandle.get<String>("catalog"),
        schema = savedStateHandle.get<String>("schema"),
        name = checkNotNull(savedStateHandle.get<String>("name")),
        type = savedStateHandle.get<String>("type")?.let { runCatching { TableType.valueOf(it) }.getOrNull() } ?: TableType.TABLE,
    )
    private val _state = MutableStateFlow(
        TableDetailUiState(table = table, tab = if (table.type == TableType.SEQUENCE) DetailTab.DDL else DetailTab.COLUMNS),
    )
    val state: StateFlow<TableDetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(ddl = null, ddlError = null) }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val session = sessions.open(connectionId)
                session.profile to if (table.type == TableType.SEQUENCE) null else session.describeTable(table)
            }.onSuccess { (profile, details) ->
                _state.update { it.copy(loading = false, profile = profile, details = details) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
        if (_state.value.tab == DetailTab.DDL) loadDdl()
    }

    fun selectTab(tab: DetailTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == DetailTab.DDL && _state.value.ddl == null) loadDdl()
    }

    private fun loadDdl() {
        viewModelScope.launch {
            _state.update { it.copy(ddlLoading = true, ddlError = null) }
            runCatching { sessions.open(connectionId).ddl(table) }
                .onSuccess { ddl -> _state.update { it.copy(ddlLoading = false, ddl = ddl) } }
                .onFailure { e -> _state.update { it.copy(ddlLoading = false, ddlError = e.toUiError()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableDetailScreen(onBack: () -> Unit, onOpenTable: (Long, TableRef) -> Unit, viewModel: TableDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SecureWindow(state.profile?.secureScreen == true)
    Scaffold(
        topBar = {
            ExplorerTopBar(
                title = state.table.name,
                subtitle = listOfNotNull(
                    state.profile?.name,
                    state.table.schema,
                    state.table.type.displayName.removeSuffix("s").lowercase(),
                )
                    .joinToString(" › "),
                onBack = onBack,
                actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(state.profile)
            if (state.tabs.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = state.tabs.indexOf(state.tab).coerceAtLeast(0), edgePadding = 16.dp) {
                    state.tabs.forEach { tab ->
                        Tab(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, text = { Text(tab.title) })
                    }
                }
            }
            when {
                state.tab == DetailTab.DDL -> DdlTab(state)
                state.loading -> LoadingBox()
                state.error != null -> ErrorCard(state.error!!.message, state.error!!.sqlState, modifier = Modifier.padding(16.dp))
                else -> state.details?.let { details ->
                    when (state.tab) {
                        DetailTab.COLUMNS -> ColumnsTab(details)
                        DetailTab.KEYS -> KeysTab(details) { onOpenTable(viewModel.connectionId, it) }
                        DetailTab.INDEXES -> IndexesTab(details)
                        DetailTab.DDL -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnsTab(details: TableDetails) {
    val pk = details.primaryKey?.columns.orEmpty().toSet()
    val fkCols = details.foreignKeys.flatMap { it.columns }.toSet()
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(details.columns, key = { it.name }) { col ->
            ListItem(
                headlineContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            col.name,
                            style = CodeTextStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (col.name in pk) Badge("PK")
                        if (col.name in fkCols) Badge("FK")
                    }
                },
                supportingContent = { Text(columnSummary(col), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Text("${col.position}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
            HorizontalDivider()
        }
    }
}

internal fun columnSummary(col: ColumnInfo): String = buildList {
    add(col.typeName + sizeSuffix(col))
    add(if (col.nullable) "null" else "not null")
    if (col.autoIncrement) add("auto")
    col.defaultValue?.let { add("default $it") }
    col.remarks?.let { add("— $it") }
}.joinToString(" · ")

private fun sizeSuffix(col: ColumnInfo): String {
    val type = col.typeName.lowercase()
    val sized = type.contains("char") || type == "numeric" || type == "decimal" || type.contains("binary")
    if (!sized || col.size == null || col.size!! <= 0 || col.size == Int.MAX_VALUE || type.contains('(')) return ""
    val digits = col.decimalDigits?.takeIf { it > 0 && (type == "numeric" || type == "decimal") }
    return "(${col.size}${digits?.let { ", $it" }.orEmpty()})"
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun KeysTab(details: TableDetails, onOpenTable: (TableRef) -> Unit) {
    if (details.primaryKey == null && details.foreignKeys.isEmpty()) {
        EmptyState("No keys", "This object has no primary key or foreign keys. Inline editing will need a primary key.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        details.primaryKey?.let { pk ->
            item(key = "pk-header") { SectionHeader("Primary key") }
            item(key = "pk") {
                ListItem(
                    headlineContent = { Text(pk.columns.joinToString(", "), style = CodeTextStyle) },
                    supportingContent = optionalText(pk.name),
                )
            }
        }
        if (details.foreignKeys.isNotEmpty()) {
            item(key = "fk-header") { SectionHeader("Foreign keys") }
            items(details.foreignKeys, key = { "fk:${it.name}:${it.columns}" }) { fk ->
                ListItem(
                    headlineContent = { Text(foreignKeySummary(fk), style = CodeTextStyle) },
                    supportingContent = optionalText(listOfNotNull(fk.name, ruleSummary(fk)).joinToString(" · ")),
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open ${fk.referencedTable.name}")
                    },
                    modifier = Modifier.clickableRow { onOpenTable(fk.referencedTable) },
                )
                HorizontalDivider()
            }
        }
    }
}

internal fun foreignKeySummary(fk: ForeignKey): String =
    "(${fk.columns.joinToString(", ")}) → ${fk.referencedTable.qualifiedName}(${fk.referencedColumns.joinToString(", ")})"

private fun ruleSummary(fk: ForeignKey): String? = listOfNotNull(
    fk.onUpdate.takeIf { it != ReferentialAction.NO_ACTION }?.let { "on update ${it.sql.lowercase()}" },
    fk.onDelete.takeIf { it != ReferentialAction.NO_ACTION }?.let { "on delete ${it.sql.lowercase()}" },
).joinToString(", ").ifEmpty { null }

@Composable
private fun IndexesTab(details: TableDetails) {
    if (details.indexes.isEmpty()) {
        EmptyState("No indexes", if (details.table.type == TableType.VIEW) "Views have no indexes." else "This table has no indexes.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(details.indexes, key = { it.name }) { index ->
            ListItem(
                headlineContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            index.name,
                            style = CodeTextStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (index.unique) Badge("UNIQUE")
                    }
                },
                supportingContent = { Text(index.columns.joinToString(", "), style = CodeTextStyle) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun DdlTab(state: TableDetailUiState) {
    when {
        state.ddlLoading -> LoadingBox()
        state.ddlError != null -> ErrorCard(state.ddlError.message, state.ddlError.sqlState, modifier = Modifier.padding(16.dp))
        state.ddl != null -> Box(Modifier.fillMaxSize()) {
            SelectionContainer {
                Text(
                    state.ddl,
                    style = CodeTextStyle,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp),
                )
            }
        }
    }
}
