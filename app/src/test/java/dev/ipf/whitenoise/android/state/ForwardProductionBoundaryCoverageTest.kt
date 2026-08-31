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
        assertTrue("priority = AttachmentDownloadPriority.Interactive" in body)
        assertTrue("persistInteractiveIntent = false" in body)
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

    /** Production timeout cleanup cancels only account-scoped memoized source requests for this batch. */
    @Test
    fun productionTransportReleasesTimedOutMaterializationForFreshRetry() {
        val source = appStateSource().readText()
        val body = source.functionBody("startForwardMessages")
        val cleanup = source.functionBody("cancelMemoizedAttachmentDownload")

        assertTrue("val materializationRequests =" in body)
        assertTrue("override fun cancelStalledMaterialization()" in body)
        assertTrue("materializationRequests.forEach(::cancelMemoizedAttachmentDownload)" in body)
        assertTrue("request.accountRef" in cleanup)
        assertTrue("inFlightDownloads[cacheKey]?.takeIf { it.isActive }" in cleanup)
        assertTrue("active?.cancel(" in cleanup)
    }

    /** Session policy bounds preparation and deliberately excludes timeout from automatic retry loops. */
    @Test
    fun forwardingSessionBoundsPreparationWithoutAutomaticRestall() {
        val source = messageForwardingSource().readText()

        assertTrue("withTimeoutOrNull(preparationTimeoutMillis) { materializeMessages() }" in source)
        assertTrue("throw ForwardPreparationTimeoutException()" in source)
        assertTrue("if (snapshot.canAutomaticallyRetry && retryCount < automaticRetryAttempts)" in source)
    }

    @Test
    fun uncertainPublishUsesConvergenceAndTheAppScopeOwnsCompletion() {
        val body = appStateSource().readText().functionBody("startForwardMessages")
        val ownerSource = messageForwardingSource().readText()
        val owner = ownerSource.functionBody("monitor")
        val retry = ownerSource.functionBody("retryAutomatically")

        assertTrue("recentForwardTimeline(targetGroupIdHex, messages.size)" in body)
        assertTrue("val knownMessageIds =" in body)
        assertTrue("ForwardPublishRecoveryEvidence(" in body)
        assertTrue("recoveryEvidence.copy(pendingMessageIdHex = newProjection.messageIdHex)" in body)
        assertTrue("ForwardPublishNotCommittedException(failure)" in body)
        assertTrue("ForwardPublishRecoveryResult.Unavailable" in body)
        assertTrue("it !in evidence.knownMessageIdsBefore" in body)
        assertTrue("retryGroupConvergence(account, targetGroupIdHex)" in body)
        assertTrue("it.messageIdHex == candidate.messageIdHex" in body)
        assertTrue("delivered?.sourceMessageIdHex != null" in body)
        assertTrue("forwardOperationOwner.start(session)" in body)
        assertTrue("session.release()" in body)
        assertTrue("scope.launch" in owner)
        assertTrue("candidate.state.first { !it.isActive }" in owner)
        assertTrue("candidate.retryFailed()" in retry)
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt source file")

    private fun messageForwardingSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/MessageForwarding.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/MessageForwarding.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MessageForwarding.kt source file")
}
