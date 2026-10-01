package dev.dbexplorer.data.session

import dev.dbexplorer.data.drivers.DbSession
import dev.dbexplorer.data.drivers.DriverRegistry
import dev.dbexplorer.data.local.ConnectionRepository
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.Secrets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OpenSession(val profile: ConnectionProfile, val serverVersion: String)

/** Owns the open [DbSession]s: one per connection profile, shared by all screens. */
@Singleton
class SessionManager @Inject constructor(private val drivers: DriverRegistry, private val repository: ConnectionRepository) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<Long, DbSession>()
    private val _openSessions = MutableStateFlow<Map<Long, OpenSession>>(emptyMap())
    val openSessions: StateFlow<Map<Long, OpenSession>> = _openSessions.asStateFlow()

    /** Returns the open session for [profileId], (re)connecting when needed. */
    suspend fun open(profileId: Long, passwordOverride: String? = null): DbSession = mutex.withLock {
        sessions[profileId]?.let { existing ->
            if (existing.isValid()) return@withLock existing
            existing.close()
            sessions.remove(profileId)
        }
        val profile = repository.get(profileId) ?: throw DbException("Connection no longer exists")
        val session = drivers.forKind(profile.kind).connect(profile, repository.secretsFor(profile, passwordOverride))
        sessions[profileId] = session
        repository.markUsed(profileId)
        _openSessions.update { it + (profileId to OpenSession(profile, session.serverVersion)) }
        session
    }

    /** Connects with an unsaved profile and disconnects again; returns the server version. */
    suspend fun test(profile: ConnectionProfile, secrets: Secrets): String =
        drivers.forKind(profile.kind).connect(profile, secrets).use { it.serverVersion }

    suspend fun close(profileId: Long) = mutex.withLock {
        sessions.remove(profileId)?.close()
        _openSessions.update { it - profileId }
    }

    suspend fun closeAll() = mutex.withLock {
        sessions.values.forEach { it.close() }
        sessions.clear()
        _openSessions.value = emptyMap()
    }
}
