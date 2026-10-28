package dev.ipf.whitenoise.android.state

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal const val LOCAL_SEND_TRACE_DIRECTORY = "local-send-trace"
internal const val LOCAL_SEND_TRACE_MANIFEST = "local-send-trace-manifest.json"
internal const val MAX_LOCAL_SEND_TRACE_BYTES = 256 * 1024
private const val MAX_TRACE_DIRECTORY_ENTRIES = 32
private const val MAX_TRACE_ROW_BYTES = 512
private const val TRACE_WRITER_SCRATCH = "manifest.tmp"
private val traceSegments = (0..3).map { "segment-$it.jsonl" }
private val traceSourceNames = traceSegments + "manifest.json"
private val traceKnownNames = traceSourceNames + TRACE_WRITER_SCRATCH
internal val localSendTraceExportNames =
    traceSegments.map { "local-send-trace-$it" }.toSet() +
        setOf("local-send-trace-native-manifest.json", LOCAL_SEND_TRACE_MANIFEST)

// Accept only the native v1 writer's closed ASCII schema. Unknown fields and versions are omitted,
// rather than allowing a future producer to accidentally export identifiers or free-form errors.
private const val TRACE_UNSIGNED = "(?:0|[1-9][0-9]{0,19})"
private const val TRACE_PROCESS = "[0-9a-f]{32}"
private const val TRACE_MAX_UNSIGNED = "18446744073709551615"
private val traceNumbers = Regex("""(?<=[\[:,])[0-9]+""")
private val traceKinds =
    listOf(
        "worker_start",
        "command",
        "admission_begin",
        "worker_acquired",
        "gate_wait",
        "gate_acquired",
        "admission_committed",
        "admission_failed",
        "admission_duplicate",
        "selection_attempt",
        "gate_busy",
        "storage_error",
        "queue_error",
        "queue_empty",
        "selected",
        "execution_begin",
        "execution_end",
        "completion",
        "pre_engine_sync_begin",
        "pre_engine_sync_end",
        "runtime_send_begin",
        "runtime_send_end",
        "engine_entry",
        "engine_outcome",
        "worker_state",
        "branch_counts",
        "branch_begin",
        "branch_end",
        "recorder_open",
    ).joinToString("|")
private val traceRowPattern =
    Regex(
        """\{"schema":1,"process":"$TRACE_PROCESS","seq":$TRACE_UNSIGNED,"elapsed_ms":$TRACE_UNSIGNED,""" +
            """"kind":"(?:$traceKinds)","owner":$TRACE_UNSIGNED,"operation":$TRACE_UNSIGNED,""" +
            """"values":\[$TRACE_UNSIGNED(?:,$TRACE_UNSIGNED){7}]}""",
    )
private val traceManifestPattern =
    Regex(
        """\{"schema":1,"process":"$TRACE_PROCESS","started_unix_ms":$TRACE_UNSIGNED,""" +
            """"dropped":$TRACE_UNSIGNED,"write_failures":$TRACE_UNSIGNED,"rotations":$TRACE_UNSIGNED,""" +
            """"active_segment":[0-3],"recording":(?:true|false),"last_written_seq":$TRACE_UNSIGNED,""" +
            """"first_written_seq":$TRACE_UNSIGNED,"clear_count":$TRACE_UNSIGNED,"sample_period_ms":250,""" +
            """"live_snapshot_seq":$TRACE_UNSIGNED,"live_snapshot_elapsed_ms":$TRACE_UNSIGNED}""",
    )

/** File metadata is host-generated; no rejected name, path, bytes or exception text can enter it. */
private data class TraceFileSnapshot(
    val bytes: ByteArray? = null,
    val status: String,
    val rejectedRows: Int = 0,
    val partialTailBytes: Int = 0,
    val changed: Boolean = false,
) {
    /** Describes omitted or changing evidence without claiming a complete producer history. */
    fun metadata(name: String): JSONObject =
        JSONObject()
            .put("name", name)
            .put("status", status)
            .put("exported_bytes", bytes?.size ?: 0)
            .put("rejected_rows", rejectedRows)
            .put("partial_tail_bytes", partialTailBytes)
            .put("changed_during_read", changed)
}

/** Directory identity lets a concurrent replacement invalidate bytes already read from its paths. */
private data class TraceDirectoryIdentity(
    val root: Path,
    val directory: Path,
    val rootKey: Any?,
    val directoryKey: Any?,
)

/**
 * Takes a bounded sidecar snapshot only when the user explicitly prepares a diagnostic archive.
 *
 * A missing directory is the old-SDK no-op. Existing traces always carry a host manifest: independent
 * file reads cannot prove atomicity, flush completeness, or the absence of native producer loss.
 * Concurrent rotation may produce duplicate/missing sequence ranges, even if each row is valid.
 */
@Suppress("ReturnCount") // Absent and unsafe directories are distinct closed snapshot outcomes.
internal fun nativeLocalSendTraceExport(marmotRoot: File): Map<String, ByteArray> {
    val started = System.currentTimeMillis()
    val directory = File(marmotRoot, LOCAL_SEND_TRACE_DIRECTORY).toPath()
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
    val identity = traceDirectoryIdentity(marmotRoot.toPath(), directory)
    if (identity == null) return traceSnapshotManifest(started, "unsafe_directory", emptyList(), 0)
    val files = traceSourceNames.map { it to readNativeTraceFile(directory.resolve(it)) }
    var unknown = 0
    var listingFailed = false
    runCatching {
        Files.newDirectoryStream(directory).use { entries ->
            val iterator = entries.iterator()
            var seen = 0
            while (iterator.hasNext() && seen < MAX_TRACE_DIRECTORY_ENTRIES) {
                val name = iterator.next().fileName.toString()
                if (name !in traceKnownNames) unknown += 1
                seen += 1
            }
            listingFailed = iterator.hasNext()
        }
    }.onFailure { listingFailed = true }
    val directoryChanged = traceDirectoryIdentity(marmotRoot.toPath(), directory) != identity
    val status =
        when {
            directoryChanged -> "directory_changed"
            listingFailed -> "directory_listing_incomplete"
            else -> "captured"
        }
    val exported =
        if (directoryChanged) {
            emptyMap()
        } else {
            files
                .mapNotNull { (name, snapshot) ->
                    snapshot.bytes?.let { bytes ->
                        val exportName = if (name == "manifest.json") "native-manifest.json" else name
                        "local-send-trace-$exportName" to bytes
                    }
                }.toMap()
        }
    val scratchPresent = Files.exists(directory.resolve(TRACE_WRITER_SCRATCH), LinkOption.NOFOLLOW_LINKS)
    return exported + traceSnapshotManifest(started, status, files, unknown, scratchPresent)
}

/**
 * Deletes only this recorder's closed file allowlist, leaving its directory and every database alone.
 * The native writer detects its unlinked active inode, drops queued pre-clear rows, and reopens it.
 * A running writer may therefore create fresh post-clear records before this call returns.
 * The writer's manifest scratch stays. Deleting it between the writer's write and rename fails that
 * rename and stops the writer for the process, export never includes it, and the next refresh replaces it.
 */
internal fun clearNativeLocalSendTrace(marmotRoot: File): Boolean {
    val directory = File(marmotRoot, LOCAL_SEND_TRACE_DIRECTORY).toPath()
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return false
    val identity = traceDirectoryIdentity(marmotRoot.toPath(), directory)
    checkNotNull(identity) { "Unable to clear local send trace directory" }
    var removed = false
    var failed = false
    traceSourceNames.forEach { name ->
        runCatching {
            check(traceDirectoryIdentity(marmotRoot.toPath(), directory) == identity)
            val path = directory.resolve(name)
            // Delete a named leaf symlink itself; never follow it or recurse into a named directory.
            check(!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            removed = Files.deleteIfExists(path) || removed
        }.onFailure { failed = true }
    }
    check(!failed) { "Unable to clear every local send trace artifact" }
    return removed
}

/** Rejects symlinked app roots/trace directories while permitting an OS alias above the app root. */
private fun traceDirectoryIdentity(
    root: Path,
    directory: Path,
): TraceDirectoryIdentity? =
    runCatching {
        val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(rootAttributes.isDirectory && attributes.isDirectory)
        TraceDirectoryIdentity(
            root.toRealPath(),
            directory.toRealPath(),
            rootAttributes.fileKey(),
            attributes.fileKey(),
        )
    }.getOrNull()

/** Opens without following the leaf; at most the cap plus one byte is ever read, even during growth. */
@Suppress("CyclomaticComplexMethod", "ReturnCount") // Closed file guards report each unsafe/incomplete outcome.
private fun readNativeTraceFile(path: Path): TraceFileSnapshot {
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return TraceFileSnapshot(status = "absent")
    return try {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile) return TraceFileSnapshot(status = "nonregular_omitted")
        if (before.size() > MAX_LOCAL_SEND_TRACE_BYTES) return TraceFileSnapshot(status = "oversize_omitted")
        val buffer = ByteBuffer.allocate(MAX_LOCAL_SEND_TRACE_BYTES + 1)
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(path, options).use { channel ->
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) break
            }
        }
        if (buffer.position() > MAX_LOCAL_SEND_TRACE_BYTES) return TraceFileSnapshot(status = "oversize_omitted")
        val after =
            runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
        val changed =
            after == null ||
                !after.isRegularFile ||
                before.fileKey() != after.fileKey() ||
                before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() ||
                buffer.position().toLong() != before.size()
        val snapshot = parseNativeTraceBytes(path.fileName.toString(), buffer.array().copyOf(buffer.position()))
        snapshot.copy(changed = changed)
    } catch (_: IOException) {
        TraceFileSnapshot(status = "read_failed")
    } catch (_: SecurityException) {
        TraceFileSnapshot(status = "read_failed")
    } catch (_: UnsupportedOperationException) {
        TraceFileSnapshot(status = "read_failed")
    }
}

/** Keeps complete, closed-schema rows; a bad row and a partial tail are explicit evidence gaps. */
private fun parseNativeTraceBytes(
    name: String,
    bytes: ByteArray,
): TraceFileSnapshot {
    if (name == "manifest.json") {
        val valid = validNativeTraceManifest(bytes.toString(Charsets.US_ASCII))
        return TraceFileSnapshot(if (valid) bytes else null, if (valid) "included" else "invalid_manifest_omitted")
    }
    val output = ByteArrayOutputStream(bytes.size)
    var start = 0
    var rejected = 0
    bytes.forEachIndexed { index, byte ->
        if (byte == '\n'.code.toByte()) {
            val size = index - start
            val row = if (size < MAX_TRACE_ROW_BYTES) String(bytes, start, size, Charsets.US_ASCII) else ""
            if (validNativeTraceContent(row, traceRowPattern)) {
                output.write(bytes, start, size + 1)
            } else {
                rejected += 1
            }
            start = index + 1
        }
    }
    return TraceFileSnapshot(output.toByteArray(), "included", rejected, bytes.size - start)
}

/** Validates the exact schema and unsigned bounds without floating-point coercion of native counters. */
private fun validNativeTraceContent(
    content: String,
    pattern: Regex,
): Boolean =
    pattern.matches(content) &&
        traceNumbers.findAll(content).all { number ->
            number.value.length < TRACE_MAX_UNSIGNED.length || number.value <= TRACE_MAX_UNSIGNED
        }

/** Validates fixed manifest fields and bounded numeric snapshots without accepting arbitrary JSON. */
@Suppress("ReturnCount") // Missing, reordered or malformed structural boundaries fail closed.
private fun validNativeTraceManifest(content: String): Boolean {
    val ownersKey = ",\"owners\":"
    val operationsKey = ",\"operations\":"
    val ownersStart = content.indexOf(ownersKey)
    val operationsStart = content.indexOf(operationsKey)
    if (ownersStart < 0 || operationsStart < ownersStart || !content.endsWith('}')) return false
    val header = content.substring(0, ownersStart) + "}"
    if (!validNativeTraceContent(header, traceManifestPattern)) return false
    val owners = content.substring(ownersStart + ownersKey.length, operationsStart)
    val operations = content.substring(operationsStart + operationsKey.length, content.length - 1)
    return validTraceNumericArrays(owners, valuesPerRow = 16, maximumRows = 64) &&
        validTraceNumericArrays(operations, valuesPerRow = 20, maximumRows = 256)
}

/** Bounds array cardinality before validating each flat numeric row, avoiding recursive JSON parsing. */
@Suppress("ReturnCount") // The empty-array case and invalid outer delimiters are separate guards.
private fun validTraceNumericArrays(
    content: String,
    valuesPerRow: Int,
    maximumRows: Int,
): Boolean {
    if (!content.startsWith('[') || !content.endsWith(']')) return false
    val inner = content.substring(1, content.length - 1)
    if (inner.isEmpty()) return true
    val rows = inner.split("],", limit = maximumRows + 1)
    val rowPattern = Regex("""\[$TRACE_UNSIGNED(?:,$TRACE_UNSIGNED){${valuesPerRow - 1}}]""")
    return rows.size <= maximumRows &&
        rows.withIndex().all { (index, row) ->
            validNativeTraceContent(if (index == rows.lastIndex) row else "$row]", rowPattern)
        }
}

/** Host metadata distinguishes capture limitations from native loss counters in the separate manifest. */
private fun traceSnapshotManifest(
    started: Long,
    status: String,
    files: List<Pair<String, TraceFileSnapshot>>,
    unknown: Int,
    scratchPresent: Boolean = false,
): Map<String, ByteArray> {
    val partial =
        status != "captured" ||
            unknown > 0 ||
            files.any { (name, file) ->
                (file.status != "included" && (name == "manifest.json" || file.status != "absent")) ||
                    file.rejectedRows > 0 ||
                    file.partialTailBytes > 0 ||
                    file.changed
            }
    val manifest =
        JSONObject()
            .put("schema", 1)
            .put("coverage", "best_effort_snapshot")
            .put("status", status)
            .put("partial", partial)
            .put("partial_scope", "host_file_reads_only")
            .put("capture_started_unix_ms", started)
            .put("capture_finished_unix_ms", System.currentTimeMillis())
            .put("atomic_snapshot", false)
            .put("producer_loss", "see_native_manifest_or_unknown")
            .put("unflushed_records", "unknown")
            .put("concurrent_rotation", "possible")
            .put("unknown_files_omitted", unknown)
            .put("writer_scratch_omitted", scratchPresent)
            .put("files", JSONArray(files.map { (name, file) -> file.metadata(name) }))
    return mapOf(LOCAL_SEND_TRACE_MANIFEST to manifest.toString().toByteArray())
}
