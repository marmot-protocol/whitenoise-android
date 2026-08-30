package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentDownloadProductionWiringTest {
    @Test
    fun visibleMediaPromotesAnAutomaticTransferWhenTheUserTapsIt() {
        val image = source("MediaImageBubbles.kt").normalized()
        val video = source("MediaVideo.kt").normalized()
        val voice = source("MediaVoice.kt").normalized()

        assertEquals(2, occurrences(image, "afterInteractiveRequest()"))
        assertEquals(2, occurrences(video, "afterInteractiveRequest()"))
        assertEquals(1, occurrences(voice, "afterInteractiveRequest()"))
        assertEquals(2, occurrences(image, "persistedAttachmentOpenEffect("))
        assertEquals(2, occurrences(video, "persistedAttachmentOpenEffect("))
        assertEquals(1, occurrences(voice, "persistedAttachmentOpenEffect("))
        assertTrue(occurrences(image, "requestAttachmentOpen(") >= 5)
        assertTrue(occurrences(video, "requestAttachmentOpen(") >= 4)
        assertTrue(occurrences(voice, "requestAttachmentOpen(") >= 2)
    }

    @Test
    fun visualMediaPolicyUsesAMonotonicIntentAndHandlesQueuedCancellation() {
        val image = source("MediaImageBubbles.kt").normalized()
        val video = source("MediaVideo.kt").normalized()
        val voice = source("MediaVoice.kt").normalized()

        assertEquals(2, occurrences(image, "rememberAttachmentMaterializationIntent("))
        assertEquals(2, occurrences(video, "rememberAttachmentMaterializationIntent("))
        assertEquals(1, occurrences(voice, "rememberAttachmentMaterializationIntent("))
        assertEquals(2, occurrences(image, "afterProducerCancellation("))
        assertEquals(2, occurrences(video, "afterProducerCancellation("))
        assertEquals(1, occurrences(voice, "afterProducerCancellation("))
    }

    @Test
    fun clickableImageLoadingProgressHasAnAccessibleName() {
        val image = source("MediaImageBubbles.kt").normalized()

        assertTrue(
            ".semantics { contentDescription = downloadLabel } .clickable( " +
                "onClickLabel = downloadLabel," in image,
        )
    }

    @Test
    fun fileOpenEffectIsNotRestartedByUnrelatedCacheMutations() {
        val file = source("MediaFileBubble.kt").normalized()

        assertTrue(
            "LaunchedEffect( controller, pillKey, reference.sourceEpoch, " +
                "appState.attachmentOpens.revision, lifecycleOwner," in file,
        )
        assertFalse(
            "appState.attachmentOpens.revision, cacheRevision" in file,
        )
    }

    @Test
    fun pendingFileOpenDoesNotReportFailureBeforeDurableCompletion() {
        val file = source("MediaFileBubble.kt").normalized()

        assertFalse("materializeMediaFileOrNotify(" in file)
        assertTrue("materialize = { materializeMediaFile(" in file)
        assertTrue("awaitNextDurableAvailability = { controller.awaitNextAttachmentAvailability(" in file)
        assertTrue("onTerminalFailure = { appState.present(couldntLoadMessage) }" in file)
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
        val sourceDownload =
            controller
                .substringAfter("internal suspend fun downloadAttachmentSource(")
                .substringBefore("internal suspend fun downloadAttachment(")
        assertTrue(
            "File-backed cache misses must retain controller single-flight and transfer-state bookkeeping",
            "onCacheMiss = { requestAttachmentTransfer(" in sourceDownload,
        )
        val retryDecision = worker.indexOf("if (shouldRetryAttachmentDownloadWork(")
        val terminalCleanup = worker.indexOf("intentStore.setInteractive(request, interactive = false)", retryDecision)
        val terminalFailure = worker.indexOf("Result.failure()", terminalCleanup)
        assertTrue(
            "A terminal worker failure must clear persisted interactive priority",
            retryDecision >= 0 && terminalCleanup > retryDecision && terminalFailure > terminalCleanup,
        )
    }

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
