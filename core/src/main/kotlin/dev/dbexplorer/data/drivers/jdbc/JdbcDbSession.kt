package dev.dbexplorer.data.drivers.jdbc

import dev.dbexplorer.data.drivers.DbSession
import dev.dbexplorer.data.drivers.GenericDdl
import dev.dbexplorer.data.drivers.SessionExecutor
import dev.dbexplorer.data.drivers.SqlText
import dev.dbexplorer.domain.model.Catalog
import dev.dbexplorer.domain.model.ColumnInfo
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.ForeignKey
import dev.dbexplorer.domain.model.IndexInfo
import dev.dbexplorer.domain.model.PrimaryKey
import dev.dbexplorer.domain.model.ReferentialAction
import dev.dbexplorer.domain.model.ResultChunk
import dev.dbexplorer.domain.model.ResultColumn
import dev.dbexplorer.domain.model.RoutineKind
import dev.dbexplorer.domain.model.RoutineRef
import dev.dbexplorer.domain.model.Schema
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.TableDetails
import dev.dbexplorer.domain.model.TableRef
import dev.dbexplorer.domain.model.TableType
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.sql.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Generic [DbSession] built on `java.sql.DatabaseMetaData`. Dialect drivers subclass it and
 * override only what the generic path cannot do (DDL, error details, cursor semantics).
 */
open class JdbcDbSession(final override val profile: ConnectionProfile, protected val connection: Connection) : DbSession {
    private val executor = SessionExecutor("dbx-session-${profile.id}")

    @Volatile
    private var running: Statement? = null

    @Volatile
    final override var isAutoCommit: Boolean = connection.autoCommit
        private set

    override val serverVersion: String = connection.metaData.let { "${it.databaseProductName} ${it.databaseProductVersion}" }

    /** Seconds before a statement is aborted by the driver; 0 disables the limit. */
    var queryTimeoutSeconds: Int = 300

    /**
     * Whether the driver only honours `fetchSize` (i.e. streams rows instead of buffering the whole
     * result) inside a transaction. True for PostgreSQL.
     */
    protected open val needsTransactionForCursor: Boolean = false

    protected suspend fun <T> withConnection(block: (Connection) -> T): T = executor.run {
        try {
            block(connection)
        } catch (e: SQLException) {
            throw toDbException(e)
        }
    }

    protected open fun toDbException(e: SQLException): DbException = DbException(e.message ?: e.javaClass.simpleName, e.sqlState, cause = e)

    private val metaData: DatabaseMetaData get() = connection.metaData

    private fun pattern(value: String?): String? = escapePattern(value, metaData.searchStringEscape)

    override suspend fun listCatalogs(): List<Catalog> = withConnection {
        metaData.catalogs.mapRows { Catalog(it.getString("TABLE_CAT")) }
    }

    override suspend fun listSchemas(catalog: String?): List<Schema> = withConnection {
        metaData.getSchemas(catalog, null).mapRows { rs ->
            Schema(catalog = rs.stringOrNull("TABLE_CATALOG"), name = rs.getString("TABLE_SCHEM"))
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun listTables(schema: SchemaRef, types: Set<TableType>): List<TableRef> = withConnection {
        val jdbcTypes = types.flatMap { it.jdbcNames }.toTypedArray()
        metaData.getTables(schema.catalog, pattern(schema.schema), "%", jdbcTypes).mapRows { rs ->
            val type = TableType.fromJdbc(rs.getString("TABLE_TYPE"))
            type?.let {
                TableRef(
                    catalog = rs.stringOrNull("TABLE_CAT"),
                    schema = rs.stringOrNull("TABLE_SCHEM"),
                    name = rs.getString("TABLE_NAME"),
                    type = it,
                    remarks = rs.stringOrNull("REMARKS")?.takeIf { r -> r.isNotBlank() },
                )
            }
        }.filterNotNull().filter { it.type in types }.sortedBy { it.name.lowercase() }
    }

    override suspend fun listRoutines(schema: SchemaRef): List<RoutineRef> = withConnection {
        try {
            metaData.getFunctions(schema.catalog, pattern(schema.schema), "%").mapRows { rs ->
                RoutineRef(
                    catalog = rs.stringOrNull("FUNCTION_CAT"),
                    schema = rs.stringOrNull("FUNCTION_SCHEM"),
                    name = rs.getString("FUNCTION_NAME"),
                    specificName = rs.stringOrNull("SPECIFIC_NAME") ?: rs.getString("FUNCTION_NAME"),
                    kind = RoutineKind.FUNCTION,
                    remarks = rs.stringOrNull("REMARKS")?.takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.name.lowercase() }
        } catch (_: SQLFeatureNotSupportedException) {
            emptyList()
        }
    }

    override suspend fun describeTable(t: TableRef): TableDetails = withConnection {
        TableDetails(
            table = t,
            columns = readColumns(t),
            primaryKey = readPrimaryKey(t),
            foreignKeys = readForeignKeys(t),
            indexes = if (t.type == TableType.TABLE || t.type == TableType.MATERIALIZED_VIEW) readIndexes(t) else emptyList(),
        )
    }

    protected fun readColumns(t: TableRef): List<ColumnInfo> = metaData.getColumns(t.catalog, pattern(t.schema), pattern(t.name), "%")
        .mapRows { rs ->
            if (rs.getString("TABLE_NAME") != t.name || (t.schema != null && rs.getString("TABLE_SCHEM") != t.schema)) {
                null
            } else {
                ColumnInfo(
                    name = rs.getString("COLUMN_NAME"),
                    position = rs.getInt("ORDINAL_POSITION"),
                    typeName = rs.getString("TYPE_NAME"),
                    jdbcType = rs.intOrNull("DATA_TYPE") ?: Types.OTHER,
                    size = rs.intOrNull("COLUMN_SIZE"),
                    decimalDigits = rs.intOrNull("DECIMAL_DIGITS"),
                    nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    defaultValue = rs.stringOrNull("COLUMN_DEF"),
                    autoIncrement = rs.stringOrNull("IS_AUTOINCREMENT") == "YES",
                    remarks = rs.stringOrNull("REMARKS")?.takeIf { it.isNotBlank() },
                )
            }
        }.filterNotNull().sortedBy { it.position }

    protected fun readPrimaryKey(t: TableRef): PrimaryKey? {
        val rows = metaData.getPrimaryKeys(t.catalog, t.schema, t.name).mapRows { rs ->
            Triple(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"), rs.stringOrNull("PK_NAME"))
        }
        if (rows.isEmpty()) return null
        return PrimaryKey(rows.first().third, rows.sortedBy { it.first }.map { it.second })
    }

    protected fun readForeignKeys(t: TableRef): List<ForeignKey> {
        data class Row(
            val name: String?,
            val seq: Int,
            val column: String,
            val ref: TableRef,
            val refColumn: String,
            val onUpdate: Int,
            val onDelete: Int,
        )
        val rows = metaData.getImportedKeys(t.catalog, t.schema, t.name).mapRows { rs ->
            Row(
                name = rs.stringOrNull("FK_NAME"),
                seq = rs.getInt("KEY_SEQ"),
                column = rs.getString("FKCOLUMN_NAME"),
                ref = TableRef(rs.stringOrNull("PKTABLE_CAT"), rs.stringOrNull("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME")),
                refColumn = rs.getString("PKCOLUMN_NAME"),
                onUpdate = rs.getInt("UPDATE_RULE"),
                onDelete = rs.getInt("DELETE_RULE"),
            )
        }
        return rows.groupBy { it.name to it.ref }.map { (key, parts) ->
            val ordered = parts.sortedBy { it.seq }
            ForeignKey(
                name = key.first,
                columns = ordered.map { it.column },
                referencedTable = key.second,
                referencedColumns = ordered.map { it.refColumn },
                onUpdate = ReferentialAction.fromJdbc(ordered.first().onUpdate),
                onDelete = ReferentialAction.fromJdbc(ordered.first().onDelete),
            )
        }.sortedBy { it.name.orEmpty() }
    }

    protected fun readIndexes(t: TableRef): List<IndexInfo> {
        data class Row(val name: String, val unique: Boolean, val position: Int, val column: String)
        val rows = metaData.getIndexInfo(t.catalog, t.schema, t.name, false, true).mapRows { rs ->
            val name = rs.stringOrNull("INDEX_NAME")
            if (name == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                null
            } else {
                Row(name, !rs.getBoolean("NON_UNIQUE"), rs.getInt("ORDINAL_POSITION"), rs.stringOrNull("COLUMN_NAME") ?: "<expression>")
            }
        }.filterNotNull()
        return rows.groupBy { it.name }.map { (name, parts) ->
            IndexInfo(name, parts.first().unique, parts.sortedBy { it.position }.map { it.column })
        }.sortedBy { it.name }
    }

    override suspend fun ddl(t: TableRef): String = GenericDdl.createTable(describeTable(t))

    override fun execute(sql: String, pageSize: Int, maxRows: Long): Flow<ResultChunk> = flow {
        val started = System.nanoTime()
        val cursorTx = needsTransactionForCursor && connection.autoCommit && SqlText.returnsRows(sql)
        if (cursorTx) connection.autoCommit = false
        var succeeded = false
        var total = 0L
        var truncated = false
        val stmt = connection.createStatement()
        running = stmt
        try {
            stmt.fetchSize = pageSize
            if (maxRows < Int.MAX_VALUE) stmt.maxRows = (maxRows + 1).toInt()
            stmt.queryTimeout = queryTimeoutSeconds
            var hasResultSet = stmt.execute(sql)
            while (true) {
                if (hasResultSet) {
                    stmt.resultSet.use { rs ->
                        val (count, cut) = emitResultSet(rs, pageSize, maxRows) { emit(it) }
                        total += count
                        truncated = truncated || cut
                    }
                } else {
                    val updateCount = largeUpdateCount(stmt)
                    if (updateCount == -1L) break
                    emit(ResultChunk.UpdateCount(updateCount))
                }
                hasResultSet = stmt.moreResults
            }
            succeeded = true
            emit(ResultChunk.Completed((System.nanoTime() - started) / 1_000_000, total, truncated))
        } catch (e: SQLException) {
            throw toDbException(e)
        } finally {
            running = null
            runCatching { stmt.close() }
            if (cursorTx) {
                runCatching { if (succeeded) connection.commit() else connection.rollback() }
                runCatching { connection.autoCommit = true }
            }
        }
    }.flowOn(executor.dispatcher)

    private inline fun emitResultSet(rs: ResultSet, pageSize: Int, maxRows: Long, emitChunk: (ResultChunk) -> Unit): Pair<Long, Boolean> {
        val md = rs.metaData
        val columnCount = md.columnCount
        val types = IntArray(columnCount) { md.getColumnType(it + 1) }
        emitChunk(
            ResultChunk.Header(
                List(columnCount) { i -> ResultColumn(md.getColumnLabel(i + 1), md.getColumnTypeName(i + 1).orEmpty(), types[i]) },
            ),
        )
        var count = 0L
        var page = ArrayList<List<Any?>>(pageSize)
        while (count < maxRows && rs.next()) {
            page += List(columnCount) { i -> readValue(rs, i + 1, types[i]) }
            count++
            if (page.size >= pageSize) {
                emitChunk(ResultChunk.Rows(page))
                page = ArrayList(pageSize)
            }
        }
        if (page.isNotEmpty()) emitChunk(ResultChunk.Rows(page))
        val truncated = count >= maxRows && rs.next()
        return count to truncated
    }

    private fun largeUpdateCount(stmt: Statement): Long = try {
        stmt.largeUpdateCount
    } catch (_: SQLFeatureNotSupportedException) {
        stmt.updateCount.toLong()
    } catch (_: UnsupportedOperationException) {
        stmt.updateCount.toLong()
    }

    /** Normalises a cell to a type the UI knows how to render. */
    protected open fun readValue(rs: ResultSet, index: Int, jdbcType: Int): Any? {
        val value: Any? = when (jdbcType) {
            Types.BOOLEAN -> rs.getBoolean(index)
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> rs.getLong(index)
            Types.REAL, Types.FLOAT, Types.DOUBLE -> rs.getDouble(index)
            Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(index)
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> rs.getBytes(index)
            else -> rs.getString(index)
        }
        return if (rs.wasNull()) null else value
    }

    override suspend fun cancel() {
        val stmt = running ?: return
        withContext(Dispatchers.IO) { runCatching { stmt.cancel() } }
    }

    override suspend fun beginTx() = withConnection {
        it.autoCommit = false
        isAutoCommit = false
    }

    override suspend fun commit() = withConnection {
        if (!it.autoCommit) it.commit()
        it.autoCommit = true
        isAutoCommit = true
    }

    override suspend fun rollback() = withConnection {
        if (!it.autoCommit) it.rollback()
        it.autoCommit = true
        isAutoCommit = true
    }

    override suspend fun isValid(): Boolean = !executor.isShutdown && runCatching { withConnection { it.isValid(5) } }.getOrDefault(false)

    override fun close() {
        // Cancelling talks to the server, so never do it on the caller's (possibly main) thread.
        running?.let { stmt -> Thread { runCatching { stmt.cancel() } }.start() }
        executor.shutdown { connection.close() }
    }
}
