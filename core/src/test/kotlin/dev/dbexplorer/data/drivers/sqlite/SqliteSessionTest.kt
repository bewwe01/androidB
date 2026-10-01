package dev.dbexplorer.data.drivers.sqlite

import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.ReferentialAction
import dev.dbexplorer.domain.model.ResultChunk
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.Secrets
import dev.dbexplorer.domain.model.TableType
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SqliteSessionTest {
    private lateinit var file: File
    private lateinit var session: SqliteSession
    private val driver = SqliteDriver { path, readOnly -> JdbcSqliteConnection(path, readOnly) }

    private fun profile(readOnly: Boolean = false) =
        ConnectionProfile(id = 1, name = "t", kind = DbKind.SQLITE, filePath = file.path, readOnly = readOnly)

    @Before
    fun setUp() = runBlocking {
        file = File.createTempFile("dbx", ".db")
        session = driver.connect(profile(), Secrets.NONE) as SqliteSession
        listOf(
            "CREATE TABLE author (id INTEGER PRIMARY KEY, name TEXT NOT NULL, born DATE DEFAULT '1900-01-01')",
            "CREATE TABLE book (isbn TEXT, edition INT, author_id INTEGER REFERENCES author ON DELETE CASCADE, price REAL, " +
                "PRIMARY KEY (isbn, edition))",
            "CREATE UNIQUE INDEX book_author_idx ON book (author_id, isbn)",
            "CREATE VIEW cheap AS SELECT * FROM book WHERE price < 10",
            "INSERT INTO author (name) VALUES ('Ada'), ('Grace'), ('Edsger')",
        ).forEach { sql -> session.execute(sql).toList() }
    }

    @After
    fun tearDown() {
        session.close()
        file.delete()
    }

    @Test
    fun listsSchemasAndTables() = runBlocking {
        assertEquals(listOf("main"), session.listSchemas(null).map { it.name })
        val tables = session.listTables(SchemaRef(null, "main"))
        assertEquals(
            listOf("author" to TableType.TABLE, "book" to TableType.TABLE, "cheap" to TableType.VIEW),
            tables.map {
                it.name to
                    it.type
            },
        )
        assertTrue(session.serverVersion.startsWith("SQLite "))
    }

    @Test
    fun describesColumnsKeysAndIndexes() = runBlocking {
        val author = session.describeTable(session.listTables(SchemaRef(null, "main")).first { it.name == "author" })
        assertEquals(listOf("id", "name", "born"), author.columns.map { it.name })
        assertTrue(author.columns[0].autoIncrement)
        assertFalse(author.columns[1].nullable)
        assertEquals("'1900-01-01'", author.columns[2].defaultValue)
        assertEquals(listOf("id"), author.primaryKey?.columns)

        val book = session.describeTable(session.listTables(SchemaRef(null, "main")).first { it.name == "book" })
        assertEquals(listOf("isbn", "edition"), book.primaryKey?.columns)
        assertFalse(book.columns.first { it.name == "isbn" }.autoIncrement)
        val fk = book.foreignKeys.single()
        assertEquals(listOf("author_id"), fk.columns)
        assertEquals("author", fk.referencedTable.name)
        assertEquals(listOf("id"), fk.referencedColumns) // implicit reference to the parent's PK
        assertEquals(ReferentialAction.CASCADE, fk.onDelete)
        val idx = book.indexes.first { it.name == "book_author_idx" }
        assertTrue(idx.unique)
        assertEquals(listOf("author_id", "isbn"), idx.columns)
    }

    @Test
    fun ddlIncludesIndexes() = runBlocking {
        val book = session.listTables(SchemaRef(null, "main")).first { it.name == "book" }
        val ddl = session.ddl(book)
        assertTrue(ddl, ddl.startsWith("CREATE TABLE book"))
        assertTrue(ddl, ddl.contains("CREATE UNIQUE INDEX book_author_idx ON book (author_id, isbn);"))
    }

    @Test
    fun executeStreamsPagesAndTruncates() = runBlocking {
        val chunks = session.execute("SELECT id, name FROM author ORDER BY id", pageSize = 2, maxRows = 2).toList()
        val header = chunks.first() as ResultChunk.Header
        assertEquals(listOf("id", "name"), header.columns.map { it.label })
        val rows = chunks.filterIsInstance<ResultChunk.Rows>().flatMap { it.rows }
        assertEquals(listOf(listOf<Any?>(1L, "Ada"), listOf<Any?>(2L, "Grace")), rows)
        val done = chunks.last() as ResultChunk.Completed
        assertEquals(2, done.rowCount)
        assertTrue(done.truncated)
    }

    @Test
    fun executeReportsUpdateCount() = runBlocking {
        val chunks = session.execute("UPDATE author SET name = upper(name) WHERE id > 1").toList()
        assertEquals(ResultChunk.UpdateCount(2), chunks.first())
    }

    @Test
    fun transactionsRollBack() = runBlocking {
        session.beginTx()
        assertFalse(session.isAutoCommit)
        session.execute("DELETE FROM author").toList()
        session.rollback()
        assertTrue(session.isAutoCommit)
        val rows = session.execute("SELECT count(*) FROM author").toList().filterIsInstance<ResultChunk.Rows>().single().rows
        assertEquals(3L, rows.single().single())
    }

    @Test
    fun readOnlyProfileRejectsWrites() = runBlocking {
        val ro = driver.connect(profile(readOnly = true), Secrets.NONE)
        try {
            val error = runCatching { ro.execute("DELETE FROM author").toList() }.exceptionOrNull()
            assertTrue("expected DbException, got $error", error is DbException)
        } finally {
            ro.close()
        }
    }

    @Test
    fun routinesAreEmpty() = runBlocking {
        assertNull(session.listRoutines(SchemaRef(null, "main")).firstOrNull())
    }
}
