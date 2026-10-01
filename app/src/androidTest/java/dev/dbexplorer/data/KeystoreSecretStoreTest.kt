package dev.dbexplorer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dbexplorer.data.secrets.KeystoreSecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = KeystoreSecretStore(context, keyAlias = "dbx_test_key", prefsName = "dbx_test_secrets")

    @After
    fun tearDown() {
        context.getSharedPreferences("dbx_test_secrets", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun roundTrip() {
        store.put("connection.1.password", "s3cr3t-ünïcode")
        assertTrue(store.contains("connection.1.password"))
        assertEquals("s3cr3t-ünïcode", store.get("connection.1.password"))
        store.remove("connection.1.password")
        assertNull(store.get("connection.1.password"))
    }

    @Test
    fun onlyCiphertextReachesDisk() {
        store.put("k", "plaintext-password")
        val raw = context.getSharedPreferences("dbx_test_secrets", Context.MODE_PRIVATE).getString("k", null)!!
        assertFalse(raw.contains("plaintext-password"))
    }

    @Test
    fun ciphertextIsBoundToItsKey() {
        store.put("a", "value")
        val prefs = context.getSharedPreferences("dbx_test_secrets", Context.MODE_PRIVATE)
        prefs.edit().putString("b", prefs.getString("a", null)).commit()
        assertNull("moved ciphertext must not decrypt", store.get("b"))
    }
}
