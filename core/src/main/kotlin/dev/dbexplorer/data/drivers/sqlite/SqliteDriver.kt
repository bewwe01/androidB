package dev.dbexplorer.data.drivers.sqlite

import dev.dbexplorer.data.drivers.DatabaseDriver
import dev.dbexplorer.data.drivers.DbSession
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteDriver(private val opener: SqliteOpener) : DatabaseDriver {
    override val kind: DbKind = DbKind.SQLITE

    override suspend fun connect(profile: ConnectionProfile, secrets: Secrets): DbSession = withContext(Dispatchers.IO) {
        require(profile.kind == DbKind.SQLITE) { "Not a SQLite profile" }
        if (profile.filePath.isBlank()) throw DbException("Database file is required")
        val connection = try {
            opener.open(profile.filePath, profile.readOnly)
        } catch (e: DbException) {
            throw e
        } catch (e: Exception) {
            throw DbException(e.message ?: "Cannot open ${profile.filePath}", cause = e)
        }
        SqliteSession(profile, connection)
    }
}
