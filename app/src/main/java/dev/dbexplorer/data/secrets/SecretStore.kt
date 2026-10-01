package dev.dbexplorer.data.secrets

/** Small key/value store for secrets. Implementations must never log values. */
interface SecretStore {
    fun put(key: String, value: String)

    fun get(key: String): String?

    fun contains(key: String): Boolean

    fun remove(key: String)
}
