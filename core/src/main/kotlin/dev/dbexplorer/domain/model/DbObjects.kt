package dev.dbexplorer.domain.model

data class Catalog(val name: String)

data class Schema(val catalog: String?, val name: String) {
    val ref: SchemaRef get() = SchemaRef(catalog, name)
}

data class SchemaRef(val catalog: String?, val schema: String?) {
    val displayName: String get() = schema ?: catalog ?: "default"
}

enum class TableType(val displayName: String, val jdbcNames: Set<String>) {
    TABLE("Tables", setOf("TABLE", "PARTITIONED TABLE")),
    VIEW("Views", setOf("VIEW")),
    MATERIALIZED_VIEW("Materialized views", setOf("MATERIALIZED VIEW")),
    FOREIGN_TABLE("Foreign tables", setOf("FOREIGN TABLE")),
    SEQUENCE("Sequences", setOf("SEQUENCE")),
    SYSTEM_TABLE("System tables", setOf("SYSTEM TABLE", "SYSTEM VIEW")),
    ;

    companion object {
        val BROWSABLE: Set<TableType> = setOf(TABLE, VIEW, MATERIALIZED_VIEW, FOREIGN_TABLE, SEQUENCE)

        fun fromJdbc(name: String?): TableType? {
            val upper = name?.uppercase() ?: return null
            return entries.firstOrNull { upper in it.jdbcNames }
        }
    }
}

data class TableRef(
    val catalog: String?,
    val schema: String?,
    val name: String,
    val type: TableType = TableType.TABLE,
    val remarks: String? = null,
) {
    val schemaRef: SchemaRef get() = SchemaRef(catalog, schema)
    val qualifiedName: String get() = listOfNotNull(schema, name).joinToString(".")
}

enum class RoutineKind { FUNCTION, PROCEDURE }

data class RoutineRef(
    val catalog: String?,
    val schema: String?,
    val name: String,
    val specificName: String,
    val kind: RoutineKind,
    val remarks: String? = null,
)

data class ColumnInfo(
    val name: String,
    val position: Int,
    val typeName: String,
    /** `java.sql.Types` constant, or [java.sql.Types.OTHER] when unknown. */
    val jdbcType: Int,
    val size: Int? = null,
    val decimalDigits: Int? = null,
    val nullable: Boolean = true,
    val defaultValue: String? = null,
    val autoIncrement: Boolean = false,
    val remarks: String? = null,
)

data class PrimaryKey(val name: String?, val columns: List<String>)

enum class ReferentialAction(val sql: String) {
    NO_ACTION("NO ACTION"),
    RESTRICT("RESTRICT"),
    CASCADE("CASCADE"),
    SET_NULL("SET NULL"),
    SET_DEFAULT("SET DEFAULT"),
    ;

    companion object {
        fun fromJdbc(rule: Int): ReferentialAction = when (rule) {
            java.sql.DatabaseMetaData.importedKeyCascade -> CASCADE
            java.sql.DatabaseMetaData.importedKeySetNull -> SET_NULL
            java.sql.DatabaseMetaData.importedKeySetDefault -> SET_DEFAULT
            java.sql.DatabaseMetaData.importedKeyRestrict -> RESTRICT
            else -> NO_ACTION
        }

        fun fromSql(text: String?): ReferentialAction = entries.firstOrNull { it.sql.equals(text?.trim(), ignoreCase = true) } ?: NO_ACTION
    }
}

data class ForeignKey(
    val name: String?,
    val columns: List<String>,
    val referencedTable: TableRef,
    val referencedColumns: List<String>,
    val onUpdate: ReferentialAction = ReferentialAction.NO_ACTION,
    val onDelete: ReferentialAction = ReferentialAction.NO_ACTION,
)

data class IndexInfo(
    val name: String,
    val unique: Boolean,
    /** Column names in index order; expression parts are rendered as their expression text when known. */
    val columns: List<String>,
)

data class TableDetails(
    val table: TableRef,
    val columns: List<ColumnInfo>,
    val primaryKey: PrimaryKey?,
    val foreignKeys: List<ForeignKey>,
    val indexes: List<IndexInfo>,
)
