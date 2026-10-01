package dev.dbexplorer.data.drivers.postgres

import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.Secrets
import dev.dbexplorer.domain.model.SslMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PostgresDriverTest {
    private val base = ConnectionProfile(name = "pg", kind = DbKind.POSTGRES, host = "db.local", database = "app", username = "me")

    @Test
    fun urlUsesDefaultsAndEncodesDatabase() {
        assertEquals("jdbc:postgresql://db.local:5432/app", PostgresDriver.jdbcUrl(base))
        assertEquals(
            "jdbc:postgresql://10.0.2.2:6543/my%20db",
            PostgresDriver.jdbcUrl(base.copy(host = " 10.0.2.2 ", port = 6543, database = "my db")),
        )
        assertEquals("jdbc:postgresql://db.local:5432/postgres", PostgresDriver.jdbcUrl(base.copy(database = "")))
    }

    @Test
    fun urlBracketsIpv6() {
        assertEquals("jdbc:postgresql://[fd7a:115c::1]:5432/app", PostgresDriver.jdbcUrl(base.copy(host = "fd7a:115c::1")))
    }

    @Test
    fun urlNeverContainsCredentials() {
        val url = PostgresDriver.jdbcUrl(base)
        assertFalse(url.contains("me"))
    }

    @Test
    fun sslModesMapToPgjdbcProperties() {
        val disabled = PostgresDriver.connectionProperties(base.copy(sslMode = SslMode.DISABLE), Secrets.NONE)
        assertEquals("disable", disabled.getProperty("sslmode"))
        val required = PostgresDriver.connectionProperties(base.copy(sslMode = SslMode.REQUIRE), Secrets.NONE)
        assertEquals("require", required.getProperty("sslmode"))
        assertNull(required.getProperty("sslfactory"))
        val verify = PostgresDriver.connectionProperties(base.copy(sslMode = SslMode.VERIFY_FULL), Secrets("pw"))
        assertEquals("verify-full", verify.getProperty("sslmode"))
        assertEquals("org.postgresql.ssl.DefaultJavaSSLFactory", verify.getProperty("sslfactory"))
        assertEquals("pw", verify.getProperty("password"))
        assertEquals("disable", verify.getProperty("gssEncMode"))
    }

    @Test
    fun readOnlyIsEnforcedEvenInAutoCommit() {
        val props = PostgresDriver.connectionProperties(base.copy(readOnly = true), Secrets.NONE)
        assertEquals("true", props.getProperty("readOnly"))
        assertEquals("always", props.getProperty("readOnlyMode"))
    }

    @Test
    fun secretsToStringIsRedacted() {
        assertEquals("Secrets(password=***)", Secrets("hunter2").toString())
    }

    @Test
    fun identifierQuotingFollowsQuoteIdent() {
        assertEquals("users", PgIdent.quote("users"))
        assertEquals("\"User\"", PgIdent.quote("User"))
        assertEquals("\"order\"", PgIdent.quote("order"))
        assertEquals("\"my table\"", PgIdent.quote("my table"))
    }
}
