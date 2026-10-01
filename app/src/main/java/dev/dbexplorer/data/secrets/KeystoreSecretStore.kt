package dev.dbexplorer.data.secrets

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets with an AES-256-GCM key that lives in the Android Keystore (StrongBox when the
 * device has one, otherwise TEE/hardware-backed where available). Only ciphertext reaches disk.
 *
 * Stored format (Base64): `[iv length][iv][ciphertext+tag]`; the entry key is bound as AAD so a
 * ciphertext cannot be moved to another entry.
 */
class KeystoreSecretStore(context: Context, private val keyAlias: String = DEFAULT_ALIAS, prefsName: String = DEFAULT_PREFS) :
    SecretStore {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(1 + iv.size + ciphertext.size)
        blob[0] = iv.size.toByte()
        iv.copyInto(blob, 1)
        ciphertext.copyInto(blob, 1 + iv.size)
        prefs.edit { putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    @Synchronized
    override fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLength = blob[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 1, ivLength))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // Key invalidated (e.g. lock screen removed) or data tampered with: the secret is unrecoverable.
            Log.w(TAG, "Dropping unreadable secret entry (${e.javaClass.simpleName})")
            prefs.edit { remove(key) }
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping malformed secret entry")
            prefs.edit { remove(key) }
            null
        }
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun remove(key: String) = prefs.edit { remove(key) }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (_: ProviderException) {
                // StrongBoxUnavailableException (API 28+) is a ProviderException; fall back to the TEE.
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "dbx_secrets_v1"
        const val DEFAULT_PREFS = "dbx_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val TAG = "SecretStore"
    }
}
