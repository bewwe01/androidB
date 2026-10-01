package dev.dbexplorer.data.drivers

/** Small helpers for building SQL text safely. */
object SqlText {
    /** Quotes an identifier with double quotes (ANSI / PostgreSQL / SQLite). */
    fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    fun quoteLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

    private val leadingNoise = Regex("""\A(?:\s+|--[^\n]*(?:\n|\z)|/\*.*?\*/)*""", RegexOption.DOT_MATCHES_ALL)

    /** First keyword of a statement, upper-cased, skipping whitespace and comments. */
    fun firstKeyword(sql: String): String {
        val rest = sql.replaceFirst(leadingNoise, "")
        return rest.takeWhile { it.isLetter() }.uppercase()
    }

    private val rowReturningKeywords = setOf("SELECT", "WITH", "VALUES", "TABLE", "SHOW", "EXPLAIN", "PRAGMA")

    /** Heuristic: does the statement normally return rows? Used where the driver API cannot tell us. */
    fun returnsRows(sql: String): Boolean = firstKeyword(sql) in rowReturningKeywords
}
