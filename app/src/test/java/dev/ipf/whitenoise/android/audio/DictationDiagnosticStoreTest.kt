package dev.ipf.whitenoise.android.audio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DictationDiagnosticStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun schemaDropsContentIdentifiersAndFreeFormValues() {
        val fields =
            requireNotNull(
                DictationDiagnosticSchema.fields(
                    "event=failure_recovery session=2 failure=TimedOut has_text=true " +
                        "transcript=PRIVATE_WORDS account=PRIVATE_ACCOUNT reason=PRIVATE_REASON " +
                        "provider=PRIVATE_PROVIDER type=PRIVATE_CLASS",
                ),
            )
        assertEquals("TimedOut", fields["failure"])
        assertEquals(true, fields["has_text"])
        assertEquals("other", fields["type"])
        assertFalse(fields.toString().contains("PRIVATE"))
        assertNull(DictationDiagnosticSchema.fields("event=PRIVATE_EVENT reason=TimedOut"))
        val captureFailure =
            requireNotNull(DictationDiagnosticSchema.fields("event=caller_audio_closed reason=read=-3"))
        assertEquals("read_failed", captureFailure["reason"])
        assertEquals(-3, captureFailure["read_code"])
    }

    @Test
    fun emittedRetryAndLifecycleFieldsSurviveTheClosedSchema() {
        val examples =
            mapOf(
                "event=recognizer_restart_scheduled reason=provider_disconnected retry=2" to
                    mapOf("reason" to "provider_disconnected", "retry" to 2L),
                "event=foreground_service_start requested=true" to
                    mapOf("requested" to true),
                "event=retry path=retained_transcript" to
                    mapOf("path" to "retained_transcript"),
                "event=completion_action reason=already_finishing" to
                    mapOf("reason" to "already_finishing"),
                "event=caller_audio_retry_exhausted attempts=3 action=retain" to
                    mapOf("attempts" to 3L, "action" to "retain"),
                "event=caller_audio_silence_acknowledged action=advance" to
                    mapOf("action" to "advance"),
                "event=silence_check_complete silence_ms=1000 speech_seen=false" to
                    mapOf("silence_ms" to 1000L, "speech_seen" to false),
                "event=target_validation source=draft_only" to
                    mapOf("source" to "draft_only"),
                "event=app_record_audio_permission granted=true" to
                    mapOf("granted" to true),
            )
        listOf("result", "permission", "provider_disconnected", "no_speech_advanced").forEach { reason ->
            val parsed =
                requireNotNull(DictationDiagnosticSchema.parse("event=recognizer_restart_scheduled reason=$reason"))
            assertEquals(reason, parsed.fields["reason"])
            assertEquals(0L, parsed.filteredFields)
        }
        assertEquals("local", event("event=target_validation source=local")["source"])
        examples.forEach { (event, expected) ->
            val parsed = requireNotNull(DictationDiagnosticSchema.parse(event))
            expected.forEach { (key, value) -> assertEquals(event, value, parsed.fields[key]) }
            assertEquals(event, 0L, parsed.filteredFields)
        }
    }

    @Test
    fun filteredAndDroppedCountsResetOnlyAfterSuccessfulClear() {
        val store = DictationDiagnosticStore(folder.newFolder(), "abcdef012")
        DictationDiagnosticRecorder(store).use { recorder ->
            recorder.setEnabled(true)
            recorder.record("event=session_started transcript=PRIVATE")
            recorder.record("event=PRIVATE_UNKNOWN")
            val before = recorder.snapshot().values.joinToString { it.decodeToString() }
            assertFalse(before.contains("PRIVATE"))
            val manifest = JSONObject(recorder.snapshot().getValue("dictation-manifest.json").decodeToString())
            assertEquals(1L, manifest.getLong("filtered_fields_in_process"))
            assertEquals(1L, manifest.getLong("dropped_in_process"))
            assertTrue(recorder.clear())
            val cleared = JSONObject(recorder.snapshot().getValue("dictation-manifest.json").decodeToString())
            assertEquals(0L, cleared.getLong("filtered_fields_in_process"))
            assertEquals(0L, cleared.getLong("dropped_in_process"))
        }
    }

    @Test
    fun corruptStoreProducesUnavailableManifestAndCanBeCleared() {
        val directory = folder.newFolder()
        File(directory, "dictation-current.jsonl").writeText("PRIVATE_CORRUPTION")
        DictationDiagnosticRecorder(DictationDiagnosticStore(directory, "abcdef012")).use { recorder ->
            val snapshot = recorder.snapshot()
            assertEquals(setOf("dictation-manifest.json"), snapshot.keys)
            val manifest = snapshot.values.single().decodeToString()
            assertFalse(manifest.contains("PRIVATE"))
            assertEquals("store_failed", JSONObject(manifest).getString("snapshot_failure"))
            assertTrue(recorder.clear())
            val cleared =
                JSONObject(
                    recorder
                        .snapshot()
                        .values
                        .single()
                        .decodeToString(),
                )
            assertEquals("bounded_local_history", cleared.getString("coverage"))
        }
    }

    @Test
    fun restartRetainsHistoryAndDistinctProcessCorrelation() {
        val directory = folder.newFolder()
        val first = DictationDiagnosticStore(directory, "abcdef012")
        first.append(event("event=session_started session=1"))
        val second = DictationDiagnosticStore(directory, "abcdef013")
        second.append(event("event=session_finished session=1 outcome=completed"))
        val lines =
            second
                .snapshot(false, 0)
                .getValue("dictation-current.jsonl")
                .decodeToString()
                .trim()
                .lines()
        assertEquals(2, lines.size)
        assertNotEquals(JSONObject(lines[0]).getString("process"), JSONObject(lines[1]).getString("process"))
        assertEquals("abcdef013", JSONObject(lines[1]).getString("app_revision"))
        val manifest = JSONObject(second.snapshot(false, 0).getValue("dictation-manifest.json").decodeToString())
        assertEquals(false, manifest.getBoolean("collection_enabled"))
    }

    @Test
    fun rotationBoundsBytesAndClearRemovesBothFiles() {
        val directory = folder.newFolder()
        val store = DictationDiagnosticStore(directory, "abcdef012", maxBytes = 500)
        repeat(40) { store.append(event("event=caller_audio_progress bytes=$it")) }
        val files = store.snapshot(true, 3).filterKeys { it.endsWith(".jsonl") }
        assertEquals(2, files.size)
        assertTrue(files.values.all { it.size <= 500 })
        val manifest = JSONObject(store.snapshot(true, 3).getValue("dictation-manifest.json").decodeToString())
        assertEquals(3, manifest.getInt("dropped_in_process"))
        assertTrue(store.clear())
        assertFalse(store.clear())
        assertTrue(store.snapshot(true, 0).keys.none { it.endsWith(".jsonl") })
    }

    @Test
    fun expiredHistoryIsRemovedBeforeExport() {
        val directory = folder.newFolder()
        var now = System.currentTimeMillis()
        val store = DictationDiagnosticStore(directory, "abcdef012", nowMillis = { now }, retentionMillis = 1000)
        store.append(event("event=session_started session=1"))
        now += 2000
        assertTrue(File(directory, "dictation-current.jsonl").setLastModified(now))
        assertTrue(store.snapshot(false, 0).keys.none { it.endsWith(".jsonl") })
    }

    @Test(expected = IOException::class)
    fun exportRejectsSymlinkedDiagnosticFile() {
        val directory = folder.newFolder()
        val external = folder.newFile().apply { writeText("PRIVATE") }
        Files.createSymbolicLink(File(directory, "dictation-current.jsonl").toPath(), external.toPath())
        DictationDiagnosticStore(directory, "abcdef012").snapshot(false, 0)
    }

    @Test
    fun continuedCollectionDoesNotExtendOldRecordsRetention() {
        val directory = folder.newFolder()
        var now = System.currentTimeMillis()
        val store = DictationDiagnosticStore(directory, "abcdef012", nowMillis = { now }, retentionMillis = 1000)
        store.append(event("event=session_started session=1"))
        now += 500
        store.append(event("event=session_started session=2"))
        now += 600
        store.append(event("event=session_started session=3"))
        val lines =
            store
                .snapshot(true, 0)
                .getValue("dictation-current.jsonl")
                .decodeToString()
                .trim()
                .lines()
        assertEquals(1, lines.size)
        assertEquals(3, JSONObject(lines.single()).getInt("session"))
    }

    @Test
    fun collectionStartsDisabledAndRevocationPreservesExistingHistory() {
        val store = DictationDiagnosticStore(folder.newFolder(), "abcdef012")
        DictationDiagnosticRecorder(store).use { recorder ->
            recorder.record("event=session_started session=1")
            assertTrue(recorder.snapshot().keys.none { it.endsWith(".jsonl") })
            recorder.setEnabled(true)
            recorder.record("event=session_started session=2")
            val recorded = recorder.snapshot().getValue("dictation-current.jsonl").decodeToString()
            recorder.setEnabled(false)
            recorder.record("event=session_started session=3")
            assertEquals(recorded, recorder.snapshot().getValue("dictation-current.jsonl").decodeToString())
            assertTrue(recorder.clear())
        }
    }

    @Test
    fun writerFailureIsCountedInExportWithoutCrashingTheProducer() {
        val store = DictationDiagnosticStore(folder.newFolder(), "abcdef012", maxBytes = 32)
        DictationDiagnosticRecorder(store).use { recorder ->
            recorder.setEnabled(true)
            recorder.record("event=session_started session=1")
            val manifest = JSONObject(recorder.snapshot().getValue("dictation-manifest.json").decodeToString())
            assertEquals(1, manifest.getInt("dropped_in_process"))
            assertEquals(0, manifest.getInt("files"))
        }
    }

    @Test
    fun rejectedEventIsCountedWithoutPersistingItsContent() {
        val store = DictationDiagnosticStore(folder.newFolder(), "abcdef012")
        DictationDiagnosticRecorder(store).use { recorder ->
            recorder.setEnabled(true)
            recorder.record("event=PRIVATE_UNKNOWN transcript=PRIVATE_SPEECH")
            val snapshot = recorder.snapshot()
            val manifest = snapshot.getValue("dictation-manifest.json").decodeToString()
            assertEquals(1, JSONObject(manifest).getInt("dropped_in_process"))
            assertTrue(snapshot.keys.none { it.endsWith(".jsonl") })
            assertFalse(manifest.contains("PRIVATE"))
        }
    }

    @Test
    fun saturatedExportReturnsExplicitUnavailableManifest() {
        unavailableSnapshot(queueCapacity = 1, timeoutSeconds = 5, saturate = true, reason = "queue_full")
    }

    @Test
    fun blockedExportReturnsExplicitTimeoutManifest() {
        unavailableSnapshot(queueCapacity = 2, timeoutSeconds = 0, saturate = false, reason = "barrier_timeout")
    }

    private fun unavailableSnapshot(
        queueCapacity: Int,
        timeoutSeconds: Long,
        saturate: Boolean,
        reason: String,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store =
            DictationDiagnosticStore(folder.newFolder(), "abcdef012", nowMillis = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                System.currentTimeMillis()
            })
        DictationDiagnosticRecorder(store, queueCapacity, timeoutSeconds).use { recorder ->
            try {
                recorder.setEnabled(true)
                recorder.record("event=session_started session=1")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (saturate) recorder.record("event=session_started session=2")
                val snapshot = recorder.snapshot()
                val manifest = JSONObject(snapshot.getValue("dictation-manifest.json").decodeToString())
                assertEquals(setOf("dictation-manifest.json"), snapshot.keys)
                assertEquals("snapshot_unavailable", manifest.getString("coverage"))
                assertEquals(reason, manifest.getString("snapshot_failure"))
                assertEquals(0, manifest.getInt("files"))
            } finally {
                release.countDown()
            }
        }
    }

    private fun event(value: String): Map<String, Any> = requireNotNull(DictationDiagnosticSchema.fields(value))
}
