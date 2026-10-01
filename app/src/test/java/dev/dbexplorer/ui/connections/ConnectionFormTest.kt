package dev.dbexplorer.ui.connections

import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.SslMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormTest {
    private val valid = ConnectionForm(name = "Home", kind = DbKind.POSTGRES, host = "nas.tailnet.ts.net", port = "5432")

    @Test
    fun validNetworkFormHasNoErrors() {
        assertTrue(valid.validate().isEmpty())
    }

    @Test
    fun requiresNameAndHost() {
        val errors = ConnectionForm(kind = DbKind.POSTGRES).validate()
        assertEquals(setOf(ConnectionForm.Field.NAME, ConnectionForm.Field.HOST), errors.keys)
    }

    @Test
    fun rejectsBadPorts() {
        assertTrue(ConnectionForm.Field.PORT in valid.copy(port = "0").validate())
        assertTrue(ConnectionForm.Field.PORT in valid.copy(port = "70000").validate())
        assertTrue(valid.copy(port = "").validate().isEmpty()) // empty means default port
    }

    @Test
    fun rejectsHostWithSpaces() {
        assertTrue(ConnectionForm.Field.HOST in valid.copy(host = "my host").validate())
        assertTrue(valid.copy(host = " 10.0.2.2 ").validate().isEmpty())
    }

    @Test
    fun sqliteNeedsAFile() {
        val form = ConnectionForm(name = "local", kind = DbKind.SQLITE)
        assertEquals(setOf(ConnectionForm.Field.FILE), form.validate().keys)
        assertTrue(form.copy(filePath = "/data/x.db").validate().isEmpty())
    }

    @Test
    fun toProfileTrimsAndDropsIrrelevantFields() {
        val profile = valid.copy(name = " Home ", host = " db ", database = " app ", filePath = "/ignored").toProfile()
        assertEquals("Home", profile.name)
        assertEquals("db", profile.host)
        assertEquals("app", profile.database)
        assertEquals("", profile.filePath)
        assertEquals(5432, profile.port)

        val sqlite = ConnectionForm(name = "f", kind = DbKind.SQLITE, host = "x", filePath = "/f.db").toProfile()
        assertEquals("", sqlite.host)
        assertNull(sqlite.port)
    }

    @Test
    fun passwordIsOnlyStoredWhenEdited() {
        assertNull(valid.copy(password = "pw").passwordToStore())
        assertEquals("pw", valid.copy(password = "pw", passwordEdited = true).passwordToStore())
        assertNull(valid.copy(password = "pw", passwordEdited = true, savePassword = false).passwordToStore())
    }

    @Test
    fun roundTripsThroughProfile() {
        val profile = ConnectionProfile(
            id = 3,
            name = "Prod",
            kind = DbKind.POSTGRES,
            host = "h",
            port = 6432,
            sslMode = SslMode.VERIFY_FULL,
            readOnly = true,
            production = true,
        )
        assertEquals(profile, ConnectionForm.from(profile, hasStoredPassword = true).toProfile())
    }
}
