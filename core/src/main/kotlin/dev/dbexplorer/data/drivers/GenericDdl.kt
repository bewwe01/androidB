package dev.dbexplorer.data.drivers

import dev.dbexplorer.domain.model.ReferentialAction
import dev.dbexplorer.domain.model.TableDetails

/** Best-effort DDL synthesised from JDBC metadata, for dialects without a native DDL source. */
object GenericDdl {
    fun createTable(details: TableDetails, quote: (String) -> String = SqlText::quoteIdent): String {
        val t = details.table
        val name = listOfNotNull(t.schema, t.name).joinToString(".") { quote(it) }
        val lines = mutableListOf<String>()
        details.columns.sortedBy { it.position }.forEach { c ->
            val type = buildString {
                append(c.typeName)
                if (c.size != null && c.typeName.lowercase() in SIZED_TYPES) {
                    append('(').append(c.size)
                    if (c.decimalDigits != null && c.decimalDigits > 0) append(", ").append(c.decimalDigits)
                    append(')')
                }
            }
            lines += buildString {
                append("    ").append(quote(c.name)).append(' ').append(type)
                c.defaultValue?.let { append(" DEFAULT ").append(it) }
                if (!c.nullable) append(" NOT NULL")
            }
        }
        details.primaryKey?.let { pk ->
            lines += "    " + constraintPrefix(pk.name, quote) + "PRIMARY KEY (" + pk.columns.joinToString { quote(it) } + ")"
        }
        details.foreignKeys.forEach { fk ->
            val ref = listOfNotNull(fk.referencedTable.schema, fk.referencedTable.name).joinToString(".") { quote(it) }
            lines += buildString {
                append("    ").append(constraintPrefix(fk.name, quote))
                append("FOREIGN KEY (").append(fk.columns.joinToString { quote(it) }).append(") REFERENCES ")
                append(ref).append(" (").append(fk.referencedColumns.joinToString { quote(it) }).append(')')
                if (fk.onUpdate != ReferentialAction.NO_ACTION) append(" ON UPDATE ").append(fk.onUpdate.sql)
                if (fk.onDelete != ReferentialAction.NO_ACTION) append(" ON DELETE ").append(fk.onDelete.sql)
            }
        }
        val pkCols = details.primaryKey?.columns
        val indexes = details.indexes.filter { it.columns != pkCols }.map { idx ->
            "CREATE " + (if (idx.unique) "UNIQUE " else "") + "INDEX " + quote(idx.name) + " ON " + name +
                " (" + idx.columns.joinToString { quote(it) } + ");"
        }
        return buildString {
            append("CREATE TABLE ").append(name).append(" (\n")
            append(lines.joinToString(",\n"))
            append("\n);")
            indexes.forEach { append("\n\n").append(it) }
        }
    }

    private fun constraintPrefix(name: String?, quote: (String) -> String) = if (name.isNullOrEmpty()) "" else "CONSTRAINT ${quote(name)} "

    private val SIZED_TYPES =
        setOf("varchar", "char", "character", "character varying", "nvarchar", "nchar", "decimal", "numeric", "varbinary", "binary")
}
