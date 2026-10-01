package dev.dbexplorer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dbexplorer.data.local.AppDatabase
import dev.dbexplorer.data.local.ConnectionProfileEntity
import dev.dbexplorer.domain.model.ConnectionColor
import dev.dbexplorer.domain.model.ConnectionProfile
import dev.dbexplorer.domain.model.DbKind
import dev.dbexplorer.domain.model.SslMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private val dao = db.connectionDao()

    @After
    fun tearDown() = db.close()

    @Test
    fun insertReadUpdateDelete() = runTest {
        val profile = ConnectionProfile(
            name = "Home NAS",
            kind = DbKind.POSTGRES,
            host = "10.0.2.2",
            database = "app",
            username = "me",
            sslMode = SslMode.VERIFY_FULL,
            color = ConnectionColor.GREEN,
            folder = "Home",
        )
        val id = dao.insert(ConnectionProfileEntity.fromDomain(profile))
        assertEquals(profile.copy(id = id), dao.get(id)!!.toDomain())

        dao.update(ConnectionProfileEntity.fromDomain(profile.copy(id = id, readOnly = true)))
        assertEquals(true, dao.observeAll().first().single().readOnly)

        dao.delete(id)
        assertNull(dao.get(id))
    }

    @Test
    fun ordersByFolderThenName() = runTest {
        listOf("b" to "", "a" to "Work", "c" to "").forEach { (name, folder) ->
            dao.insert(
                ConnectionProfileEntity.fromDomain(ConnectionProfile(name = name, kind = DbKind.SQLITE, filePath = "/x", folder = folder)),
            )
        }
        assertEquals(listOf("b", "c", "a"), dao.observeAll().first().map { it.name })
    }
}
