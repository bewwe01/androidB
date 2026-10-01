package dev.dbexplorer.domain.model

data class ResultColumn(val label: String, val typeName: String, val jdbcType: Int)

/**
 * Pieces of a streamed statement execution, emitted in order:
 * [Header] → zero or more [Rows] → [Completed] for result sets, or [UpdateCount] → [Completed] for DML/DDL.
 */
sealed interface ResultChunk {
    data class Header(val columns: List<ResultColumn>) : ResultChunk

    /** Cell values are normalised to String, Long, Double, BigDecimal, Boolean, ByteArray, or null. */
    data class Rows(val rows: List<List<Any?>>) : ResultChunk

    data class UpdateCount(val count: Long) : ResultChunk

    data class Completed(
        val elapsedMillis: Long,
        val rowCount: Long,
        /** True when the row cap was reached and the remaining rows were not fetched. */
        val truncated: Boolean,
    ) : ResultChunk
}

/** A database error surfaced to the UI with the details users need to fix their SQL. */
class DbException(
    message: String,
    val sqlState: String? = null,
    /** 1-based character position in the statement, when the server reports one. */
    val position: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
