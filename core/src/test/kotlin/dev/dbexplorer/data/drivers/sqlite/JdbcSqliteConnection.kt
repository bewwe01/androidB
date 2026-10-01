package dev.dbexplorer.data.drivers.sqlite

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import org.sqlite.SQLiteConfig

/** Test-only [SqliteConnection] backed by xerial sqlite-jdbc, mirroring the Android adapter. */
class JdbcSqliteConnection(path: String, readOnly: Boolean) : SqliteConnection {
    private val conn: Connection = SQLiteConfig().apply { setReadOnly(readOnly) }
        .let { DriverManager.getConnection("jdbc:sqlite:$path", it.toProperties()) }

    @Volatile
    private var current: Statement? = null

    override val sqliteVersion: String = conn.metaData.databaseProductVersion

    override fun query(sql: String, args: List<String>): SqliteCursor {
        val ps = conn.prepareStatement(sql)
        args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
        current = ps
        return Cursor(ps.executeQuery(), ps)
    }

    override fun execute(sql: String): Long = conn.createStatement().use { st ->
        current = st
        st.executeUpdate(sql).toLong()
    }

    override fun beginTransaction() {
        conn.autoCommit = false
    }

    override fun commitTransaction() {
        conn.commit()
        conn.autoCommit = true
    }

    override fun rollbackTransaction() {
        conn.rollback()
        conn.autoCommit = true
    }

    override fun cancel() {
        current?.cancel()
    }

    override fun close() = conn.close()

    private class Cursor(private val rs: ResultSet, private val st: Statement) : SqliteCursor {
        override val columnNames: List<String> = List(rs.metaData.columnCount) { rs.metaData.getColumnLabel(it + 1) }

        override fun moveToNext(): Boolean = rs.next()

        override fun value(index: Int): Any? = when (val v = rs.getObject(index + 1)) {
            is Int -> v.toLong()
            is Float -> v.toDouble()
            else -> v
        }

        override fun close() {
            rs.close()
            st.close()
        }
    }
}
