package dev.ipf.whitenoise.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PerformanceDiagnosticFileStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** The stored copy matches the closed-schema emitter, including writes queued before export. */
    @Test
    fun activeSessionFlushesExactLinesAndResetsOnNextOptIn() {
        val directory = File(temporaryFolder.root, "diagnostics")
        val store = PerformanceDiagnosticFileStore(directory)
        val emitter =
            PerformanceDiagnosticEmitter(
                available = true,
                appRevision = "app1234",
                mdkRevision = "mdk5678",
                nowMs = { 10L },
                sink = store::append,
                onSessionStart = store::reset,
            )

        assertNull(store.readForExport())
        emitter.start()
        val first = emitter.begin(PerformanceOperation.TEXT_SEND)
        emitter.record(first, PerformancePhase.ACCEPTED, elapsedMs = 2L)
        assertEquals(
            emitter.output.snapshot().joinToString("\n", postfix = "\n"),
            store.readForExport()!!.decodeToString(),
        )

        emitter.stop()
        emitter.start()
        val second = emitter.begin(PerformanceOperation.CHAT_OPEN)
        emitter.record(second, PerformancePhase.FIRST_LOCAL_FRAME, elapsedMs = 3L)
        assertEquals(
            emitter.output.snapshot().joinToString("\n", postfix = "\n"),
            store.readForExport()!!.decodeToString(),
        )
        assertFalse(store.readForExport()!!.decodeToString().contains("op=text_send"))
    }

    /** A noisy session cannot grow the private file past the emitter's event cap. */
    @Test
    fun fileHasSameHardEventLimitAsEmitter() {
        val store = PerformanceDiagnosticFileStore(temporaryFolder.newFolder("diagnostics"))
        store.reset()
        repeat(300) { store.append("schema=2 op=app_start phase=accepted count=$it") }

        val lines =
            store
                .readForExport()!!
                .decodeToString()
                .trimEnd()
                .lines()
        assertEquals(PerformanceDiagnosticEmitter.SESSION_EVENT_LIMIT, lines.size)
        assertTrue(lines.last().endsWith("count=255"))
        assertTrue(store.delete())
        assertNull(store.readForExport())
    }

    /** A replaced private file cannot redirect reads to another path. */
    @Test
    fun symlinkedFileFailsClosed() {
        val directory = temporaryFolder.newFolder("diagnostics")
        val outside = temporaryFolder.newFile("outside").apply { writeText("secret") }
        Files.createSymbolicLink(File(directory, PerformanceDiagnosticFileStore.FILE_NAME).toPath(), outside.toPath())
        val store = PerformanceDiagnosticFileStore(directory)

        assertTrue(runCatching { store.readForExport() }.isFailure)
        assertEquals("secret", outside.readText())
    }

    /** Clearing a failed session without a file permits a later valid session to export. */
    @Test
    fun clearRecoversFromWriteFailureBeforeFileCreation() {
        val blockedDirectory = temporaryFolder.newFile("blocked-diagnostics")
        val store = PerformanceDiagnosticFileStore(blockedDirectory)
        store.append("schema=2 op=app_start phase=accepted")
        assertTrue(runCatching { store.readForExport() }.isFailure)

        assertFalse(store.delete())
        assertNull(store.readForExport())
        assertTrue(blockedDirectory.delete())
        store.reset()
        store.append("schema=2 op=app_start phase=accepted")
        assertEquals("schema=2 op=app_start phase=accepted\n", store.readForExport()!!.decodeToString())
    }
}
