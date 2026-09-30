package dev.ipf.whitenoise.android.audio

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val MAX_DIAGNOSTIC_FILE_BYTES = 256 * 1024
private const val DIAGNOSTIC_RETENTION_MILLIS = 24 * 60 * 60 * 1000L

/** Local technical metadata only, outside the engine's automatic-upload directory. */
internal class DictationDiagnosticStore(
    private val directory: File,
    private val buildRevision: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = MAX_DIAGNOSTIC_FILE_BYTES,
    private val retentionMillis: Long = DIAGNOSTIC_RETENTION_MILLIS,
) {
    private val process = UUID.randomUUID().toString()
    private val names = listOf("dictation-current.jsonl", "dictation-previous.jsonl")
    private var sequence = 0L
    private var rotations = 0L
    private var expiredFiles = 0L

    @Synchronized
    fun append(fields: Map<String, Any>) {
        prepare()
        val record =
            JSONObject(fields)
                .put("schema", 1)
                .put("process", process)
                .put("sequence", ++sequence)
                .put("time_ms", nowMillis())
                .put("app_revision", buildRevision.takeIf { it.matches(Regex("[a-f0-9]{7,40}")) } ?: "unknown")
                .toString() + "\n"
        val bytes = record.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw IOException("Diagnostic record exceeds limit")
        val current = File(directory, names[0])
        if (current.length() + bytes.size > maxBytes) {
            val previous = File(directory, names[1])
            Files.move(
                current.toPath(),
                previous.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            rotations += 1
        }
        current.appendBytes(bytes)
    }

    @Synchronized
    fun snapshot(
        enabled: Boolean,
        dropped: Long,
        filtered: Long = 0,
    ): Map<String, ByteArray> {
        prepare()
        val files = names.map { File(directory, it) }.filter { it.exists() }
        val metadata =
            JSONObject()
                .put("schema", 1)
                .put("collection_enabled", enabled)
                .put("dropped_in_process", dropped)
                .put("filtered_fields_in_process", filtered)
                .put("max_file_bytes", maxBytes)
                .put("max_files", names.size)
                .put("retention_ms", retentionMillis)
                .put("coverage", "bounded_local_history")
                .put("rotations_in_process", rotations)
                .put("expired_files_in_process", expiredFiles)
                .put("process", process)
                .put("app_revision", buildRevision.takeIf { it.matches(Regex("[a-f0-9]{7,40}")) } ?: "unknown")
        return buildMap {
            files.forEach { put(it.name, it.readBytes()) }
            put("dictation-manifest.json", metadata.put("files", files.size).toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** No IO or store lock: native logs can still be exported when the writer barrier is unavailable. */
    fun unavailableSnapshot(
        enabled: Boolean,
        dropped: Long,
        reason: String,
        filtered: Long = 0,
    ): Map<String, ByteArray> =
        mapOf(
            "dictation-manifest.json" to
                JSONObject()
                    .put("schema", 1)
                    .put("collection_enabled", enabled)
                    .put("dropped_in_process", dropped)
                    .put("filtered_fields_in_process", filtered)
                    .put("coverage", "snapshot_unavailable")
                    .put("snapshot_failure", reason)
                    .put("files", 0)
                    .put("process", process)
                    .put("app_revision", buildRevision.takeIf { it.matches(Regex("[a-f0-9]{7,40}")) } ?: "unknown")
                    .toString()
                    .toByteArray(Charsets.UTF_8),
        )

    @Synchronized
    fun clear(): Boolean {
        prepareDirectory()
        var removed = false
        names.map { File(directory, it) }.filter { it.exists() }.forEach {
            requireSafeFile(it)
            check(it.delete())
            removed = true
        }
        return removed
    }

    /** Fail closed on a symlink or unexpected size rather than exporting another file. */
    private fun prepare() {
        prepareDirectory()
        names.forEach { validateRetainedFile(File(directory, it)) }
    }

    private fun prepareDirectory() {
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("Unsafe diagnostic directory")
        check(directory.mkdirs() || directory.isDirectory)
    }

    private fun requireSafeFile(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("Unsafe diagnostic file")
        if (file.exists() && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Invalid diagnostic file")
        }
    }

    private fun validateRetainedFile(file: File) {
        requireSafeFile(file)
        if (file.exists()) {
            if (file.length() > maxBytes) throw IOException("Diagnostic file exceeds limit")
            // Record time is authoritative; appends or unsupported timestamp writes cannot extend retention.
            val oldest = file.bufferedReader().use { reader -> JSONObject(reader.readLine()).getLong("time_ms") }
            val age = nowMillis() - oldest
            if (age < 0 || age > retentionMillis) {
                check(file.delete())
                expiredFiles += 1
            }
        }
    }
}
