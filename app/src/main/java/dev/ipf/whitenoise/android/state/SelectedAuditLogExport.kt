package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AuditLogFileFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** A single selected audit file retains its original JSONL format. */
internal fun auditLogExportMimeType(file: File): String =
    if (file.extension == "jsonl") AUDIT_LOG_JSONL_MIME_TYPE else AUDIT_LOG_ARCHIVE_MIME_TYPE

/** Lists nonempty Marmot forensics files newest first. Key-reveal records have a separate purpose. */
internal fun auditLogExportCandidates(files: List<AuditLogFileFfi>): List<AuditLogFileFfi> =
    files
        .filter { it.sizeBytes > 0uL && it.fileName.endsWith(".jsonl") && it.fileName != "audit-key-reveal.jsonl" }
        .sortedWith(compareByDescending<AuditLogFileFfi> { it.modifiedAtMs }.thenBy { it.path })

/** Fetches the current on-device file inventory from MarmotKit. */
internal suspend fun WhiteNoiseAppState.listAuditLogExportCandidates(): List<AuditLogFileFfi> =
    marmotIo { auditLogExportCandidates(auditLogFiles()) }

/** Revalidates the selected paths so a stale or partial selection can never be silently exported. */
internal suspend fun WhiteNoiseAppState.stageSelectedAuditLogs(selectedPaths: Set<String>): File {
    val selected = listAuditLogExportCandidates().filter { it.path in selectedPaths }
    if (selected.size != selectedPaths.size) throw IOException("An audit log is no longer available")
    return withContext(Dispatchers.IO) {
        prepareSelectedAuditLogExport(
            cacheDir = appContext.cacheDir,
            allowedSourceRoot = File(appContext.filesDir, "Marmot"),
            sourcePaths = selected.map { it.path },
        )
    }
}

/** Copies the bytes present at open time, failing if the last record is incomplete. */
internal fun copyAuditSnapshot(
    source: File,
    output: OutputStream,
) {
    FileChannel.open(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
        val length = input.size()
        if (length == 0L) throw IOException("An audit log is empty")
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        buffer.limit(1)
        if (input.read(buffer, length - 1) != 1 || buffer.get(0) != '\n'.code.toByte()) {
            throw IOException("An audit log changed during export")
        }
        var remaining = length
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(remaining, buffer.capacity().toLong()).toInt())
            val count = input.read(buffer)
            if (count <= 0) throw IOException("An audit log changed during export")
            output.write(buffer.array(), 0, count)
            remaining -= count
        }
    }
}
