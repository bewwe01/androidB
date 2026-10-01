package dev.dbexplorer.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dbexplorer.data.local.ConnectionRepository
import dev.dbexplorer.data.session.SessionManager
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionItem(val profile: ConnectionProfile, val isOpen: Boolean)

data class ConnectionListUiState(
    val loading: Boolean = true,
    /** Grouped by folder; "" is the ungrouped section. */
    val groups: Map<String, List<ConnectionItem>> = emptyMap(),
    val connectingId: Long? = null,
    val passwordPrompt: ConnectionProfile? = null,
    val error: ConnectError? = null,
)

data class ConnectError(val connectionName: String, val message: String, val sqlState: String?)

@HiltViewModel
class ConnectionListViewModel @Inject constructor(private val repository: ConnectionRepository, private val sessions: SessionManager) :
    ViewModel() {
    private val transient = MutableStateFlow(ConnectionListUiState())

    val state: StateFlow<ConnectionListUiState> = combine(
        repository.observeProfiles(),
        sessions.openSessions,
        transient,
    ) { profiles, open, t ->
        t.copy(
            loading = false,
            groups = profiles.map { ConnectionItem(it, it.id in open) }.groupBy { it.profile.folder }.toSortedMap(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionListUiState())

    private val _connected = Channel<Long>(Channel.BUFFERED)

    /** Emits the profile id after a successful connect, so the UI can open the explorer. */
    val connected = _connected.receiveAsFlow()

    fun connect(profile: ConnectionProfile) {
        viewModelScope.launch {
            val needsPrompt = profile.kind.usesNetwork && (!profile.savePassword || !repository.hasStoredPassword(profile.id))
            if (needsPrompt && profile.id !in sessions.openSessions.value) {
                transient.update { it.copy(passwordPrompt = profile) }
            } else {
                doConnect(profile, null)
            }
        }
    }

    fun connectWithPassword(password: String) {
        val profile = transient.value.passwordPrompt ?: return
        transient.update { it.copy(passwordPrompt = null) }
        viewModelScope.launch { doConnect(profile, password) }
    }

    fun dismissPasswordPrompt() = transient.update { it.copy(passwordPrompt = null) }

    private suspend fun doConnect(profile: ConnectionProfile, password: String?) {
        transient.update { it.copy(connectingId = profile.id, error = null) }
        runCatching { sessions.open(profile.id, password) }
            .onSuccess {
                transient.update { it.copy(connectingId = null) }
                _connected.send(profile.id)
            }
            .onFailure { e ->
                transient.update {
                    it.copy(
                        connectingId = null,
                        error = ConnectError(profile.name, e.message ?: e.javaClass.simpleName, (e as? DbException)?.sqlState),
                    )
                }
            }
    }

    fun disconnect(id: Long) {
        viewModelScope.launch { sessions.close(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            sessions.close(id)
            repository.delete(id)
        }
    }

    fun dismissError() = transient.update { it.copy(error = null) }
}
