package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Shares host memory without requiring a native handle, with bounded explicit read grants. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupDiagnosticShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A recipient receives exactly the selected text file; previous copies remain immutable. */
    @Test fun exportContainsOnlyTheRequestedSnapshotAndKeepsThreeCopies() {
        val first = "schema=whitenoise-startup-v1\nstage=NATIVE_CONSTRUCT"
        val intent = createStartupDiagnosticShareIntent(context, first)
        val uri = requireNotNull(intent.clipData).getItemAt(0).uri
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(
            first,
            context.contentResolver
                .openInputStream(uri)!!
                .bufferedReader()
                .use { it.readText() },
        )
        repeat(4) { createStartupDiagnosticShareIntent(context, "snapshot=$it") }
        val files = File(context.cacheDir, "startup_diagnostics").listFiles().orEmpty()
        assertEquals(3, files.count { it.name.startsWith("startup-") })
    }

    /** Oversized data is rejected before creating an export artifact. */
    @Test fun exportRejectsOversizedUtf8Report() {
        val failure = runCatching { createStartupDiagnosticShareIntent(context, "é".repeat(MAX_STARTUP_REPORT_BYTES)) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    /** A substituted cache directory cannot redirect the writer or the share grant. */
    @Test fun exportRejectsSymlinkedDirectory() {
        val directory = File(context.cacheDir, "startup_diagnostics")
        directory.deleteRecursively()
        val external = Files.createTempDirectory("startup-unrelated-").toFile()
        try {
            Files.createSymbolicLink(directory.toPath(), external.toPath())
            assertTrue(runCatching { createStartupDiagnosticShareIntent(context, "safe") }.isFailure)
            assertTrue(external.listFiles().orEmpty().isEmpty())
        } finally {
            Files.deleteIfExists(directory.toPath())
            external.deleteRecursively()
        }
    }
}
