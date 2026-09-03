package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentDownloadProductionWiringTest {
    /** APK installer completion is owned by the app shell, not the originating bubble. */
    @Test
    fun installerHandoffOwnerOutlivesTheOriginatingFileBubble() {
        val bubble = source("MediaFileBubble.kt").normalized()
        val shell = projectSource("ui/navigation/MainShell.kt").normalized()
        val appState = projectSource("state/AppState.kt").normalized()
        val worker = projectSource("state/AttachmentDownloadWorker.kt").normalized()

        assertTrue("requestAttachmentInstallerHandoff(" in bubble)
        assertTrue(
            "onPersistenceFailure = { appState.present(couldntOpenMessage, copyable = true) }" in bubble,
        )
        assertTrue("attachmentInstallerHandoffEffect(appState" in shell)
        assertTrue("EncryptedAttachmentInstallerHandoffRecordStore.create(appContext)" in appState)
        assertTrue("EncryptedAttachmentInstallerHandoffRecordStore.create(context)" in worker)
    }

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

    /** Guards file-open work against restarting for unrelated cache revisions. */
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

    /** Guards that durable file-open intent survives until background availability is terminal. */
    @Test
    fun pendingFileOpenDoesNotReportFailureBeforeDurableCompletion() {
        val file = source("MediaFileBubble.kt").normalized()

        assertFalse("materializeMediaFileOrNotify(" in file)
        assertTrue("materialize = { materializeMediaFile(" in file)
        assertTrue("awaitNextDurableAvailability = { controller.awaitNextAttachmentAvailability(" in file)
        assertTrue("onTerminalFailure = { appState.present(couldntLoadMessage) }" in file)
    }

    /** Guards foreground enqueueing, source single-flight, and terminal durable cleanup wiring. */
    @Test
    fun everyInteractiveControllerDownloadHasDurableTerminalCleanup() {
        val controller = projectSource("state/Controllers.kt").normalized()
        val sourceController = projectSource("state/AttachmentControllerCache.kt").normalized()
        val appState = projectSource("state/AppState.kt").normalized()
        val worker = projectSource("state/AttachmentDownloadWorker.kt").normalized()

        assertTrue(
            "Interactive controller downloads must enqueue durable work before the direct transfer",
            "if (priority == AttachmentDownloadPriority.Interactive) { " +
                "appState.enqueueAttachmentDownload(request, priority) } " +
                "return appState.downloadAttachmentPlaintext(" in controller,
        )
        val sourceDownload =
            sourceController.substringAfter(
                "internal suspend fun ConversationController.downloadAttachmentSource(",
            )
        assertTrue(
            "File-backed cache misses must retain controller single-flight and transfer-state bookkeeping",
            "onCacheMiss = { requestAttachmentTransfer(" in sourceDownload,
        )
        val durableDownload =
            appState
                .substringAfter("internal suspend fun downloadAttachmentForDurableWork(")
                .substringBefore("suspend fun bootstrap()")
        assertTrue(
            "Durable cache hits must close a source lease instead of materializing large byte arrays",
            "downloadAttachmentPlaintextSource(" in durableDownload && ").use { }" in durableDownload,
        )
        val retryDecision = worker.indexOf("if (shouldRetryAttachmentDownloadWork(")
        val terminalCleanup = worker.indexOf("intentStore.setInteractive(request, interactive = false)", retryDecision)
        val terminalFailure = worker.indexOf("Result.failure()", terminalCleanup)
        assertTrue(
            "A terminal worker failure must clear persisted interactive priority",
            retryDecision >= 0 && terminalCleanup > retryDecision && terminalFailure > terminalCleanup,
        )
    }

    /** Guards retained outgoing documents against bypassing transfer and encrypted-cache bookkeeping. */
    @Test
    fun retainedDocumentOpenStillUsesTransferCoordinator() {
        val access = source("MediaFileAccess.kt").normalized()
        val retainedBranch =
            access
                .substringAfter("if (retained != null)")
                .substringBefore("} else {")

        assertTrue(
            "Retained outgoing documents must preserve transfer state and encrypted-cache seeding",
            "requestAttachmentTransfer(" in retainedBranch && "retainedPlaintext = retained" in retainedBranch,
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
