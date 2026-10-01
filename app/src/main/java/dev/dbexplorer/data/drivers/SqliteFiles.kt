package dev.dbexplorer.data.drivers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite needs a real file path, but the Storage Access Framework hands out content URIs, so
 * picked databases are copied into app-private storage. Edits apply to that copy.
 */
@Singleton
class SqliteFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "sqlite").apply { mkdirs() }

    /** Copies the document at [uri] into app storage and returns the copy's absolute path. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "imported.db"
        val target = uniqueFile(sanitize(displayName))
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read the selected file" }
            target.outputStream().use { input.copyTo(it) }
        }
        target.absolutePath
    }

    /** Path for a new, empty database (created on first connect). */
    fun newDatabasePath(name: String): String {
        val base = sanitize(name).let { if (it.contains('.')) it else "$it.db" }
        return uniqueFile(base).absolutePath
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { "database.db" }

    private fun uniqueFile(name: String): File {
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, name.substringBeforeLast('.') + "-${n++}." + name.substringAfterLast('.', "db"))
        }
        return candidate
    }
}
