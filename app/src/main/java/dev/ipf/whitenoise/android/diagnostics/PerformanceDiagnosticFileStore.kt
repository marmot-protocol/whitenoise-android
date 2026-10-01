package dev.ipf.whitenoise.android.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * Private, bounded copy of closed-schema WNPerf lines. Any writer failure is retained so an
 * explicit export fails instead of silently omitting events.
 */
@Suppress("TooGenericExceptionCaught")
internal class PerformanceDiagnosticFileStore(
    private val directory: File,
) {
    private val file = File(directory, FILE_NAME)
    private val writer =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "WNPerf-file").apply { isDaemon = true }
        }
    private var written = 0
    private var failure: Throwable? = null

    /** Replaces the previous session before any lines from this one are queued. */
    fun reset() {
        writer.execute {
            try {
                checkSafePath()
                file.writeText("")
                written = 0
                failure = null
            } catch (error: Throwable) {
                failure = error
            }
        }
    }

    /** Queues a sanitized line without putting file I/O on a measured operation's thread. */
    fun append(line: String) {
        writer.execute {
            if (failure != null) return@execute
            try {
                if (written >= PerformanceDiagnosticEmitter.SESSION_EVENT_LIMIT) return@execute
                check(line.length <= MAX_LINE_LENGTH) { "Performance log line exceeds its bound" }
                checkSafePath()
                file.appendText("$line\n")
                written++
            } catch (error: Throwable) {
                failure = error
            }
        }
    }

    /** Flushes queued writes and returns the exact file bytes for an immutable archive snapshot. */
    fun readForExport(): ByteArray? =
        writer
            .submit<ByteArray?> {
                failure?.let { throw IOException("Unable to read performance diagnostics", it) }
                if (!file.exists()) return@submit null
                checkSafePath()
                check(file.length() <= MAX_FILE_BYTES) { "Performance log exceeds its bound" }
                file.readBytes()
            }.get()

    /** Clears the on-disk session; later events may begin a fresh file while opt-in remains active. */
    fun delete(): Boolean =
        writer
            .submit<Boolean> {
                if (!file.exists()) return@submit false
                checkSafePath()
                check(file.delete()) { "Unable to delete performance diagnostics" }
                written = 0
                failure = null
                true
            }.get()

    /** Rejects a replaced directory or file rather than following a link outside private storage. */
    private fun checkSafePath() {
        check(!Files.isSymbolicLink(directory.toPath())) { "Unsafe performance diagnostics directory" }
        check(directory.mkdirs() || directory.isDirectory) { "Unable to prepare performance diagnostics" }
        check(!Files.isSymbolicLink(file.toPath())) { "Unsafe performance diagnostics file" }
    }

    internal companion object {
        const val FILE_NAME = "performance-session.log"
        private const val MAX_LINE_LENGTH = 512
        private const val MAX_FILE_BYTES = PerformanceDiagnosticEmitter.SESSION_EVENT_LIMIT * (MAX_LINE_LENGTH + 1)
    }
}
