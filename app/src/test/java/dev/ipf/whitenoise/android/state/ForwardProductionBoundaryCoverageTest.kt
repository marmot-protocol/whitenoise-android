package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForwardProductionBoundaryCoverageTest {
    @Test
    fun productionTransportMaterializesFromSourceAndPublishesOnlyDestinationReferences() {
        val body = appStateSource().readText().functionBody("startForwardMessages")

        assertTrue("resolveAttachmentReference(request)" in body)
        assertTrue("downloadAttachmentPlaintext(request, reference)" in body)
        assertTrue("uploadMedia(" in body)
        assertTrue("send = false" in body)
        assertTrue("uploadedReferences[messageIndex]" in body)
        assertTrue("sendMediaAttachments(" in body)
        assertFalse(
            Regex("sendMediaAttachments\\([^)]*source\\.reference", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body),
        )
    }

    @Test
    fun productionTransportGuardsAccountEpochAndSerializesEachDestinationBatch() {
        val body = appStateSource().readText().functionBody("startForwardMessages")

        assertTrue("mediaUploadSessionEpoch()" in body)
        assertTrue("requireCurrentAccount()" in body)
        assertTrue("withGroupCommitLock(account, targetGroupIdHex)" in body)
        assertTrue("for (messageIndex in startIndex until messages.size)" in body)
        assertTrue("onMessagePublished(messageIndex)" in body)
    }

    @Test
    fun uncertainPublishUsesConvergenceAndTheAppScopeOwnsCompletion() {
        val body = appStateSource().readText().functionBody("startForwardMessages")

        assertTrue("forwardProjectionRecords(targetGroupIdHex, message, references)" in body)
        assertTrue("pendingMessageIdHex = newProjection.messageIdHex" in body)
        assertTrue("ForwardPublishNotCommittedException(failure)" in body)
        assertTrue("retryGroupConvergence(account, targetGroupIdHex)" in body)
        assertTrue("it.messageIdHex == pendingMessageIdHex" in body)
        assertTrue("delivered.sourceMessageIdHex != null" in body)
        assertTrue("mutationsScope.launch" in body)
        assertTrue("session.state.first { !it.isActive }" in body)
        assertTrue("session.release()" in body)
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt source file")
}
