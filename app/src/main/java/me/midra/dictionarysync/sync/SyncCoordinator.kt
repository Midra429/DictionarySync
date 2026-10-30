package me.midra.dictionarysync.sync

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One lock shared by source edits, downloads, diagnostics and Provider plans. Never a timed lease. */
object SyncCoordinator {
    private val mutex = Mutex()
    suspend fun <T> locked(context: Context, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            RandomAccessFile(File(context.noBackupFilesDir, "sync.lock"), "rw").use { file ->
                file.channel.lock().use { block() }
            }
        }
    }
    fun installation(context: Context): String {
        val file = AtomicFile(File(context.noBackupFilesDir, "installation-id"))
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) return file.openRead().bufferedReader().use { it.readText() }.also { UUID.fromString(it) }
        return UUID.randomUUID().toString().also { id ->
            val stream = file.startWrite()
            try { stream.write(id.toByteArray()); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }
}
