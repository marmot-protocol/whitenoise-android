package dev.ipf.whitenoise.android.state

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class NativeLocalSendTraceExportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Older SDKs never created this sidecar; export and clear do not create it themselves. */
    @Test
    fun absentSidecarIsANoopAndLeavesExistingDataAlone() {
        val root = temporaryFolder.newFolder()
        val database = File(root, "marmot.sqlite").apply { writeText("retained history") }

        assertTrue(nativeLocalSendTraceExport(root).isEmpty())
        assertFalse(clearNativeLocalSendTrace(root))
        assertEquals("retained history", database.readText())
        assertFalse(File(root, LOCAL_SEND_TRACE_DIRECTORY).exists())
    }

    /** A snapshot never labels independently read ring segments as atomic or producer-loss-free. */
    @Test
    fun closedSchemaExportsWithoutChangingTheSourceAndAlwaysStatesCoverageLimits() {
        val root = newTraceRoot()
        val source = traceFile(root, "segment-0.jsonl").apply { writeText(row(9) + row(10)) }
        traceFile(root, "manifest.json").writeText(nativeManifest())

        val entries = nativeLocalSendTraceExport(root)

        assertEquals(row(9) + row(10), entries.getValue("local-send-trace-segment-0.jsonl").decodeToString())
        assertEquals(nativeManifest(), entries.getValue("local-send-trace-native-manifest.json").decodeToString())
        assertEquals(row(9) + row(10), source.readText())
        assertTrue(entries.keys.all { it in localSendTraceExportNames })
        assertTrue(entries.values.all { it.size <= MAX_LOCAL_SEND_TRACE_BYTES })
        val manifest = JSONObject(entries.getValue(LOCAL_SEND_TRACE_MANIFEST).decodeToString())
        assertEquals("best_effort_snapshot", manifest.getString("coverage"))
        assertFalse(manifest.getBoolean("atomic_snapshot"))
        assertFalse(manifest.getBoolean("partial"))
        assertEquals("unknown", manifest.getString("unflushed_records"))
        assertEquals("see_native_manifest_or_unknown", manifest.getString("producer_loss"))
        assertEquals("possible", manifest.getString("concurrent_rotation"))
    }

    /** Worker generations/command boundaries survive export; the writer's atomic scratch is ignored. */
    @Test
    fun workerAndCommandRowsAreKeptWhileKnownWriterScratchDoesNotCreateALossClaim() {
        val root = newTraceRoot()
        val rows =
            row().replace("\"selected\"", "\"worker_start\"") +
                row(2).replace("\"selected\"", "\"command\"") +
                row(3).replace("\"selected\"", "\"branch_counts\"") +
                row(4).replace("\"operation\":2", "\"operation\":18446744073709551615")
        traceFile(root, "segment-0.jsonl").writeText(rows)
        traceFile(root, "manifest.json").writeText(nativeManifest())
        traceFile(root, "manifest.tmp").writeText("PRIVATE_WRITER_SCRATCH")

        val entries = nativeLocalSendTraceExport(root)

        assertEquals(rows, entries.getValue("local-send-trace-segment-0.jsonl").decodeToString())
        val manifest = JSONObject(entries.getValue(LOCAL_SEND_TRACE_MANIFEST).decodeToString())
        assertFalse(manifest.getBoolean("partial"))
        assertEquals(0, manifest.getInt("unknown_files_omitted"))
        assertTrue(manifest.getBoolean("writer_scratch_omitted"))
        assertFalse(entries.values.any { it.decodeToString().contains("PRIVATE") })
    }

    /** Maximum live snapshots remain exportable; nested identifiers, extra fields and excess rows do not. */
    @Test
    fun liveSnapshotManifestRequiresFixedNumericShapesAndCardinality() {
        val root = newTraceRoot()
        val owner = List(16) { "18446744073709551615" }.joinToString(",", "[", "]")
        val operation = List(20) { "18446744073709551615" }.joinToString(",", "[", "]")
        val owners = List(64) { owner }.joinToString(",", "[", "]")
        val operations = List(256) { operation }.joinToString(",", "[", "]")
        val source = traceFile(root, "manifest.json")
        val valid = nativeManifest(owners, operations)
        source.writeText(valid)

        assertEquals(
            valid,
            nativeLocalSendTraceExport(root).getValue("local-send-trace-native-manifest.json").decodeToString(),
        )
        val rejected =
            listOf(
                nativeManifest(List(65) { owner }.joinToString(",", "[", "]"), "[]"),
                nativeManifest("[]", List(257) { operation }.joinToString(",", "[", "]")),
                nativeManifest("[[0]]", "[]"),
                nativeManifest("[]", "[[0]]"),
                nativeManifest("[{\"PRIVATE\":1}]", "[]"),
                nativeManifest("[]", "[\"PRIVATE\"]"),
                valid.replace("\"sample_period_ms\":250", "\"sample_period_ms\":250,\"PRIVATE\":1"),
            )
        rejected.forEach { bytes ->
            source.writeText(bytes)
            val entries = nativeLocalSendTraceExport(root)
            assertFalse(entries.containsKey("local-send-trace-native-manifest.json"))
            assertEquals("invalid_manifest_omitted", fileMetadata(entries, "manifest.json").getString("status"))
            assertFalse(entries.values.any { it.decodeToString().contains("PRIVATE") })
        }
    }

    /** A writer may stop halfway through a row; valid earlier rows survive with an explicit gap. */
    @Test
    fun partialTailAndUnknownFieldsAreOmittedWithoutLeakingRejectedContent() {
        val root = newTraceRoot()
        val invalid = row(2).trimEnd().dropLast(1) + ",\"message\":\"PRIVATE\"}\n"
        val tail = "{\"PRIVATE_PARTIAL\":"
        traceFile(root, "segment-0.jsonl").writeText(row() + invalid + tail)
        traceFile(root, "manifest.json").writeText(nativeManifest())

        val entries = nativeLocalSendTraceExport(root)

        assertEquals(row(), entries.getValue("local-send-trace-segment-0.jsonl").decodeToString())
        val file = fileMetadata(entries, "segment-0.jsonl")
        assertEquals(1, file.getInt("rejected_rows"))
        assertEquals(tail.toByteArray().size, file.getInt("partial_tail_bytes"))
        assertTrue(JSONObject(entries.getValue(LOCAL_SEND_TRACE_MANIFEST).decodeToString()).getBoolean("partial"))
        assertFalse(entries.values.any { it.decodeToString().contains("PRIVATE") })
    }

    /** Future versions, duplicate keys, free-form kinds, oversized rows and u64 overflow fail closed. */
    @Test
    fun incompatibleRowsAndManifestCannotMasqueradeAsNativeV1() {
        val root = newTraceRoot()
        val rejected =
            listOf(
                row().replace("\"schema\":1", "\"schema\":2"),
                row().replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                row().replace("\"selected\"", "\"PRIVATE_KIND\""),
                row().replace("\"seq\":1", "\"seq\":18446744073709551616"),
                row().replace("\"seq\":1", "\"seq\":1.0"),
                "x".repeat(512) + "\n",
            )
        traceFile(root, "segment-0.jsonl").writeText(rejected.joinToString("") + row(3))
        traceFile(root, "manifest.json").writeText(nativeManifest().dropLast(1) + ",\"PRIVATE\":true}")

        val entries = nativeLocalSendTraceExport(root)

        assertEquals(row(3), entries.getValue("local-send-trace-segment-0.jsonl").decodeToString())
        assertEquals(rejected.size, fileMetadata(entries, "segment-0.jsonl").getInt("rejected_rows"))
        assertEquals("invalid_manifest_omitted", fileMetadata(entries, "manifest.json").getString("status"))
        assertFalse(entries.containsKey("local-send-trace-native-manifest.json"))
        assertFalse(entries.values.any { it.decodeToString().contains("PRIVATE") })
    }

    /** Neither an oversized segment nor a manifest can evade the per-entry read/copy bound. */
    @Test
    fun oversizeAndUnknownArtifactsAreNotReadOrNamedInTheArchive() {
        val root = newTraceRoot()
        traceFile(root, "segment-0.jsonl").writeBytes(ByteArray(MAX_LOCAL_SEND_TRACE_BYTES + 1) { 'x'.code.toByte() })
        traceFile(root, "manifest.json").writeBytes(ByteArray(MAX_LOCAL_SEND_TRACE_BYTES + 1))
        traceFile(root, "PRIVATE-unknown-name").writeText("PRIVATE_CONTENT")
        traceFile(root, "segment-1.jsonl").writeText(row())

        val entries = nativeLocalSendTraceExport(root)

        assertEquals("oversize_omitted", fileMetadata(entries, "segment-0.jsonl").getString("status"))
        assertEquals("oversize_omitted", fileMetadata(entries, "manifest.json").getString("status"))
        assertEquals(row(), entries.getValue("local-send-trace-segment-1.jsonl").decodeToString())
        val manifest = JSONObject(entries.getValue(LOCAL_SEND_TRACE_MANIFEST).decodeToString())
        assertEquals(1, manifest.getInt("unknown_files_omitted"))
        assertFalse(entries.values.any { it.decodeToString().contains("PRIVATE") })
    }

    /** Exact-name symlinks are never read; clearing unlinks only the leaf and preserves its target. */
    @Test
    fun leafSymlinkCannotExportOrDeleteAnUnrelatedFile() {
        val root = newTraceRoot()
        val outside = temporaryFolder.newFile().apply { writeText("PRIVATE_OUTSIDE") }
        val link = traceFile(root, "segment-0.jsonl")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        val entries = nativeLocalSendTraceExport(root)

        assertEquals("nonregular_omitted", fileMetadata(entries, "segment-0.jsonl").getString("status"))
        assertFalse(entries.values.any { it.decodeToString().contains("PRIVATE") })
        assertTrue(clearNativeLocalSendTrace(root))
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("PRIVATE_OUTSIDE", outside.readText())
    }

    /** Intermediate symlinks and a symlinked Marmot root fail closed for both reads and deletion. */
    @Test
    fun directorySymlinksCannotEscapeTheConfiguredAppRoot() {
        val root = temporaryFolder.newFolder()
        val outside = temporaryFolder.newFolder()
        val outsideFile = File(outside, "segment-0.jsonl").apply { writeText(row()) }
        Files.createSymbolicLink(File(root, LOCAL_SEND_TRACE_DIRECTORY).toPath(), outside.toPath())
        val rootAlias = File(temporaryFolder.root, "root-alias")
        Files.createSymbolicLink(rootAlias.toPath(), root.toPath())

        listOf(root, rootAlias).forEach { unsafeRoot ->
            val entries = nativeLocalSendTraceExport(unsafeRoot)
            assertEquals(setOf(LOCAL_SEND_TRACE_MANIFEST), entries.keys)
            assertEquals("unsafe_directory", JSONObject(entries.values.single().decodeToString()).getString("status"))
            assertTrue(runCatching { clearNativeLocalSendTrace(unsafeRoot) }.isFailure)
        }
        assertEquals(row(), outsideFile.readText())
    }

    /** Clear never recurses into lookalike directories or deletes unrelated files, even on failure. */
    @Test
    fun clearAttemptsEveryAllowedFileAndRetainsDirectoryDatabasesAndUnknownFiles() {
        val root = newTraceRoot()
        val directory = File(root, LOCAL_SEND_TRACE_DIRECTORY)
        val blocked = traceFile(root, "segment-0.jsonl").apply { mkdir() }
        val retained = File(blocked, "history.sqlite").apply { writeText("history") }
        val removable = traceFile(root, "segment-1.jsonl").apply { writeText(row()) }
        val temporary = traceFile(root, "manifest.tmp").apply { writeText("scratch") }
        val unknown = traceFile(root, "other.sqlite").apply { writeText("other history") }
        traceFile(root, "manifest.json").writeText(nativeManifest())

        assertTrue(runCatching { clearNativeLocalSendTrace(root) }.isFailure)

        assertTrue(directory.isDirectory)
        assertFalse(removable.exists())
        assertFalse(temporary.exists())
        assertFalse(traceFile(root, "manifest.json").exists())
        assertEquals("history", retained.readText())
        assertEquals("other history", unknown.readText())
    }

    /** Native rotates only four known filenames; every exported entry still respects the single-file cap. */
    @Test
    fun fullRingStaysBoundedAndDirectoryEnumerationHasAnExplicitCutoff() {
        val root = newTraceRoot()
        val repeated = row().repeat(MAX_LOCAL_SEND_TRACE_BYTES / row().toByteArray().size)
        (0..3).forEach { traceFile(root, "segment-$it.jsonl").writeText(repeated) }
        traceFile(root, "manifest.json").writeText(nativeManifest())
        repeat(40) { traceFile(root, "unknown-$it").writeText("unexported") }

        val entries = nativeLocalSendTraceExport(root)

        assertTrue(entries.values.all { it.size <= MAX_LOCAL_SEND_TRACE_BYTES })
        assertEquals(6, entries.size)
        val manifest = JSONObject(entries.getValue(LOCAL_SEND_TRACE_MANIFEST).decodeToString())
        assertEquals("directory_listing_incomplete", manifest.getString("status"))
        assertTrue(manifest.getBoolean("partial"))
        assertTrue(manifest.getInt("unknown_files_omitted") <= 32)
    }

    /** Builds only this test's private sidecar fixture; production snapshot code creates nothing. */
    private fun newTraceRoot(): File = temporaryFolder.newFolder().also { File(it, LOCAL_SEND_TRACE_DIRECTORY).mkdir() }

    /** Resolves a closed fixture leaf under the recorder directory. */
    private fun traceFile(
        root: File,
        name: String,
    ): File = File(File(root, LOCAL_SEND_TRACE_DIRECTORY), name)

    /** Uses a capture-scoped nonce and numeric aliases, never an application identifier. */
    private fun row(sequence: Int = 1): String =
        "{\"schema\":1,\"process\":\"0123456789abcdef0123456789abcdef\",\"seq\":$sequence,\"elapsed_ms\":4," +
            "\"kind\":\"selected\",\"owner\":1,\"operation\":2,\"values\":[1,0,0,0,0,0,0,0]}\n"

    /** Matches the native v1 fixed-order manifest, including actual loss and clear counters. */
    private fun nativeManifest(
        owners: String = "[]",
        operations: String = "[]",
    ): String =
        "{\"schema\":1,\"process\":\"0123456789abcdef0123456789abcdef\",\"started_unix_ms\":1," +
            "\"dropped\":0,\"write_failures\":0,\"rotations\":0,\"active_segment\":0,\"recording\":true," +
            "\"last_written_seq\":10,\"first_written_seq\":9,\"clear_count\":0,\"sample_period_ms\":250," +
            "\"live_snapshot_seq\":10,\"live_snapshot_elapsed_ms\":4,\"owners\":$owners,\"operations\":$operations}"

    /** Selects only a known file's host metadata, keeping test assertions independent of JSON key order. */
    private fun fileMetadata(
        entries: Map<String, ByteArray>,
        name: String,
    ): JSONObject {
        val files = JSONObject(entries.getValue(LOCAL_SEND_TRACE_MANIFEST).decodeToString()).getJSONArray("files")
        return (0 until files.length()).map { files.getJSONObject(it) }.single { it.getString("name") == name }
    }
}
