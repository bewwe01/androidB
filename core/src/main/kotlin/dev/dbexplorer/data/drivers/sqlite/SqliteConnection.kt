package dev.dbexplorer.data.drivers.sqlite

/**
 * Minimal SQLite access needed by [SqliteSession]. The app implements it on top of
 * `android.database.sqlite.SQLiteDatabase` (built into Android, no native library to ship);
 * JVM tests implement it on top of a JDBC driver.
 *
 * All methods are called from a single thread owned by the session.
 */
interface SqliteConnection : AutoCloseable {
    /** e.g. "3.45.1". */
    val sqliteVersion: String

    /** Runs a row-returning statement. The caller closes the cursor. */
    fun query(sql: String, args: List<String> = emptyList()): SqliteCursor

    /** Runs a statement that returns no rows and returns `changes()` for DML (0 for DDL). */
    fun execute(sql: String): Long

    fun beginTransaction()

    fun commitTransaction()

    fun rollbackTransaction()

    /** Interrupts a running [query]; may be called from any thread. */
    fun cancel()
}

interface SqliteCursor : AutoCloseable {
    val columnNames: List<String>

    fun moveToNext(): Boolean

    /** Value of the 0-based column as Long, Double, String, ByteArray or null. */
    fun value(index: Int): Any?
}

fun interface SqliteOpener {
    fun open(path: String, readOnly: Boolean): SqliteConnection
}

internal fun SqliteCursor.columnIndex(name: String): Int = columnNames.indexOfFirst { it.equals(name, ignoreCase = true) }
    .also { require(it >= 0) { "Missing column $name" } }

internal inline fun <T> SqliteConnection.queryRows(sql: String, args: List<String> = emptyList(), transform: (SqliteCursor) -> T): List<T> =
    query(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(transform(c)) }
    }
