package me.midra.dictionarysync.probe

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Local diagnostic artifacts are deliberately excluded from both backup and device migration. */
class ProbeFiles(context: Context) {
    private val directory = File(context.noBackupFilesDir, "probe").apply { mkdirs() }
    private val markerFile = AtomicFile(File(directory, "marker.txt"))
    private val reportFile = AtomicFile(File(directory, "last-report.txt"))

    fun marker(): String {
        if (markerFile.baseFile.exists()) {
            return markerFile.openRead().bufferedReader().use { it.readText() }.also {
                // Never silently replace a damaged identifier: the old manual row may still exist.
                require(ReadProbe.MARKER_PATTERN.matches(it))
            }
        }
        return "${ReadProbe.MARKER_PREFIX}${UUID.randomUUID().toString().take(8)}".also {
            write(markerFile, it)
        }
    }

    fun lastReport(): String = if (reportFile.baseFile.exists()) {
        reportFile.openRead().bufferedReader().use { it.readText() }
    } else ""

    fun saveReport(text: String) = write(reportFile, text)

    private fun write(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
