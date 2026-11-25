package dev.ipf.whitenoise.android.diagnostics

import dev.ipf.whitenoise.android.chatsControllerSource
import dev.ipf.whitenoise.android.conversationControllerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PerformanceLogReleaseSafetyTest {
    @Test
    fun onlyTypedEmitterOwnsTheWNPerfTag() {
        val sourceRoot = sourceDirectory()
        val owners =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains("\"WNPerf\"") }
                .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
                .toList()

        assertEquals(listOf("diagnostics/PerformanceDiagnostics.kt"), owners)
    }

    /** Release journey logs, including every split part of the disk cache, never carry known dynamic payloads. */
    @Test
    fun coveredJourneyLogsDoNotExposeKnownDynamicPayloadShapesInRelease() {
        // This pins payload shapes removed by this migration. The exhaustive
        // WNPerf ownership boundary is enforced separately above.
        val controllers = chatsControllerSource() + "\n" + conversationControllerSource()
        val appState = source("state/AppState.kt")
        val diskCache =
            listOf("DiskByteCache.kt", "DiskByteCacheSupport.kt").joinToString("\n") { source("media/$it") }
        val updateWorker = source("updates/AppUpdateWorker.kt")
        val foregroundService = source("notifications/NotificationStreamForegroundService.kt")
        val attachmentWorker = source("state/AttachmentDownloadWorker.kt")
        val mediaFiles = source("ui/conversation/media/MediaFileAccess.kt")
        val mediaImages = source("ui/conversation/media/MediaImageBubbles.kt")
        val mediaVoice = source("ui/conversation/media/MediaVoice.kt")
        val mediaVideo =
            listOf("MediaVideo.kt", "MediaVideoGridTile.kt", "MediaVideoCache.kt", "MediaVideoViewer.kt")
                .joinToString("\n") { source("ui/conversation/media/$it") }

        listOf(
            "detail=\${throwable.message}",
            "media upload failed for \${group.groupIdHex.take(8)}",
            "retryFailedSend failed for \${group.groupIdHex.take(8)}",
        ).forEach { denied -> assertFalse(denied, controllers.contains(denied)) }
        listOf(
            "host=\$host",
            "operation failed: \${error.javaClass.simpleName}",
        ).forEach { denied -> assertFalse(denied, appState.contains(denied)) }
        assertFalse(diskCache.contains("\${file.name}"))
        assertFalse(updateWorker.substringAfter("private fun logRefreshFailure()").contains("lastAttemptErrorReport"))
        assertTrue(foregroundService.contains("if (BuildConfig.DEBUG) Log.e(\"DMForegroundSvc\""))
        assertFalse(foregroundService.contains("reason=\$failureClass"))
        listOf(attachmentWorker, mediaFiles, mediaImages, mediaVoice, mediaVideo).forEach { source ->
            assertFalse(source.contains("messageIdHex.take("))
            assertFalse(source.contains("msg=\${"))
        }
    }

    private fun source(relativePath: String): String = File(sourceDirectory(), relativePath).readText()

    private fun sourceDirectory(): File =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android"),
            File("app/src/main/java/dev/ipf/whitenoise/android"),
        ).firstOrNull(File::isDirectory) ?: error("Missing Android source directory")
}
