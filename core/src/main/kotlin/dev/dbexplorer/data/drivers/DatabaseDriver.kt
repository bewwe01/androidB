package dev.dbexplorer.data.drivers

import dev.dbexplorer.domain.model.Catalog
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.ResultChunk
import dev.dbexplorer.domain.model.RoutineRef
import dev.dbexplorer.domain.model.Schema
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.Secrets
import dev.dbexplorer.domain.model.TableDetails
import dev.dbexplorer.domain.model.TableRef
import dev.dbexplorer.domain.model.TableType
import kotlinx.coroutines.flow.Flow

interface DatabaseDriver {
    val kind: DbKind

    /** Opens a session. Throws [dev.dbexplorer.domain.model.DbException] on failure. */
    suspend fun connect(profile: ConnectionProfile, secrets: Secrets): DbSession
}

/**
 * One open connection. Implementations serialise access to the underlying connection, run all
 * blocking work off the main thread and are safe to call from any coroutine.
 */
interface DbSession : AutoCloseable {
    val profile: ConnectionProfile

    /** e.g. "PostgreSQL 16.4". */
    val serverVersion: String

    suspend fun listCatalogs(): List<Catalog>

    suspend fun listSchemas(catalog: String?): List<Schema>

    suspend fun listTables(schema: SchemaRef, types: Set<TableType> = TableType.BROWSABLE): List<TableRef>

    suspend fun listRoutines(schema: SchemaRef): List<RoutineRef>

    /** Columns, primary key, foreign keys and indexes. */
    suspend fun describeTable(t: TableRef): TableDetails

    /** Dialect-specific DDL for a table, view or sequence. */
    suspend fun ddl(t: TableRef): String

    /** Executes [sql] and streams results in chunks of [pageSize] rows, stopping after [maxRows]. */
    fun execute(sql: String, pageSize: Int = 200, maxRows: Long = 10_000): Flow<ResultChunk>

    /** Cancels the statement currently running in [execute], if any. */
    suspend fun cancel()

    val isAutoCommit: Boolean

    suspend fun beginTx()

    suspend fun commit()

    suspend fun rollback()

    /** Whether the connection is still usable (cheap server round trip). */
    suspend fun isValid(): Boolean

    /** Closes the session; idempotent. */
    override fun close()
}
