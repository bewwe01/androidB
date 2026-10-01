package dev.dbexplorer.ui.explorer

import dev.dbexplorer.domain.model.ColumnInfo
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.ForeignKey
import dev.dbexplorer.domain.model.Schema
import dev.dbexplorer.domain.model.TableRef
import java.sql.Types
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerFormattingTest {
    @Test
    fun columnSummaryShowsSizeOnlyForSizedTypes() {
        val varchar = ColumnInfo("name", 1, "varchar", Types.VARCHAR, size = 100, nullable = false)
        assertEquals("varchar(100) · not null", columnSummary(varchar))
        val numeric = ColumnInfo("price", 2, "numeric", Types.NUMERIC, size = 8, decimalDigits = 2, defaultValue = "0")
        assertEquals("numeric(8, 2) · null · default 0", columnSummary(numeric))
        val int = ColumnInfo("id", 3, "int8", Types.BIGINT, size = 19, nullable = false, autoIncrement = true)
        assertEquals("int8 · not null · auto", columnSummary(int))
        val text = ColumnInfo("bio", 4, "text", Types.VARCHAR, size = Int.MAX_VALUE)
        assertEquals("text · null", columnSummary(text))
    }

    @Test
    fun foreignKeySummary() {
        val fk = ForeignKey("fk", listOf("author_id"), TableRef(null, "public", "author"), listOf("id"))
        assertEquals("(author_id) → public.author(id)", foreignKeySummary(fk))
    }

    @Test
    fun systemSchemaFilter() {
        val state = SchemaListUiState(
            profile = ConnectionProfile(name = "x", kind = DbKind.POSTGRES),
            schemas = listOf("public", "pg_catalog", "information_schema", "sales").map { Schema(null, it) },
        )
        assertEquals(listOf("public", "sales"), state.visible.map { it.name })
        assertEquals(listOf("sales"), state.copy(query = "SAL").visible.map { it.name })
        assertEquals(4, state.copy(showSystem = true).visible.size)
    }
}
