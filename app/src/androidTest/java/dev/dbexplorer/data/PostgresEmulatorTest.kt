package dev.dbexplorer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dbexplorer.data.drivers.postgres.PostgresDriver
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.ResultChunk
import dev.dbexplorer.domain.model.Secrets
import dev.dbexplorer.domain.model.SslMode
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves pgjdbc works on ART. Needs a PostgreSQL reachable from the device; on the emulator the
 * host machine is 10.0.2.2. Override with instrumentation arguments, e.g.
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.pgPassword=secret`.
 * Skipped (not failed) when nothing listens on the target port.
 */
@RunWith(AndroidJUnit4::class)
class PostgresEmulatorTest {
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("pgHost") ?: "10.0.2.2"
    private val port = args.getString("pgPort")?.toInt() ?: 5432

    private fun reachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
    }.isSuccess

    @Test
    fun connectsBrowsesAndQueries() = runBlocking {
        assumeTrue("No PostgreSQL at $host:$port", reachable())
        val profile = ConnectionProfile(
            name = "emulator",
            kind = DbKind.POSTGRES,
            host = host,
            port = port,
            database = args.getString("pgDatabase") ?: "postgres",
            username = args.getString("pgUser") ?: "postgres",
            sslMode = SslMode.valueOf(args.getString("pgSslMode") ?: "DISABLE"),
        )
        val session = PostgresDriver().connect(profile, Secrets(args.getString("pgPassword")))
        try {
            assertTrue(session.serverVersion, session.serverVersion.startsWith("PostgreSQL"))
            assertTrue(session.listSchemas(null).any { it.name == "public" })
            val rows = session.execute("SELECT 1 + 1 AS two").toList().filterIsInstance<ResultChunk.Rows>().single().rows
            assertEquals(2L, rows.single().single())
        } finally {
            session.close()
        }
    }
}
