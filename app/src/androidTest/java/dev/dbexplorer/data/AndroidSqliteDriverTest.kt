package dev.dbexplorer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dbexplorer.data.drivers.AndroidSqliteConnection
import dev.dbexplorer.data.drivers.sqlite.SqliteDriver
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.ResultChunk
import dev.dbexplorer.domain.model.SchemaRef
import dev.dbexplorer.domain.model.Secrets
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shared SqliteSession logic on the device's own SQLite (3.18 on API 26). */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteDriverTest {
    @Test
    fun browsesAndQueriesOnPlatformSqlite() = runBlocking {
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "driver-test.db").apply { delete() }
        val profile = ConnectionProfile(id = 1, name = "t", kind = DbKind.SQLITE, filePath = file.path)
        val session = SqliteDriver(AndroidSqliteConnection.opener).connect(profile, Secrets.NONE)
        try {
            session.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, name TEXT NOT NULL)").toList()
            session.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))").toList()
            session.execute("CREATE INDEX child_parent ON child (parent_id)").toList()
            assertEquals(ResultChunk.UpdateCount(2), session.execute("INSERT INTO parent (name) VALUES ('a'), ('b')").toList().first())

            val tables = session.listTables(SchemaRef(null, "main")).map { it.name }
            assertTrue(tables.containsAll(listOf("parent", "child")))
            val child = session.describeTable(session.listTables(SchemaRef(null, "main")).first { it.name == "child" })
            assertEquals("parent", child.foreignKeys.single().referencedTable.name)
            assertEquals(listOf("parent_id"), child.indexes.single().columns)

            val rows = session.execute("SELECT name FROM parent ORDER BY id").toList().filterIsInstance<ResultChunk.Rows>().flatMap {
                it.rows
            }
            assertEquals(listOf(listOf<Any?>("a"), listOf<Any?>("b")), rows)

            session.beginTx()
            session.execute("DELETE FROM parent").toList()
            session.rollback()
            val count = session.execute(
                "SELECT count(*) FROM parent",
            ).toList().filterIsInstance<ResultChunk.Rows>().single().rows.single().single()
            assertEquals(2L, count)
        } finally {
            session.close()
        }
    }
}
