package dev.dbexplorer.ui.connections

import dev.dbexplorer.domain.model.ConnectionColor
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.SslMode

/** Editable text state of the connection form; converted to a [ConnectionProfile] on save. */
data class ConnectionForm(
    val id: Long = 0,
    val name: String = "",
    val kind: DbKind = DbKind.POSTGRES,
    val host: String = "",
    val port: String = "",
    val database: String = "",
    val username: String = "",
    val password: String = "",
    /** True once the user typed into the password field (an untouched field keeps the stored one). */
    val passwordEdited: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val savePassword: Boolean = true,
    val filePath: String = "",
    val sslMode: SslMode = SslMode.REQUIRE,
    val readOnly: Boolean = false,
    val production: Boolean = false,
    val secureScreen: Boolean = false,
    val color: ConnectionColor = ConnectionColor.NONE,
    val folder: String = "",
) {
    fun validate(): Map<Field, String> = buildMap {
        if (name.isBlank()) put(Field.NAME, "Required")
        if (kind.usesNetwork) {
            if (host.isBlank()) put(Field.HOST, "Required")
            if (host.trim().any { it.isWhitespace() }) put(Field.HOST, "No spaces")
            if (port.isNotBlank() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) put(Field.PORT, "1–65535")
        } else if (filePath.isBlank()) {
            put(Field.FILE, "Pick or create a database file")
        }
    }

    fun toProfile(): ConnectionProfile = ConnectionProfile(
        id = id,
        name = name.trim(),
        kind = kind,
        host = if (kind.usesNetwork) host.trim() else "",
        port = if (kind.usesNetwork) port.trim().toIntOrNull() else null,
        database = if (kind.usesNetwork) database.trim() else "",
        username = if (kind.usesNetwork) username.trim() else "",
        filePath = if (kind.usesNetwork) "" else filePath,
        sslMode = sslMode,
        readOnly = readOnly,
        production = production,
        secureScreen = secureScreen,
        color = color,
        folder = folder.trim(),
        savePassword = savePassword,
    )

    /** Password to store on save: only when edited (null keeps the stored one). */
    fun passwordToStore(): String? = if (kind.usesNetwork && savePassword && passwordEdited) password else null

    enum class Field { NAME, HOST, PORT, FILE }

    companion object {
        fun from(profile: ConnectionProfile, hasStoredPassword: Boolean) = ConnectionForm(
            id = profile.id,
            name = profile.name,
            kind = profile.kind,
            host = profile.host,
            port = profile.port?.toString().orEmpty(),
            database = profile.database,
            username = profile.username,
            hasStoredPassword = hasStoredPassword,
            savePassword = profile.savePassword,
            filePath = profile.filePath,
            sslMode = profile.sslMode,
            readOnly = profile.readOnly,
            production = profile.production,
            secureScreen = profile.secureScreen,
            color = profile.color,
            folder = profile.folder,
        )
    }
}
