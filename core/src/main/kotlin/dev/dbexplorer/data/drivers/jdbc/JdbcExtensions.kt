package dev.dbexplorer.data.drivers.jdbc

import java.sql.ResultSet

internal fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

internal fun ResultSet.stringOrNull(column: String): String? = getString(column)

internal inline fun <T> ResultSet.mapRows(transform: (ResultSet) -> T): List<T> = use { rs ->
    val out = ArrayList<T>()
    while (rs.next()) out += transform(rs)
    out
}

/** Escapes `%` and `_` for use as a literal in a DatabaseMetaData pattern argument. */
internal fun escapePattern(value: String?, escape: String?): String? {
    if (value == null || escape.isNullOrEmpty()) return value
    return value.replace(escape, escape + escape).replace("%", "$escape%").replace("_", "${escape}_")
}
