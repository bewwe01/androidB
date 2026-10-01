package dev.dbexplorer.data.local

import dev.dbexplorer.data.secrets.SecretStore
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.Secrets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Connection profiles (Room) plus their passwords (Keystore-encrypted, stored separately). */
@Singleton
class ConnectionRepository @Inject constructor(private val dao: ConnectionDao, private val secrets: SecretStore) {
    fun observeProfiles(): Flow<List<ConnectionProfile>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: Long): ConnectionProfile? = dao.get(id)?.toDomain()

    /**
     * Inserts or updates [profile]. [newPassword] replaces the stored password when non-null;
     * the password is dropped when the profile no longer wants it saved.
     */
    suspend fun save(profile: ConnectionProfile, newPassword: String?): Long = withContext(Dispatchers.IO) {
        val id = if (profile.id == 0L) {
            dao.insert(ConnectionProfileEntity.fromDomain(profile))
        } else {
            val previous = dao.get(profile.id)
            dao.update(ConnectionProfileEntity.fromDomain(profile, previous?.lastUsedAt))
            profile.id
        }
        when {
            !profile.savePassword -> secrets.remove(passwordKey(id))
            newPassword != null -> secrets.put(passwordKey(id), newPassword)
        }
        id
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
        secrets.remove(passwordKey(id))
    }

    suspend fun markUsed(id: Long) = dao.markUsed(id, System.currentTimeMillis())

    suspend fun hasStoredPassword(id: Long): Boolean = withContext(Dispatchers.IO) { secrets.contains(passwordKey(id)) }

    /** Resolves secrets for a connection attempt; [override] wins over the stored password. */
    suspend fun secretsFor(profile: ConnectionProfile, override: String? = null): Secrets = withContext(Dispatchers.IO) {
        Secrets(override ?: if (profile.savePassword && profile.id != 0L) secrets.get(passwordKey(profile.id)) else null)
    }

    private fun passwordKey(id: Long) = "connection.$id.password"
}
