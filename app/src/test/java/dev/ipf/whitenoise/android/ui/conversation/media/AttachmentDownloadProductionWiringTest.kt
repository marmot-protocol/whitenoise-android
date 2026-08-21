package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentDownloadProductionWiringTest {
    @Test
    fun visibleMediaPromotesAnAutomaticTransferWhenTheUserTapsIt() {
        val image = source("MediaImageBubbles.kt").normalized()
        val video = source("MediaVideo.kt").normalized()
        val voice = source("MediaVoice.kt").normalized()

        assertEquals(2, keyedEffects(image, "interactiveDownloadRequested"))
        assertEquals(2, keyedEffects(video, "interactiveDownloadRequested"))
        assertEquals(1, keyedEffects(voice, "interactiveDownloadRequested"))
        assertEquals(2, occurrences(image, "persistedAttachmentOpenEffect("))
        assertEquals(2, occurrences(video, "persistedAttachmentOpenEffect("))
        assertEquals(1, occurrences(voice, "persistedAttachmentOpenEffect("))
        assertTrue(occurrences(image, "requestAttachmentOpen(") >= 5)
        assertTrue(occurrences(video, "requestAttachmentOpen(") >= 4)
        assertTrue(occurrences(voice, "requestAttachmentOpen(") >= 2)
    }

    @Test
    fun restartReevaluatesEveryLatchedVisualMediaDownload() {
        val image = source("MediaImageBubbles.kt").normalized()
        val video = source("MediaVideo.kt").normalized()
        val voice = source("MediaVoice.kt").normalized()

        assertEquals(2, keyedRemembers(image, "automaticDownloadsPaused"))
        assertEquals(2, keyedRemembers(video, "automaticDownloadsPaused"))
        assertEquals(1, keyedRemembers(voice, "automaticDownloadsPaused"))
    }

    @Test
    fun everyInteractiveControllerDownloadHasDurableTerminalCleanup() {
        val controller = projectSource("state/Controllers.kt").normalized()
        val worker = projectSource("state/AttachmentDownloadWorker.kt").normalized()

        assertTrue(
            "Interactive controller downloads must enqueue durable work before the direct transfer",
            "if (priority == AttachmentDownloadPriority.Interactive) { " +
                "appState.enqueueAttachmentDownload(request, priority) } " +
                "return appState.downloadAttachmentPlaintext(" in controller,
        )
        val retryDecision = worker.indexOf("if (shouldRetryAttachmentDownloadWork(")
        val terminalCleanup = worker.indexOf("intentStore.setInteractive(request, interactive = false)", retryDecision)
        val terminalFailure = worker.indexOf("Result.failure()", terminalCleanup)
        assertTrue(
            "A terminal worker failure must clear persisted interactive priority",
            retryDecision >= 0 && terminalCleanup > retryDecision && terminalFailure > terminalCleanup,
        )
    }

    private fun keyedEffects(
        source: String,
        key: String,
    ): Int = Regex("LaunchedEffect\\([^)]*\\b$key\\b[^)]*\\)").findAll(source).count()

    private fun keyedRemembers(
        source: String,
        key: String,
    ): Int = Regex("remember\\([^)]*\\b$key\\b[^)]*\\)").findAll(source).count()

    private fun occurrences(
        source: String,
        needle: String,
    ): Int = source.windowed(needle.length).count { it == needle }

    private fun source(fileName: String): String = projectSource("ui/conversation/media/$fileName")

    private fun projectSource(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing $relativePath source file")

    private fun String.normalized(): String = replace(Regex("\\s+"), " ")
}
