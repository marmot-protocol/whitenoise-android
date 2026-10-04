package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForwardProductionBoundaryCoverageTest {
    @Test
    fun productionTransportMaterializesFromSourceAndPublishesOnlyDestinationReferences() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")

        assertTrue("resolveAttachmentReference(request)" in body)
        assertTrue("materializeAttachmentPlaintextIsolated(request, reference, diagnostics)" in body)
        assertTrue("uploadMedia(" in body)
        assertTrue("send = false" in body)
        assertTrue("uploadedReferences[messageIndex]" in body)
        assertTrue("sendMediaAttachments(" in body)
        assertFalse(
            Regex("sendMediaAttachments\\([^)]*source\\.reference", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body),
        )
    }

    /** The transport declares both owner guards and batch serialization. */
    @Test
    fun productionTransportGuardsBothOwnersAndSerializesEachDestinationBatch() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")
        val start = appStateSource().readText().functionBody("startForwardMessages")

        assertTrue("forwardTransport(sourceAccount, account, messages.size, diagnostics)" in start)

        assertTrue("fun requireSourceAccount()" in body)
        assertTrue("fun requireDestinationAccount()" in body)
        assertTrue("isForwardOwnerSignedIn(sourceAccount)" in body)
        assertTrue("isForwardOwnerSignedIn(account)" in body)
        assertTrue("withGroupCommitLock(account, targetGroupIdHex)" in body)
        assertTrue("for (messageIndex in startIndex until messages.size)" in body)
        assertTrue("onMessagePublished(messageIndex)" in body)
    }

    /** Materialization stays bound to the source owner; upload/publish stay bound to the destination owner. */
    @Test
    fun productionTransportSplitsSourceAndDestinationOwnership() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")

        assertTrue("accountRef = sourceAccount" in body)
        val materializeBlock =
            body
                .substringAfter("override suspend fun materialize(")
                .substringBefore("override suspend fun upload(")
        assertTrue("requireSourceAccount()" in materializeBlock)
        assertFalse("requireDestinationAccount()" in materializeBlock)
        val uploadBlock =
            body
                .substringAfter("override suspend fun upload(")
                .substringBefore("private suspend fun recentForwardTimeline")
        assertTrue("requireDestinationAccount()" in uploadBlock)
        assertFalse("requireSourceAccount()" in uploadBlock)
        // Neither boundary may re-read the live active account inside the transport.
        val transport = body.substringAfter("object : ForwardTransport {")
        assertFalse("activeAccountRef" in transport)
    }

    /**
     * Forward materialization stays isolated from the shared download pool:
     * account switching cancels and clears that pool, so a forward that joined
     * it would be killed by an unrelated switch. The isolated path reads the
     * caches opportunistically, never writes them, and downloads inside the
     * forwarding session's own scope.
     */
    @Test
    fun productionMaterializationStaysOutOfTheSharedCancellableDownloadPool() {
        val transportFile = transportSource().readText()
        val transport = transportFile.functionBody("WhiteNoiseAppState.forwardTransport")
        val isolated = transportFile.functionBody("WhiteNoiseAppState.materializeAttachmentPlaintextIsolated")

        assertFalse("downloadAttachmentPlaintext(" in transport)
        assertFalse("memoizedDownload(" in transportFile)
        assertTrue("cachedMediaPlaintext(cacheKey)" in isolated)
        assertTrue("diskMediaCache.get(cacheKey)" in isolated)
        assertFalse("cacheMediaPlaintext(" in isolated)
        assertFalse("diskMediaCache.put" in isolated)
        // MarmotKit's retained copy is read before the network, and only after both Android caches miss.
        assertTrue("runCatchingCancellable { readRetained(request) }" in isolated)
        assertTrue("readRetainedForwardPlaintext { openNativeAttachment(request) }" in transportFile)
        // The whole-file read leaves the main dispatcher a forward session starts on.
        assertTrue(
            "open()?.use { lease -> withContext(Dispatchers.IO) { lease.toByteArray() } }" in transportFile,
        )
        assertTrue("downloadMedia(request.accountRef, request.groupIdHex, reference)" in isolated)
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
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")
        val start = appStateSource().readText().functionBody("startForwardMessages")
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
        assertTrue("forwardOperationOwner.start(session)" in start)
        assertTrue("session.release()" in start)
        assertTrue("scope.launch" in owner)
        assertTrue("candidate.state.first { !it.isActive }" in owner)
        assertTrue("candidate.retryFailed()" in retry)
    }

    /** Every forward phase the issue names is timed on the production path and reported at the terminal. */
    @Test
    fun productionTransportTimesSourceUploadLockAndPublishPhasesWithoutIdentifiers() {
        val transportFile = transportSource().readText()
        val body = transportFile.functionBody("WhiteNoiseAppState.forwardTransport")
        val isolated = transportFile.functionBody("WhiteNoiseAppState.materializeAttachmentPlaintextIsolated")
        val appState = appStateSource().readText()
        val diagnostics = forwardDiagnosticsSource().readText()

        assertTrue("ForwardDiagnostics.begin()" in appState.functionBody("startForwardMessages"))
        assertTrue("activeForwardDiagnostics?.terminal(snapshot)" in appState)
        assertTrue("diagnostics.span({}, ForwardDiagnostics::sourceReady)" in body)
        assertTrue("diagnostics.span({}, ForwardDiagnostics::sourceReferenceResolved)" in body)
        assertTrue("ForwardDiagnostics::uploadStart, ForwardDiagnostics::uploadReturn" in body)
        assertTrue("diagnostics?.commitLockAcquired(lockRequestedAtMs)" in body)
        assertTrue("ForwardDiagnostics::publishStart, ForwardDiagnostics::publishReturn" in body)
        assertTrue("ForwardDiagnostics::convergenceStart, ForwardDiagnostics::convergenceReturn" in body)
        assertTrue("diagnostics?.sourceLookup(" in isolated)
        assertTrue("hit = cached != null || retained != null" in isolated)
        assertTrue("native = retained != null" in isolated)
        assertTrue("ForwardDiagnostics::sourceDownloadStart, ForwardDiagnostics::sourceDownloadReturn" in isolated)
        // The diagnostics owner has no String, identifier or byte inputs to serialize.
        listOf("String", "groupIdHex", "messageIdHex", "accountRef", "fileName", "ByteArray", "Throwable.message")
            .forEach { denied -> assertFalse("Unexpected diagnostic input: $denied", denied in diagnostics) }
    }

    /** Locates the production forward diagnostics source in root- and module-scoped test layouts. */
    private fun forwardDiagnosticsSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ForwardDiagnostics.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ForwardDiagnostics.kt"),
        ).firstOrNull(File::exists) ?: error("Missing ForwardDiagnostics.kt source file")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppState.kt source file")

    /** Locates the extracted production transport in root- and module-scoped test layouts. */
    private fun transportSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppStateForwardTransport.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppStateForwardTransport.kt"),
        ).firstOrNull(File::exists) ?: error("Missing AppStateForwardTransport.kt source file")

    private fun messageForwardingSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/MessageForwarding.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/MessageForwarding.kt"),
        ).firstOrNull(File::exists) ?: error("Missing MessageForwarding.kt source file")
}
