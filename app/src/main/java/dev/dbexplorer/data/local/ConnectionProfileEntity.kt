package dev.dbexplorer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.dbexplorer.domain.model.ConnectionColor
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.SslMode

/** Room row for a [ConnectionProfile]. Never holds secrets. */
@Entity(tableName = "connection_profile")
data class ConnectionProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: DbKind,
    val host: String,
    val port: Int?,
    val database: String,
    val username: String,
    val filePath: String,
    val sslMode: SslMode,
    val readOnly: Boolean,
    val production: Boolean,
    val secureScreen: Boolean,
    val color: ConnectionColor,
    val folder: String,
    val savePassword: Boolean,
    val lastUsedAt: Long? = null,
) {
    fun toDomain() = ConnectionProfile(
        id = id,
        name = name,
        kind = kind,
        host = host,
        port = port,
        database = database,
        username = username,
        filePath = filePath,
        sslMode = sslMode,
        readOnly = readOnly,
        production = production,
        secureScreen = secureScreen,
        color = color,
        folder = folder,
        savePassword = savePassword,
    )

    companion object {
        fun fromDomain(p: ConnectionProfile, lastUsedAt: Long? = null) = ConnectionProfileEntity(
            id = p.id,
            name = p.name,
            kind = p.kind,
            host = p.host,
            port = p.port,
            database = p.database,
            username = p.username,
            filePath = p.filePath,
            sslMode = p.sslMode,
            readOnly = p.readOnly,
            production = p.production,
            secureScreen = p.secureScreen,
            color = p.color,
            folder = p.folder,
            savePassword = p.savePassword,
            lastUsedAt = lastUsedAt,
        )
    }
}
