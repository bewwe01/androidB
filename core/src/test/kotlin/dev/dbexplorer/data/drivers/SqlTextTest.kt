package dev.dbexplorer.data.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlTextTest {
    @Test
    fun firstKeywordSkipsCommentsAndWhitespace() {
        assertEquals("SELECT", SqlText.firstKeyword("  -- hi\n /* multi\nline */ select 1"))
        assertEquals("WITH", SqlText.firstKeyword("with x as (select 1) select * from x"))
        assertEquals("", SqlText.firstKeyword("   "))
    }

    @Test
    fun returnsRows() {
        assertTrue(SqlText.returnsRows("SELECT 1"))
        assertTrue(SqlText.returnsRows("explain analyze select 1"))
        assertFalse(SqlText.returnsRows("update t set a = 1"))
        assertFalse(SqlText.returnsRows("CREATE TABLE t (a int)"))
    }

    @Test
    fun quoting() {
        assertEquals("\"we\"\"ird\"", SqlText.quoteIdent("we\"ird"))
        assertEquals("'it''s'", SqlText.quoteLiteral("it's"))
    }
}
