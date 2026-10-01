package dev.dbexplorer.data.drivers

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A single background thread that owns one database connection. JDBC connections (and Android's
 * SQLite transactions) are not safe to use from several threads at once, so every call on a
 * session is funnelled through here.
 */
class SessionExecutor(name: String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @Volatile
    var isShutdown: Boolean = false
        private set

    suspend fun <T> run(block: () -> T): T {
        check(!isShutdown) { "Session is closed" }
        return withContext(dispatcher) { block() }
    }

    /** Runs [finalTask] after any queued work, then stops the thread. Never blocks the caller. */
    fun shutdown(finalTask: () -> Unit) {
        if (isShutdown) return
        isShutdown = true
        executor.execute { runCatching(finalTask) }
        executor.shutdown()
    }
}
