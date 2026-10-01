package dev.dbexplorer.data.drivers.postgres

import dev.dbexplorer.data.drivers.DatabaseDriver
import dev.dbexplorer.data.drivers.DbSession
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.Secrets
import dev.dbexplorer.domain.model.SslMode
import java.net.URLEncoder
import java.sql.SQLException
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PostgresDriver(private val applicationName: String = "DbExplorer", private val connectTimeoutSeconds: Int = 15) : DatabaseDriver {
    override val kind: DbKind = DbKind.POSTGRES

    override suspend fun connect(profile: ConnectionProfile, secrets: Secrets): DbSession = withContext(Dispatchers.IO) {
        require(profile.kind == DbKind.POSTGRES) { "Not a PostgreSQL profile" }
        if (profile.host.isBlank()) throw DbException("Host is required")
        val connection = try {
            // Instantiate the driver directly: DriverManager's ServiceLoader lookup is unreliable on Android.
            org.postgresql.Driver().connect(
                jdbcUrl(profile),
                connectionProperties(profile, secrets, applicationName, connectTimeoutSeconds),
            )
                ?: throw DbException("PostgreSQL driver rejected the connection URL")
        } catch (e: SQLException) {
            throw PostgresSession.toDbException(e)
        }
        try {
            PostgresSession(profile, connection)
        } catch (e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
    }

    companion object {
        /** Builds the JDBC URL. Never contains credentials, so it is safe to show (but we still don't log it). */
        fun jdbcUrl(profile: ConnectionProfile): String {
            val rawHost = profile.host.trim()
            val host = if (':' in rawHost && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
            val port = profile.effectivePort ?: 5432
            val db = profile.database.trim().ifEmpty { "postgres" }
            return "jdbc:postgresql://$host:$port/" + URLEncoder.encode(db, Charsets.UTF_8.name()).replace("+", "%20")
        }

        fun connectionProperties(
            profile: ConnectionProfile,
            secrets: Secrets,
            applicationName: String = "DbExplorer",
            connectTimeoutSeconds: Int = 15,
        ): Properties = Properties().apply {
            setProperty("user", profile.username)
            secrets.password?.let { setProperty("password", it) }
            setProperty("ApplicationName", applicationName)
            setProperty("connectTimeout", connectTimeoutSeconds.toString())
            setProperty("loginTimeout", (connectTimeoutSeconds + 5).toString())
            setProperty("tcpKeepAlive", "true")
            // GSSAPI needs javax.security.auth.kerberos, which Android does not ship.
            setProperty("gssEncMode", "disable")
            when (profile.sslMode) {
                SslMode.DISABLE -> setProperty("sslmode", "disable")
                SslMode.REQUIRE -> setProperty("sslmode", "require")
                SslMode.VERIFY_FULL -> {
                    setProperty("sslmode", "verify-full")
                    // Validate against the platform trust store instead of libpq's ~/.postgresql/root.crt.
                    setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory")
                }
            }
            if (profile.readOnly) {
                setProperty("readOnly", "true")
                // "always" also enforces read-only while auto-commit is on.
                setProperty("readOnlyMode", "always")
            }
        }
    }
}
