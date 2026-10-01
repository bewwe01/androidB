package dev.dbexplorer.data.drivers

import dev.dbexplorer.data.drivers.postgres.PostgresDriver
import dev.dbexplorer.data.drivers.sqlite.SqliteDriver
import dev.dbexplorer.domain.model.DbException
import dev.dbexplorer.domain.model.DbKind
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriverRegistry @Inject constructor() {
    private val postgres = PostgresDriver(applicationName = "DbExplorer (Android)")
    private val sqlite = SqliteDriver(AndroidSqliteConnection.opener)

    val supportedKinds: List<DbKind> = listOf(DbKind.POSTGRES, DbKind.SQLITE)

    fun forKind(kind: DbKind): DatabaseDriver = when (kind) {
        DbKind.POSTGRES -> postgres
        DbKind.SQLITE -> sqlite
        DbKind.MARIADB -> throw DbException("MariaDB / MySQL support is planned for a later release")
    }
}
