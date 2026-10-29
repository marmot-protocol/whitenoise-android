package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForwardProductionBoundaryCoverageTest {
    /** Production forwarding reads plaintext under the source account and publishes only destination references. */
    @Test
    fun productionTransportMaterializesFromSourceAndPublishesOnlyDestinationReferences() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")

        assertTrue("resolveAttachmentReference(request)" in body)
        assertTrue("materializeAttachmentPlaintextIsolated(request, reference, diagnostics)" in body)
        assertTrue("uploadMedia(" in body)
        assertTrue("send = false" in body)
        assertTrue("uploadedReferences[messageIndex]" in body)
        assertTrue("sendPreparedMediaWithClientToken(" in body)
        assertFalse(
            Regex("sendPreparedMediaWithClientToken\\([^)]*source\\.reference", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(body),
        )
    }

    /**
     * The destination upload runs on MDK's file path under the start watchdog, so a transfer that never starts cannot
     * hang for minutes (#2556), and messages the file path cannot take keep the in-memory upload. The staged-disk
     * claim is taken outside the off-thread read, so a cancellation on the way back cannot leak it.
     */
    @Test
    fun productionUploadIsWatchedForStalls() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")
        val fileBacked =
            body
                .substringAfter("private suspend fun uploadFileBacked(")
                .substringBefore("private suspend fun uploadInMemory(")

        assertTrue("uploadMediaFiles(account, targetGroupIdHex, request, control)" in fileBacked)
        assertTrue("withUploadStartWatchdog(" in fileBacked)
        assertTrue("startMillis = FORWARD_UPLOAD_START_MILLIS" in fileBacked)
        assertTrue("cancel = control::cancel" in fileBacked)
        assertTrue("withNativeTransferControl(" in fileBacked)
        assertTrue("uploadFileBacked(targetGroupIdHex, message, batchCiphertextBytes)" in body)
        assertTrue("fileUploadFits(message.attachments, batch, available)" in body)
        assertTrue("diskClaims.claimIf(copyBytes, usableBytes)" in body)
        assertTrue("claim?.shrinkBy(snapshotBytes)" in body)
        assertTrue("claim?.release()" in body)
        assertTrue("uploadInMemory(targetGroupIdHex, message)" in body)
        // withContext drops its result when the caller is cancelled on the way back, so a claim taken inside it leaks.
        val offThread = body.substringAfter("withContext(Dispatchers.IO) {\n").substringBefore("\n                }\n")
        assertTrue("nativeFileBackedSendLimits()" in offThread)
        assertFalse("claimIf" in offThread)
    }

    /** The transport declares both owner guards and batch serialization. */
    @Test
    fun productionTransportGuardsBothOwnersAndSerializesEachDestinationBatch() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")
        val start = appStateSource().readText().functionBody("startForwardMessages")

        assertTrue("forwardTransport(sourceAccount, account, diagnostics)" in start)

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
                .substringBefore("override suspend fun publishBatch(")
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
        // A source over the forward's cap is rejected, never swallowed into a download that cannot succeed.
        assertTrue("if (failure is ForwardPayloadTooLargeException) throw failure" in isolated)
        assertTrue("throw ForwardPayloadTooLargeException()" in transportFile)
        // The open carries the same cap, so an over-cap retained body is refused before it is copied into a lease.
        val budgetedOpen = "readRetainedForwardPlaintext(maxBytes) { openNativeAttachment(request, maxBytes) }"
        assertTrue(budgetedOpen in transportFile)
        // The read is bounded and runs off the main dispatcher a forward session starts on.
        assertTrue("open()?.use { lease -> lease.readWithin(maxBytes) }" in transportFile)
        assertTrue("downloadMedia(request.accountRef, request.groupIdHex, reference)" in isolated)
    }

    /** Session policy bounds preparation and deliberately excludes timeout from automatic retry loops. */
    @Test
    fun forwardingSessionBoundsPreparationWithoutAutomaticRestall() {
        val source = messageForwardingSource().readText()

        val bounded = source.substringAfter("withTimeoutOrNull(preparationTimeoutMillis) {").substringBefore("?: run {")
        assertTrue("preparation must stay inside its timeout", "materializeMessages()" in bounded)
        // The slow notice only changes what is shown inside that same bound, it never extends or restarts it.
        assertTrue("withSlowPhaseNotice(" in bounded)
        assertTrue("throw ForwardPreparationTimeoutException()" in source)
        assertTrue("if (snapshot.canAutomaticallyRetry && retryCount < automaticRetryAttempts)" in source)
    }

    /**
     * Destination publishes are admitted by client token (#2556) and count as sent only once MDK's engine owns them:
     * a retry probes the same token first and every native call is detached so the deadline bounds it. A healthy
     * forward never asks for convergence, matching the attachment fixture's contract.
     */
    @Test
    fun publishIsTokenBoundAndTheAppScopeOwnsCompletion() {
        val body = transportSource().readText().functionBody("WhiteNoiseAppState.forwardTransport")
        val start = appStateSource().readText().functionBody("startForwardMessages")
        val ownerSource = messageForwardingSource().readText()
        val owner = ownerSource.functionBody("monitor")
        val retry = ownerSource.functionBody("retryAutomatically")

        assertTrue("clientTokenFor(messageIndex)" in body)
        assertTrue("admitForwardPublish(" in body)
        assertTrue("detachedNative { localSendStatus(account, targetGroupIdHex, token) }" in body)
        assertTrue("sendTextWithClientToken(account, targetGroupIdHex, message.text, token)" in body)
        assertTrue("sendPreparedMediaWithClientToken(" in body)
        assertTrue("beforeAdmit = ::requireDestinationAccount" in body)
        assertFalse("retryGroupConvergence" in transportSource().readText())
        assertFalse("onvergence" in forwardDiagnosticsSource().readText())
        assertTrue(".async { marmotIo(block) }" in body)
        assertTrue("CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)" in body)
        assertFalse("recoverPendingPublish" in body)
        assertFalse("sendText(account" in body)
        assertFalse("sendMediaAttachments(" in body)
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
        assertTrue("diagnostics?.admissionAccepted(admissionStartedAtMs)" in body)
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
