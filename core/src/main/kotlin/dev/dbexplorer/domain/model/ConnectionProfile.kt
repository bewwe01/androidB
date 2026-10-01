package dev.dbexplorer.domain.model

enum class DbKind(val displayName: String, val defaultPort: Int?, val usesNetwork: Boolean) {
    POSTGRES("PostgreSQL", 5432, true),
    SQLITE("SQLite", null, false),
    MARIADB("MariaDB / MySQL", 3306, true),
}

/** Mirrors libpq `sslmode` semantics for the subset we support. */
enum class SslMode(val displayName: String) {
    DISABLE("Disable"),
    REQUIRE("Require (no verification)"),
    VERIFY_FULL("Verify CA + hostname"),
}

enum class ConnectionColor { NONE, RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }

/**
 * A saved connection. Contains no secrets: passwords live in the Keystore-backed secret store,
 * keyed by [id].
 */
data class ConnectionProfile(
    val id: Long = 0,
    val name: String,
    val kind: DbKind,
    val host: String = "",
    val port: Int? = kind.defaultPort,
    val database: String = "",
    val username: String = "",
    /** Absolute path of the database file for [DbKind.SQLITE]. */
    val filePath: String = "",
    val sslMode: SslMode = SslMode.REQUIRE,
    val readOnly: Boolean = false,
    val production: Boolean = false,
    val secureScreen: Boolean = false,
    val color: ConnectionColor = ConnectionColor.NONE,
    val folder: String = "",
    val savePassword: Boolean = true,
) {
    val effectivePort: Int? get() = port ?: kind.defaultPort

    /** Human-readable target, never containing credentials. */
    val target: String
        get() = when {
            !kind.usesNetwork -> filePath
            else -> buildString {
                append(host)
                effectivePort?.let { append(':').append(it) }
                if (database.isNotEmpty()) append('/').append(database)
            }
        }
}

/** Secrets resolved at connect time. [toString] never reveals values. */
class Secrets(val password: String?) {
    override fun toString(): String = "Secrets(password=${if (password == null) "null" else "***"})"

    companion object {
        val NONE = Secrets(null)
    }
}
