package dev.dbexplorer.data.drivers.postgres

import dev.dbexplorer.data.drivers.SqlText
import dev.dbexplorer.data.drivers.jdbc.JdbcDbSession
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.TableRef
import dev.dbexplorer.domain.model.TableType
import java.sql.Connection
import java.sql.SQLException
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage

class PostgresSession(profile: ConnectionProfile, connection: Connection) : JdbcDbSession(profile, connection) {
    private val majorVersion: Int = connection.metaData.databaseMajorVersion

    override val needsTransactionForCursor: Boolean = true

    override fun toDbException(e: SQLException): DbException = Companion.toDbException(e)

    override suspend fun ddl(t: TableRef): String = when (t.type) {
        TableType.VIEW -> viewDdl(t, materialized = false)
        TableType.MATERIALIZED_VIEW -> viewDdl(t, materialized = true)
        TableType.SEQUENCE -> sequenceDdl(t)
        else -> tableDdl(t)
    }

    private fun qualified(t: TableRef) = listOfNotNull(t.schema, t.name).joinToString(".") { PgIdent.quote(it) }

    private suspend fun viewDdl(t: TableRef, materialized: Boolean): String = withConnection { c ->
        val body = c.prepareStatement("SELECT pg_get_viewdef(?::regclass, true)").use { ps ->
            ps.setString(1, qualified(t))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: throw DbException("View ${t.qualifiedName} not found")
        val head = if (materialized) "CREATE MATERIALIZED VIEW" else "CREATE OR REPLACE VIEW"
        "$head ${qualified(t)} AS\n" + body.trimEnd().removeSuffix(";") + ";"
    }

    private suspend fun sequenceDdl(t: TableRef): String = withConnection { c ->
        if (majorVersion < 10) return@withConnection "-- Sequence DDL requires PostgreSQL 10 or later"
        c.prepareStatement(
            """
            SELECT data_type::text, start_value, min_value, max_value, increment_by, cycle, cache_size
            FROM pg_sequences WHERE schemaname = ? AND sequencename = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, t.schema)
            ps.setString(2, t.name)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw DbException("Sequence ${t.qualifiedName} not found")
                buildString {
                    append("CREATE SEQUENCE ").append(qualified(t))
                    append("\n    AS ").append(rs.getString(1))
                    append("\n    INCREMENT BY ").append(rs.getLong(5))
                    append("\n    MINVALUE ").append(rs.getLong(3))
                    append("\n    MAXVALUE ").append(rs.getLong(4))
                    append("\n    START WITH ").append(rs.getLong(2))
                    append("\n    CACHE ").append(rs.getLong(7))
                    append(if (rs.getBoolean(6)) "\n    CYCLE;" else "\n    NO CYCLE;")
                }
            }
        }
    }

    private suspend fun tableDdl(t: TableRef): String = withConnection { c ->
        val name = qualified(t)
        val identityCol = if (majorVersion >= 10) "a.attidentity::text" else "''"
        val generatedCol = if (majorVersion >= 12) "a.attgenerated::text" else "''"
        data class Col(
            val name: String,
            val type: String,
            val notNull: Boolean,
            val default: String?,
            val identity: String,
            val generated: String,
            val comment: String?,
        )
        val cols = c.prepareStatement(
            """
            SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                   pg_get_expr(d.adbin, d.adrelid), $identityCol, $generatedCol, col_description(a.attrelid, a.attnum)
            FROM pg_attribute a
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Col(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getBoolean(3),
                                rs.getString(4),
                                rs.getString(5).orEmpty(),
                                rs.getString(6).orEmpty(),
                                rs.getString(7),
                            ),
                        )
                    }
                }
            }
        }
        if (cols.isEmpty()) throw DbException("Table ${t.qualifiedName} not found or has no columns")

        val constraints = c.prepareStatement(
            """
            SELECT conname, pg_get_constraintdef(oid, true)
            FROM pg_constraint
            WHERE conrelid = ?::regclass AND contype IN ('p', 'u', 'c', 'f', 'x')
            ORDER BY CASE contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 WHEN 'c' THEN 2 WHEN 'x' THEN 3 ELSE 4 END, conname
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }

        val indexes = c.prepareStatement(
            """
            SELECT pg_get_indexdef(i.indexrelid)
            FROM pg_index i
            WHERE i.indrelid = ?::regclass
              AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid AND c.conrelid = i.indrelid)
            ORDER BY 1
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

        val tableComment = c.prepareStatement("SELECT obj_description(?::regclass, 'pg_class')").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

        buildString {
            append("CREATE TABLE ").append(name).append(" (\n")
            val lines = cols.map { col ->
                buildString {
                    append("    ").append(PgIdent.quote(col.name)).append(' ').append(col.type)
                    when {
                        col.generated == "s" -> append(" GENERATED ALWAYS AS (").append(col.default).append(") STORED")
                        col.identity == "a" -> append(" GENERATED ALWAYS AS IDENTITY")
                        col.identity == "d" -> append(" GENERATED BY DEFAULT AS IDENTITY")
                        col.default != null -> append(" DEFAULT ").append(col.default)
                    }
                    if (col.notNull) append(" NOT NULL")
                }
            } + constraints.map { (conName, def) -> "    CONSTRAINT ${PgIdent.quote(conName)} $def" }
            append(lines.joinToString(",\n"))
            append("\n);")
            indexes.forEach { append("\n\n").append(it).append(';') }
            tableComment?.let { append("\n\nCOMMENT ON TABLE ").append(name).append(" IS ").append(SqlText.quoteLiteral(it)).append(';') }
            cols.filter { it.comment != null }.forEach { col ->
                append("\nCOMMENT ON COLUMN ").append(name).append('.').append(PgIdent.quote(col.name))
                append(" IS ").append(SqlText.quoteLiteral(col.comment!!)).append(';')
            }
        }
    }

    companion object {
        fun toDbException(e: SQLException): DbException {
            // Explicit types: pgjdbc's checker-framework annotations are not on our classpath.
            val server: ServerErrorMessage? = (e as? PSQLException)?.serverErrorMessage
            val message: String = server?.let { sem ->
                val severity: String? = sem.severity
                val text: String? = sem.message
                val detail: String? = sem.detail
                val hint: String? = sem.hint
                buildString {
                    append(severity ?: "ERROR").append(": ").append(text ?: e.message)
                    detail?.let { append("\nDetail: ").append(it) }
                    hint?.let { append("\nHint: ").append(it) }
                }
            } ?: (e.message ?: e.javaClass.simpleName)
            val position: Int? = server?.position?.takeIf { it > 0 }
            return DbException(message, e.sqlState, position, e)
        }
    }
}

/** PostgreSQL's `quote_ident` rules: leave simple lower-case, non-reserved names bare. */
internal object PgIdent {
    private val simple = Regex("^[a-z_][a-z0-9_$]*$")
    private val reserved = setOf(
        "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both", "case", "cast", "check", "collate",
        "column", "constraint", "create", "current_catalog", "current_date", "current_role", "current_time", "current_timestamp",
        "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end", "except", "false", "fetch", "for",
        "foreign", "from", "grant", "group", "having", "in", "initially", "intersect", "into", "lateral", "leading", "limit",
        "localtime", "localtimestamp", "not", "null", "offset", "on", "only", "or", "order", "placing", "primary", "references",
        "returning", "select", "session_user", "some", "symmetric", "system_user", "table", "then", "to", "trailing", "true",
        "union", "unique", "user", "using", "variadic", "when", "where", "window", "with",
    )

    fun quote(name: String): String = if (simple.matches(name) && name !in reserved) name else SqlText.quoteIdent(name)
}
