package dev.dbexplorer.ui.connections

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dbexplorer.data.drivers.DriverRegistry
import dev.dbexplorer.data.drivers.SqliteFiles
import dev.dbexplorer.data.local.ConnectionRepository
import dev.dbexplorer.data.session.SessionManager
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.Secrets
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState

    data object Running : TestState

    data class Success(val serverVersion: String) : TestState

    data class Failure(val message: String, val sqlState: String?) : TestState
}

data class ConnectionEditUiState(
    val loading: Boolean = true,
    val form: ConnectionForm = ConnectionForm(),
    val errors: Map<ConnectionForm.Field, String> = emptyMap(),
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
    val supportedKinds: List<DbKind> = emptyList(),
    val message: String? = null,
) {
    val isNew: Boolean get() = form.id == 0L
}

@HiltViewModel
class ConnectionEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ConnectionRepository,
    private val sessions: SessionManager,
    private val sqliteFiles: SqliteFiles,
    drivers: DriverRegistry,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectionEditUiState(supportedKinds = drivers.supportedKinds))
    val state: StateFlow<ConnectionEditUiState> = _state.asStateFlow()

    private val _saved = Channel<Long>(Channel.BUFFERED)

    /** Emits the profile id once saved. */
    val saved = _saved.receiveAsFlow()

    private var testJob: Job? = null

    init {
        val id = savedStateHandle.get<Long>("id") ?: -1L
        viewModelScope.launch {
            val profile = if (id > 0) repository.get(id) else null
            val form = profile?.let { ConnectionForm.from(it, repository.hasStoredPassword(it.id)) } ?: ConnectionForm()
            _state.update { it.copy(loading = false, form = form) }
        }
    }

    fun update(transform: (ConnectionForm) -> ConnectionForm) {
        _state.update { s ->
            val form = transform(s.form)
            s.copy(form = form, errors = if (s.errors.isEmpty()) s.errors else form.validate(), test = TestState.Idle)
        }
    }

    fun setKind(kind: DbKind) = update { f ->
        val oldDefault = f.kind.defaultPort?.toString()
        f.copy(kind = kind, port = if (f.port.isBlank() || f.port == oldDefault) kind.defaultPort?.toString().orEmpty() else f.port)
    }

    fun importSqliteFile(uri: Uri) {
        viewModelScope.launch {
            runCatching { sqliteFiles.import(uri) }
                .onSuccess { path ->
                    update { f -> f.copy(filePath = path, name = f.name.ifBlank { path.substringAfterLast('/') }) }
                }
                .onFailure { e -> _state.update { it.copy(message = "Import failed: ${e.message}") } }
        }
    }

    fun createSqliteFile(name: String) {
        val path = sqliteFiles.newDatabasePath(name)
        update { f -> f.copy(filePath = path, name = f.name.ifBlank { name }) }
    }

    fun testConnection() {
        val form = _state.value.form
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _state.update { it.copy(test = TestState.Running) }
            val profile = form.toProfile()
            val result = runCatching {
                val secrets = if (form.passwordEdited ||
                    !form.hasStoredPassword
                ) {
                    Secrets(form.password.ifEmpty { null })
                } else {
                    repository.secretsFor(profile)
                }
                sessions.test(profile, secrets)
            }
            _state.update {
                it.copy(
                    test = result.fold(
                        onSuccess = { v -> TestState.Success(v) },
                        onFailure = { e -> TestState.Failure(e.message ?: e.javaClass.simpleName, (e as? DbException)?.sqlState) },
                    ),
                )
            }
        }
    }

    fun save() {
        val form = _state.value.form
        val errors = form.validate()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching { repository.save(form.toProfile(), form.passwordToStore()) }
                .onSuccess { id ->
                    // Settings such as read-only take effect on the next connect.
                    if (form.id != 0L) sessions.close(form.id)
                    _saved.send(id)
                }
                .onFailure { e -> _state.update { it.copy(saving = false, message = "Save failed: ${e.message}") } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
