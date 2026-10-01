package dev.dbexplorer.data.drivers

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import dev.dbexplorer.data.drivers.sqlite.SqliteConnection
import dev.dbexplorer.data.drivers.sqlite.SqliteCursor
import dev.dbexplorer.data.drivers.sqlite.SqliteOpener

/** [SqliteConnection] on the platform's built-in SQLite. Used from the session's single thread. */
class AndroidSqliteConnection(path: String, readOnly: Boolean) : SqliteConnection {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        path,
        null,
        if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
    )

    @Volatile
    private var signal: CancellationSignal? = null

    override val sqliteVersion: String = DatabaseUtils.stringForQuery(db, "SELECT sqlite_version()", null)

    override fun query(sql: String, args: List<String>): SqliteCursor {
        val cancel = CancellationSignal()
        signal = cancel
        return AndroidCursor(db.rawQuery(sql, args.toTypedArray(), cancel))
    }

    override fun execute(sql: String): Long = when (SqlText.firstKeyword(sql)) {
        "INSERT", "UPDATE", "DELETE", "REPLACE" -> db.compileStatement(sql).use { it.executeUpdateDelete().toLong() }
        else -> {
            db.execSQL(sql)
            0L
        }
    }

    // Android ties transactions to the calling thread; SqliteSession always calls from its own thread.
    override fun beginTransaction() = db.beginTransactionNonExclusive()

    override fun commitTransaction() {
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    override fun rollbackTransaction() = db.endTransaction()

    override fun cancel() {
        signal?.cancel()
    }

    override fun close() = db.close()

    private class AndroidCursor(private val cursor: Cursor) : SqliteCursor {
        override val columnNames: List<String> = cursor.columnNames.toList()

        override fun moveToNext(): Boolean = cursor.moveToNext()

        override fun value(index: Int): Any? = when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
            else -> cursor.getString(index)
        }

        override fun close() = cursor.close()
    }

    companion object {
        val opener = SqliteOpener { path, readOnly -> AndroidSqliteConnection(path, readOnly) }
    }
}
