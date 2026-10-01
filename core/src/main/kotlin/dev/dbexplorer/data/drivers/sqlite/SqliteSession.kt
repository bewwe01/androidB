package dev.dbexplorer.data.drivers.sqlite

import dev.dbexplorer.data.drivers.DbSession
import dev.dbexplorer.data.drivers.SessionExecutor
import dev.dbexplorer.data.drivers.SqlText
import dev.dbexplorer.data.drivers.SqlText.quoteIdent
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
import dev.dbexplorer.domain.model.RoutineRef
import dev.dbexplorer.domain.model.Schema
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.TableDetails
import dev.dbexplorer.domain.model.TableRef
import dev.dbexplorer.domain.model.TableType
import java.sql.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * SQLite metadata comes from `sqlite_master` and PRAGMAs rather than JDBC metadata. Only PRAGMAs
 * available in SQLite 3.18 (Android 8.0) are used.
 */
class SqliteSession(override val profile: ConnectionProfile, private val connection: SqliteConnection) : DbSession {
    private val executor = SessionExecutor("dbx-sqlite-${profile.id}")

    override val serverVersion: String = "SQLite ${connection.sqliteVersion}"

    @Volatile
    override var isAutoCommit: Boolean = true
        private set

    private suspend fun <T> withConnection(block: (SqliteConnection) -> T): T = executor.run {
        try {
            block(connection)
        } catch (e: DbException) {
            throw e
        } catch (e: Exception) {
            throw DbException(e.message ?: e.javaClass.simpleName, cause = e)
        }
    }

    override suspend fun listCatalogs(): List<Catalog> = emptyList()

    override suspend fun listSchemas(catalog: String?): List<Schema> = withConnection { c ->
        c.queryRows("PRAGMA database_list") { it.value(it.columnIndex("name")) as String }
            .filter { it != "temp" }
            .map { Schema(catalog = null, name = it) }
    }

    override suspend fun listTables(schema: SchemaRef, types: Set<TableType>): List<TableRef> = withConnection { c ->
        val db = quoteIdent(schema.schema ?: "main")
        c.queryRows("SELECT name, type FROM $db.sqlite_master WHERE type IN ('table', 'view') ORDER BY name COLLATE NOCASE") { cur ->
            val name = cur.value(0) as String
            val type = when {
                name.startsWith("sqlite_") -> TableType.SYSTEM_TABLE
                cur.value(1) == "view" -> TableType.VIEW
                else -> TableType.TABLE
            }
            TableRef(catalog = null, schema = schema.schema ?: "main", name = name, type = type)
        }.filter { it.type in types }
    }

    override suspend fun listRoutines(schema: SchemaRef): List<RoutineRef> = emptyList()

    override suspend fun describeTable(t: TableRef): TableDetails = withConnection { c ->
        val db = quoteIdent(t.schema ?: "main")
        val table = quoteIdent(t.name)
        data class Col(val info: ColumnInfo, val pkOrder: Int)
        val cols = c.queryRows("PRAGMA $db.table_info($table)") { cur ->
            val declared = (cur.value(cur.columnIndex("type")) as String?).orEmpty()
            Col(
                ColumnInfo(
                    name = cur.value(cur.columnIndex("name")) as String,
                    position = (cur.value(cur.columnIndex("cid")) as Long).toInt() + 1,
                    typeName = declared,
                    jdbcType = jdbcTypeForAffinity(declared),
                    nullable = (cur.value(cur.columnIndex("notnull")) as Long) == 0L,
                    defaultValue = cur.value(cur.columnIndex("dflt_value"))?.toString(),
                ),
                pkOrder = (cur.value(cur.columnIndex("pk")) as Long).toInt(),
            )
        }
        val pkCols = cols.filter { it.pkOrder > 0 }.sortedBy { it.pkOrder }
        // A single INTEGER PRIMARY KEY column is an alias for the rowid and auto-assigned.
        val rowidAlias = pkCols.singleOrNull()?.takeIf { it.info.typeName.equals("INTEGER", ignoreCase = true) }?.info?.name
        val columns = cols.map { col ->
            if (col.info.name == rowidAlias) col.info.copy(autoIncrement = true, nullable = false) else col.info
        }

        val foreignKeys = c.queryRows("PRAGMA $db.foreign_key_list($table)") { cur ->
            listOf(
                cur.value(cur.columnIndex("id")),
                cur.value(cur.columnIndex("seq")),
                cur.value(cur.columnIndex("table")),
                cur.value(cur.columnIndex("from")),
                cur.value(cur.columnIndex("to")),
                cur.value(cur.columnIndex("on_update")),
                cur.value(cur.columnIndex("on_delete")),
            )
        }.groupBy { it[0] as Long }.map { (_, parts) ->
            val ordered = parts.sortedBy { it[1] as Long }
            val refTable = ordered.first()[2] as String
            val explicitTo = ordered.map { it[4] as String? }
            val refColumns = if (explicitTo.all { it != null }) {
                explicitTo.filterNotNull()
            } else {
                // "REFERENCES parent" without columns targets the parent's primary key.
                primaryKeyColumns(c, db, refTable)
            }
            ForeignKey(
                name = null,
                columns = ordered.map { it[3] as String },
                referencedTable = TableRef(null, t.schema ?: "main", refTable),
                referencedColumns = refColumns,
                onUpdate = ReferentialAction.fromSql(ordered.first()[5] as String?),
                onDelete = ReferentialAction.fromSql(ordered.first()[6] as String?),
            )
        }

        val indexes = if (t.type == TableType.VIEW) {
            emptyList()
        } else {
            c.queryRows("PRAGMA $db.index_list($table)") { cur ->
                (cur.value(cur.columnIndex("name")) as String) to (cur.value(cur.columnIndex("unique")) == 1L)
            }.map { (name, unique) ->
                val idxCols = c.queryRows("PRAGMA $db.index_info(${quoteIdent(name)})") { cur ->
                    (cur.value(cur.columnIndex("seqno")) as Long) to (cur.value(cur.columnIndex("name")) as String? ?: "<expression>")
                }.sortedBy { it.first }.map { it.second }
                IndexInfo(name, unique, idxCols)
            }.sortedBy { it.name }
        }

        TableDetails(
            table = t,
            columns = columns,
            primaryKey = pkCols.takeIf { it.isNotEmpty() }?.let { pk -> PrimaryKey(name = null, columns = pk.map { it.info.name }) },
            foreignKeys = foreignKeys,
            indexes = indexes,
        )
    }

    private fun primaryKeyColumns(c: SqliteConnection, db: String, table: String): List<String> =
        c.queryRows("PRAGMA $db.table_info(${quoteIdent(table)})") { cur ->
            (cur.value(cur.columnIndex("pk")) as Long) to (cur.value(cur.columnIndex("name")) as String)
        }.filter { it.first > 0 }.sortedBy { it.first }.map { it.second }

    override suspend fun ddl(t: TableRef): String = withConnection { c ->
        val db = quoteIdent(t.schema ?: "main")
        val own = c.queryRows("SELECT sql FROM $db.sqlite_master WHERE name = ? AND sql IS NOT NULL", listOf(t.name)) {
            it.value(0) as String
        }
        if (own.isEmpty()) throw DbException("${t.name} not found")
        val related = c.queryRows(
            "SELECT sql FROM $db.sqlite_master WHERE tbl_name = ? AND type IN ('index', 'trigger') AND sql IS NOT NULL ORDER BY type, name",
            listOf(t.name),
        ) { it.value(0) as String }
        (own + related).joinToString("\n\n") { it.trimEnd().removeSuffix(";") + ";" }
    }

    override fun execute(sql: String, pageSize: Int, maxRows: Long): Flow<ResultChunk> = flow {
        val started = System.nanoTime()
        try {
            if (SqlText.returnsRows(sql)) {
                connection.query(sql).use { cur ->
                    emit(ResultChunk.Header(cur.columnNames.map { ResultColumn(it, "", Types.OTHER) }))
                    var count = 0L
                    var page = ArrayList<List<Any?>>(pageSize)
                    val width = cur.columnNames.size
                    while (count < maxRows && cur.moveToNext()) {
                        page += List(width) { cur.value(it) }
                        count++
                        if (page.size >= pageSize) {
                            emit(ResultChunk.Rows(page))
                            page = ArrayList(pageSize)
                        }
                    }
                    if (page.isNotEmpty()) emit(ResultChunk.Rows(page))
                    val truncated = count >= maxRows && cur.moveToNext()
                    emit(ResultChunk.Completed((System.nanoTime() - started) / 1_000_000, count, truncated))
                }
            } else {
                emit(ResultChunk.UpdateCount(connection.execute(sql)))
                emit(ResultChunk.Completed((System.nanoTime() - started) / 1_000_000, 0, false))
            }
        } catch (e: DbException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DbException(e.message ?: e.javaClass.simpleName, cause = e)
        }
    }.flowOn(executor.dispatcher)

    override suspend fun cancel() {
        connection.cancel()
    }

    override suspend fun beginTx() = withConnection {
        if (isAutoCommit) {
            it.beginTransaction()
            isAutoCommit = false
        }
    }

    override suspend fun commit() = withConnection {
        if (!isAutoCommit) {
            it.commitTransaction()
            isAutoCommit = true
        }
    }

    override suspend fun rollback() = withConnection {
        if (!isAutoCommit) {
            it.rollbackTransaction()
            isAutoCommit = true
        }
    }

    override suspend fun isValid(): Boolean = !executor.isShutdown

    override fun close() {
        executor.shutdown { connection.close() }
    }

    companion object {
        /** SQLite type affinity rules (https://sqlite.org/datatype3.html §3.1) mapped to JDBC types. */
        fun jdbcTypeForAffinity(declared: String): Int {
            val t = declared.uppercase()
            return when {
                "INT" in t -> Types.BIGINT
                "CHAR" in t || "CLOB" in t || "TEXT" in t -> Types.VARCHAR
                t.isEmpty() || "BLOB" in t -> Types.BLOB
                "REAL" in t || "FLOA" in t || "DOUB" in t -> Types.DOUBLE
                else -> Types.NUMERIC
            }
        }
    }
}
