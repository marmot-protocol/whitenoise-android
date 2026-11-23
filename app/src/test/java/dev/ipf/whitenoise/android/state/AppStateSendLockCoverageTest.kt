package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.conversationControllerSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.motionOwnerSource
import dev.ipf.whitenoise.android.notificationDeliveryControllerSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the two AppState send paths that cannot be reached through
 * ConversationController's behavioral send tests.
 *
 * WhiteNoiseAppState construction needs Android Context plus the real Marmot FFI,
 * so this JVM test verifies the structural invariant: each commit-producing send
 * calls sendText only from inside the existing per-group commit lock.
 */
@Suppress("LargeClass") // Structural send-path assertions share source parsers and lock invariants.
class AppStateSendLockCoverageTest {
    @Test
    fun dictationSendRecordsTiming() {
        val body = appStateFunctionBody("sendDictationTranscriptIfOriginUnchanged")
        val mountedControllerBody = appStateFunctionBody("sendDictationThroughMountedController")

        assertTrue(
            "Mounted dictation must dispatch through the ordinary controller path",
            "sendDictationThroughMountedController(liveReply.controller, request)" in body &&
                Regex(
                    """sendConversationText\(\s*controller,\s*request\.payload,\s*onAccepted\s*=\s*\{""" +
                        """[^{}]*pendingShown\s*=\s*true[^{}]*request\.onPendingShown\(\)\s*\},?\s*\)""",
                ).containsMatchIn(mountedControllerBody),
        )
        assertTrue(
            "The ordinary text publisher must record its marmotIo timing section",
            "marmotIo(MarmotTraceSection.TEXT_SEND)" in controllerFunctionBody("send") ||
                "marmotIo(MarmotTraceSection.TEXT_SEND)" in conversationControllerSource() ||
                "marmotIo(MarmotTraceSection.TEXT_SEND)" in
                controllersSource().resolveSibling("DurableLocalSends.kt").motionOwnerSource(),
        )
    }

    @Test
    fun forwardTextsLocksEachTargetsOrderedMessageBatch() {
        val body = functionBodyIn(forwardingSource().motionOwnerSource(), "forwardTexts")

        assertTrue(
            "forwardTexts must serialize each target's ordered message batch through the per-group commit lock",
            Regex(
                """for\s*\(\s*groupIdHex\s+in\s+targets\s*\).*""" +
                    """withGroupCommitLock\s*\(\s*account\s*,\s*groupIdHex\s*\).*""" +
                    """for\s*\(\s*body\s+in\s+bodies\s*\).*""" +
                    """marmotIo\s*\(MarmotTraceSection\.TEXT_SEND\)\s*\{\s*sendText""" +
                    """\s*\(\s*account\s*,\s*groupIdHex\s*,\s*body\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
        assertTrue(
            "forwardTexts must validate bodies without trimming user-authored text",
            "MessageProjector.validatedForwardTextBodies(texts)" in body &&
                "String::trim" !in body,
        )
        assertTrue(
            "forwardTexts must continue after an individual send failure and report partial batches accurately",
            "successfulSends += 1" in body &&
                "targetComplete = false" in body &&
                "R.string.toast_forwarded_batch_partial" in body,
        )
    }

    @Test
    fun singleMessageForwardKeepsSingleMessageResultCopy() {
        val body = functionBodyIn(forwardingSource().motionOwnerSource(), "forwardText")

        assertTrue(
            "forwardText must keep its single-message target breakdown instead of using batch result copy",
            "R.string.toast_forwarded_partial" in body &&
                "R.string.toast_forward_failed" in body &&
                "toast_forwarded_batch_partial" !in body,
        )
    }

    /** The gate sits inside the IO bridge and precedes each native mutation. */
    @Test
    fun notificationMutationsCheckAppLockAtNativeAdmission() {
        val source = notificationReplySource()
        val reply = appStateFunctionBody("sendNotificationReply", source)
        assertTrue(reply.indexOf("withGroupCommitLock(account, group)") in 0 until reply.indexOf("marmotIo("))
        val mutations =
            listOf(
                reply to "sendText(account, group, body)",
                appStateFunctionBody("sendNotificationReaction", source) to "reactToMessage(",
                appStateFunctionBody("markNotificationMessageReadIfAllowed", source) to "markTimelineMessageRead(",
            )
        mutations.forEach { (body, nativeCall) ->
            val gate = body.indexOf("appLock.notificationActionsAllowed")
            val ioBridge = Regex("""\bmarmotIo\s*[(\{]""").find(body)?.range?.first ?: -1
            assertTrue(ioBridge in 0 until gate)
            assertTrue(gate in 0 until body.indexOf(nativeCall))
        }
    }

    @Test
    fun acceptedPendingNotificationReplyPersistsItsCanonicalIdBeforeReturning() {
        val body = appStateFunctionBody("sendNotificationReply", notificationReplySource())
        val sendIndex = body.indexOf("sendText(account, group, body)")
        val persistIndex = body.indexOf("completionStore.markCommittedMessage", startIndex = sendIndex)
        val acceptedPendingIndex = body.indexOf("NotificationReplySendOutcome.AcceptedPending", startIndex = sendIndex)

        assertTrue(
            "an accepted-pending reply must persist MDK's canonical id before reporting " +
                "success, so crash recovery cannot resend it",
            sendIndex >= 0 && persistIndex > sendIndex && acceptedPendingIndex > persistIndex,
        )
    }

    /** A cold worker has no foreground transition. Grace expires at the configured boundary. */
    @Test
    fun notificationActionsRequireAnAuthenticatedSession() {
        assertTrue(notificationBackgroundGraceAllows(1L, 60_000L, AppLockDelay.OneMinute))
        assertFalse(notificationBackgroundGraceAllows(1L, 60_001L, AppLockDelay.OneMinute))
        assertFalse(notificationBackgroundGraceAllows(null, 60_001L, AppLockDelay.OneMinute))
        assertFalse(notificationBackgroundGraceAllows(2L, 1L, AppLockDelay.OneMinute))
        assertFalse(notificationBackgroundGraceAllows(1L, 1L, AppLockDelay.Immediately))
        assertTrue(notificationActionsAllowed(false, true, true, true))
        assertFalse(notificationActionsAllowed(false, true, true, false))
        assertFalse(notificationActionsAllowed(true, true, true, true))
        assertFalse(notificationActionsAllowed(false, true, false, true))
        assertTrue(notificationActionsAllowed(false, false, false, false))
    }

    @Test
    fun visibleConversationDismissalStaysWiredToResumeAndUnlock() {
        val foregroundBody = appStateFunctionBody("setAppInForeground")
        val foregroundLockGate = foregroundBody.indexOf("appLock.onAppForegrounded()")
        val foregroundDismissal = foregroundBody.indexOf("dismissVisibleConversationNotifications()")
        assertTrue(
            "foreground resume must dismiss only after deciding whether app lock covers the conversation",
            foregroundLockGate >= 0 && foregroundDismissal > foregroundLockGate,
        )

        val controllerSource = appStateSource().resolveSibling("AppLockController.kt").motionOwnerSource()
        val unlockStart = controllerSource.indexOf("fun markUnlockSucceeded(")
        val unlockBody = controllerSource.substring(unlockStart, controllerSource.indexOf("\n    }\n", unlockStart))
        val hideLock = unlockBody.indexOf("screenVisible = false")
        val unlockCallback = unlockBody.indexOf("onUnlockSucceeded(dismissRetainedVisibleConversation)")
        val wiring =
            appStateSource()
                .motionOwnerSource()
                .substringAfter("onUnlockSucceeded = { dismissRetainedVisibleConversation ->")
        assertTrue(
            "successful unlock must dismiss after revealing the retained conversation",
            hideLock >= 0 &&
                unlockCallback > hideLock &&
                wiring.substringBefore("},").contains("dismissVisibleConversationNotifications()"),
        )
    }

    /** The fenced wipe completion must clear native push state under the same mutex as push synchronization. */
    @Test
    fun destructiveWipeDropsSyncedPushFingerprintUnderNativePushMutex() {
        val publicEntry = appStateFunctionBody("signOutAndWipeActiveAccount")
        assertTrue(
            "the public wipe must own the account lifetime before entering its revocation bracket",
            publicEntry.indexOf("withAccountLifetime") in
                0 until publicEntry.indexOf("signOutAndWipeActiveAccountLocked(requested)"),
        )
        val entry = appStateFunctionBody("signOutAndWipeActiveAccountLocked")
        val revocation = entry.indexOf("withRevokedPinnedTarget(wipedRef)")
        assertTrue("wipe must acquire durable launcher revocation for its target", revocation >= 0)
        val revokedBody = entry.kotlinBlockFrom(entry.indexOf('{', revocation), "launcher revocation")
        assertTrue(
            "the public wipe must delegate completion inside the durable launcher-revocation bracket",
            "finishRevokedAccountWipe(wipedRef, wipedShareAccount)" in revokedBody,
        )
        val body = appStateFunctionBody("finishRevokedAccountWipe")
        val serializedWipeIndex = body.indexOf("nativePushSyncMutex.withSerializedNativePushWipe")
        assertTrue("wipe must acquire the native push mutex", serializedWipeIndex >= 0)
        val serialized = body.kotlinBlockFrom(body.indexOf('{', serializedWipeIndex), "serialized native wipe")
        val nativeWipe = serialized.indexOf("signOutAndWipe(wipedRef)")
        val completed = serialized.indexOf("if (outcome.localCleanup.completed)")
        assertTrue(
            "push cleanup must follow completed native removal under the same mutex",
            nativeWipe in 0 until completed,
        )
        val completedBody = serialized.kotlinBlockFrom(serialized.indexOf('{', completed), "successful native removal")
        assertTrue(
            "a completed wipe must remove only its target's fingerprint under the native push mutex",
            "perAccountSyncedFingerprints.remove(wipedRef)" in completedBody,
        )
        assertFalse(
            "withSerializedNativePushWipe already holds the mutex; nesting withLock would deadlock",
            Regex("""nativePushSyncMutex\.withLock\s*\{\s*perAccountSyncedFingerprints\.remove\(wipedRef\)\s*}""")
                .containsMatchIn(body),
        )
    }

    /** Keeps delivery cutover cleanup atomic with registration sync and suppresses competing token work. */
    @Test
    fun notificationDeliveryCleanupSerializesWithNativePushSync() {
        val disableBody = notificationDeliveryFunctionBody("disableNativePushForPersistentDelivery")
        val rollbackBody = notificationDeliveryFunctionBody("rollbackNativePushMode")
        val rotationBody = notificationDeliveryFunctionBody("onPushTokenRotated")
        val finishBody = notificationDeliveryFunctionBody("finishNotificationDeliveryModeTransaction")
        val modeEntryBodies =
            listOf(
                // setNotificationDeliveryMode is an expression body that delegates to applyMode.
                notificationDeliveryFunctionBody("applyMode"),
                notificationDeliveryFunctionBody("launchNotificationDeliveryModeReconciliation"),
            )

        listOf(disableBody, rollbackBody).forEach { body ->
            val lockIndex = body.indexOf("nativePushSyncMutex.withLock")
            val fingerprintIndex = body.indexOf("perAccountSyncedFingerprints.remove")
            val clearIndex = body.indexOf("clearPushRegistrationForNotificationDeliveryOwnerLocked")
            assertTrue("delivery cleanup must acquire the native-push sync mutex", lockIndex >= 0)
            assertTrue("fingerprints must be removed after acquiring the sync mutex", fingerprintIndex > lockIndex)
            assertTrue("registration clear must run after acquiring the sync mutex", clearIndex > lockIndex)
        }
        assertTrue(
            "token rotation must not start registration sync during a delivery-mode transaction",
            rotationBody.indexOf("if (!notificationDeliveryModeBusy)") in
                0 until rotationBody.indexOf("syncNativePushRegistration(token, ownerIsCurrent)"),
        )
        assertTrue(
            "token rotation must serialize its busy check with the delivery-mode transaction",
            rotationBody.indexOf("notificationDeliveryModeMutex.withLock") in
                0 until rotationBody.indexOf("if (!notificationDeliveryModeBusy)"),
        )
        assertTrue(
            "a token rotation must retain a durable retry marker before inspecting cutover state",
            rotationBody.indexOf("recordPendingNativePushRegistrationSync()") in
                0 until rotationBody.indexOf("if (!notificationDeliveryModeBusy)"),
        )
        assertTrue(
            "the latest delivery transaction must drain token work after clearing busy state under its mutex",
            finishBody.indexOf("notificationDeliveryModeMutex.withLock") in
                0 until finishBody.indexOf("notificationDeliveryModeBusy = false") &&
                finishBody.indexOf("notificationDeliveryModeBusy = false") in
                0 until finishBody.indexOf("nativePushRegistrationSyncPending()") &&
                finishBody.indexOf("nativePushRegistrationSyncPending()") in
                0 until finishBody.indexOf("syncNativePushRegistrationIfEnabled()"),
        )
        modeEntryBodies.forEach { body ->
            assertTrue(
                "every busy delivery-mode entry point must settle through the token-work handoff",
                body.contains("finishNotificationDeliveryModeTransaction"),
            )
        }
        assertTrue(
            "explicit delivery-mode selection must enter through the busy applyMode transaction",
            Regex("""fun setNotificationDeliveryMode\([^)]*\): Boolean = applyMode\(mode, true\)""")
                .containsMatchIn(notificationDeliveryControllerSource()),
        )
    }

    /** Refused native wipes must exit before any successful-wipe-only profile or avatar cache eviction. */
    @Test
    fun failedDestructiveWipeBranchesExitBeforeProcessGlobalProfileCachesAreCleared() {
        val body = appStateFunctionBody("finishRevokedAccountWipe")
        val engineFailureIndex = body.indexOf("val failure = wipeResult.exceptionOrNull()")
        val engineFailureReturnIndex = body.indexOf("return null", startIndex = engineFailureIndex)
        val nullOutcomeFallbackIndex = body.indexOf("wipeResult.getOrNull() ?: run")
        val nullOutcomeReturnIndex = body.indexOf("return null", startIndex = nullOutcomeFallbackIndex)
        val avatarClearIndex = body.indexOf("AvatarImageLoader.clear()")
        val scopedCacheClearIndex = body.indexOf("clearCrossAccountCaches()")

        assertTrue(
            "an engine failure must return before the null-outcome fallback and cache eviction",
            engineFailureIndex >= 0 &&
                engineFailureReturnIndex > engineFailureIndex &&
                engineFailureReturnIndex < nullOutcomeFallbackIndex,
        )
        assertTrue(
            "a null wipe outcome must return before process-global profile caches are evicted",
            nullOutcomeFallbackIndex >= 0 &&
                nullOutcomeReturnIndex > nullOutcomeFallbackIndex &&
                avatarClearIndex > nullOutcomeReturnIndex &&
                scopedCacheClearIndex > nullOutcomeReturnIndex,
        )
    }

    @Test
    fun retentionSweepRoutesThroughEngineAccountWorker() {
        val body = appStateFunctionBody("runRetentionSweep", sourceFile = retentionSweepSource())

        assertTrue(
            "the retention sweep core must be the engine's atomic gate+prune call",
            Regex(
                """marmotIo\s*\{\s*sweepExpiredRetention\s*\(\s*accountRef\s*,\s*nowMillis\.toULong\(\)\s*\)\s*\}""",
            ).containsMatchIn(body),
        )
        assertFalse(
            "the account worker already serializes the sweep against sends; an app-side commit lock would be redundant",
            "withGroupCommitLock" in body,
        )
    }

    @Test
    fun retentionSweepOutcomeKeepsDiskEvictionTagScoped() {
        val body = appStateFunctionBody("processRetentionSweepOutcome", sourceFile = retentionSweepSource())

        assertTrue(
            "pruned groups must dismiss tray cards only when rows were actually pruned",
            Regex("""prunedMessages\s*>\s*0uL""").containsMatchIn(body),
        )
        assertTrue(
            "disk eviction must stay ciphertext-tag scoped; only the in-memory tier may drop the whole group slice",
            "removeMediaDiskCacheTags(expiredCiphertextSha256)" in body &&
                "removeMediaDiskCacheEntry(" !in body &&
                "diskMediaCache.remove(" !in body,
        )
    }

    /** Guards the engine-owned projected deadline contract used by loaded-row expiry checks. */
    @Test
    fun localExpiryRowsFeedEngineAuthoritativeExpiry() {
        // localExpiryRow is an expression-body function, so window the source
        // instead of brace-matching a block body.
        val source = conversationControllerSource()
        val start = source.indexOf("fun ConversationController.localExpiryRow(")
        require(start >= 0) { "Missing localExpiryRow" }

        assertTrue(
            "the loaded-row hide filter must prefer the engine's projected per-message expiry",
            source.substring(start, (start + 800).coerceAtMost(source.length)).let { body ->
                "val authoritativeExpiry = record.retentionExpiresAt?.takeIf { it > 0uL }" in body &&
                    "expiresAtLocalSeconds = authoritativeExpiry" in body
            },
        )
    }

    @Test
    fun foregroundDisappearingSweepDelegatesToEngineSweep() {
        val body = controllerFunctionBody("runForegroundDisappearingMessageSweep")

        assertTrue(
            "foreground sweeps must run the engine sweep with their own group's outcome handled in place",
            Regex(
                """appState\.runRetentionSweep\s*\(\s*account\s*,\s*nowMillis\s*,\s*""" +
                    """handledGroupIdHex\s*=\s*group\.groupIdHex\s*\)""",
            ).containsMatchIn(body),
        )
        assertFalse(
            "the engine gate replaced the app-side scan; no commit lock or direct prune belongs here",
            "withGroupCommitLock" in body || "secureDeleteExpired" in body,
        )
    }

    /**
     * DM classification prefers the engine's projected kind, read through a derived kind so classification readers
     * do not recompose on every chat-list row update.
     */
    @Test
    fun conversationClassificationPrefersProjectedKind() {
        val source = conversationControllerSource()
        val derivedKind =
            Regex(
                """val\s+latestConversationKind:\s*ChatConversationKindFfi\?\s*by\s*derivedStateOf\s*\{\s*""" +
                    """latestChatListRow\?\.conversationKind\s*}""",
            )
        val projectedIsDm =
            Regex(
                """val\s+isDm:\s*Boolean\s*\n\s*get\(\)\s*=\s*""" +
                    """GroupProjector\.isDm\(latestConversationKind,\s*memberCount,\s*group\.name\)""",
            )
        val projectedIsDirect =
            Regex(
                """val\s+isDirectConversation:\s*Boolean\s*\n\s*get\(\)\s*=\s*""" +
                    """GroupProjector\.isDm\(latestConversationKind,\s*memberCount,\s*group\.name\)""",
            )

        assertTrue(
            "conversation-screen DM classification must prefer the engine's projected kind, heuristic only as fallback",
            derivedKind.containsMatchIn(source) &&
                projectedIsDm.containsMatchIn(source) &&
                projectedIsDirect.containsMatchIn(source),
        )
    }

    @Test
    fun failedSendDiscardRetainsCallbackOnlyWhileRetryStillExists() {
        val body = controllerFunctionBody("discardFailedSend")

        assertTrue(
            "discardFailedSend must arbitrate stale failure actions through the shared cancellation lock",
            "suspend fun discardFailedSend" in conversationControllerSource() &&
                "cancelOptimisticSendResult(current.record" in body &&
                "optimisticKeyOverride = key" in body,
        )
    }

    @Test
    fun manualFailedSendRetryUsesTheSharedConnectivityPolicyWithoutAnOuterCommitLock() {
        val body = controllerFunctionBody("retryFailedSend")
        val retryStart =
            body.indexOf("retryTrace = PerformanceDiagnostics.begin(PerformanceOperation.TEXT_SEND)")
        val sharedRetry = body.indexOf("publishTextWithRetry(replyTarget, account, text", startIndex = retryStart)
        val retryEnd = body.indexOf("completeDurableAcceptance(key)", startIndex = sharedRetry)

        assertTrue(
            "manual text/reply retry must reuse the shared connect-phase policy",
            retryStart >= 0 && sharedRetry > retryStart && retryEnd > sharedRetry,
        )
        val retryWindow = body.substring(retryStart, retryEnd)
        assertFalse(
            "manual retry must not hold the group commit lock around the shared retry loop",
            "withGroupCommitLock" in retryWindow,
        )
        assertFalse(
            "manual text/reply retry must not bypass the shared policy with direct FFI publish calls",
            "replyToMessage(account, group.groupIdHex, replyTarget, text)" in body ||
                "sendText(account, group.groupIdHex, text)" in body,
        )
    }

    @Test
    fun foregroundTextRetryListensForValidatedConnectivityRecovery() {
        val body = controllerFunctionBody("publishTextWithRetry")

        assertTrue(
            "the foreground retry loop must wake when Android validates restored internet",
            "retryPendingConversationSend(" in body &&
                "connectivityRecoveryGeneration = appState.validatedConnectivityRecoveryGeneration" in body,
        )
    }

    @Test
    fun markLatestVisibleReadDeadPathStaysDeleted() {
        val source = conversationControllerSource()
        val declaration = Regex("""\bfun\s+(?:\w+\.)?markLatestVisibleRead\s*\(""")

        assertTrue(declaration.containsMatchIn("internal fun ConversationController.markLatestVisibleRead()"))
        assertFalse(
            "markLatestVisibleRead zeroes unread state from a stale open-chat path and should stay deleted",
            declaration.containsMatchIn(source),
        )
    }

    @Test
    fun appStateRuntimeReferenceIsVolatile() {
        val source = appStateSource().motionOwnerSource()

        assertTrue(
            "Marmot runtime is written on IO and read off-mutex, so visibility must be explicit",
            Regex("""@Volatile\s+private\s+var\s+marmotRuntime:\s*AppMarmotRuntime\?""").containsMatchIn(source),
        )
    }

    /** Keeps read-watermark math tied to the edit-filtered timeline and every structural prefix row. */
    @Test
    fun conversationReadAnchorUsesHoistedRenderedTimelineAndCandidateIdentity() {
        val screen = conversationScreenSource()
        val source = screen.motionOwnerSource()
        val tracking = screen.parentFile.resolve("ConversationUnreadTracking.kt").motionOwnerSource()
        val renderedTimelineIndex = source.indexOf("val renderedTimeline =")
        val readAnchorHelper = tracking.substring(tracking.indexOf("private fun ConversationReadAnchorEffect("))
        val readAnchorCallIndex = source.indexOf("ConversationUnreadTracking(", renderedTimelineIndex)
        val readAnchorCall = source.substring(readAnchorCallIndex, source.indexOf(")", readAnchorCallIndex) + 1)
        val trackingCall = tracking.substring(tracking.indexOf("    ConversationReadAnchorEffect("))

        assertTrue(
            "read-anchor effect must preserve the durable watermark and reuse the hoisted edit-filtered timeline",
            renderedTimelineIndex >= 0 &&
                renderedTimelineIndex < readAnchorCallIndex &&
                "renderedTimeline = renderedTimeline" in readAnchorCall &&
                "renderedTimeline = renderedTimeline" in trackingCall.substringBefore(")") &&
                "remember(timelineViewport, renderedSize, trailingRowCount)" in readAnchorHelper &&
                "currentHighestVisibleMessageId," in readAnchorHelper &&
                "advanceConversationReadAnchor(" in readAnchorHelper &&
                "durableAnchorId = controller.lastReadMessageId" in readAnchorHelper &&
                "filterNot { MessageProjector.isEdit(it.record) }" !in readAnchorHelper,
        )
    }

    /** Stable presentation keys ignore media hydration while canonical IDs still identify durable anchors. */
    @Test
    fun conversationHistoryReanchorIgnoresSameRowHydration() {
        val screenFile = conversationScreenSource()
        val source = screenFile.motionOwnerSource().replace(Regex("\\s+"), " ")
        val owner =
            screenFile.parentFile
                .resolve("ConversationViewportRestorationOwner.kt")
                .motionOwnerSource()
                .replace(Regex("\\s+"), " ")
        val effects =
            screenFile.parentFile
                .resolve("ConversationViewportRestorationEffects.kt")
                .motionOwnerSource()
                .replace(Regex("\\s+"), " ")

        assertTrue(
            "startup materialization and same-row media hydration must not restart durable history anchoring",
            "val renderedTimelineAnchorKeys = remember(renderedTimeline)" in source &&
                "renderedTimeline.map { it.presentationId to it.record.messageIdHex }" in source &&
                "ConversationViewportRestorationEffects(" in source &&
                "ConversationViewportStructureEffect(" in source &&
                "coordinator.commitInitialAnchor(" in owner &&
                "coordinator.commitInitialTailAnchor(" in owner &&
                "while (!owner.commitInitialPosition(" in effects &&
                "reanchorGate.commit(" in owner &&
                "reanchorGate.onStructure(" in owner &&
                "anchored && changed" in owner &&
                "structure.rowKeys, structure.olderHeaderCount, structure.inlineTopErrorCount, anchored," in effects &&
                "LaunchedEffect(controller, renderedTimeline, olderHeaderCount" !in source &&
                "LaunchedEffect(controller, renderedTimeline, olderHeaderCount" !in effects,
        )
    }

    @Test
    fun voiceAutoChainSkipsDerivedEditRows() {
        val source = conversationScreenSource().motionOwnerSource()
        val autoChain =
            source.substring(
                source.indexOf("fun voicePlaybackHosts()"),
                source.indexOf("val nextMsg", source.indexOf("fun voicePlaybackHosts()")),
            )

        assertTrue(
            "auto-chain should skip invisible edit and group-system rows before checking the immediate visible voice neighbor",
            "MessageProjector.isEdit(controller.timeline[nextIdx].record)" in autoChain &&
                "MessageProjector.isGroupSystem(controller.timeline[nextIdx].record)" in autoChain,
        )
    }

    @Test
    fun loadOlderPageRoutesPaginateThroughActiveSubscriptionGuard() {
        val pagingSource = pagingSource().motionOwnerSource()
        val loadOlder = functionBodyIn(pagingSource, "loadOlderPageInternal")
        val guard = functionBodyIn(pagingSource, "pageOlderIfActive")
        val retains = functionBodyIn(pagingSource, "retainsSubscription")

        assertTrue(
            "loadOlderPageInternal must not call paginateBackwards directly on a captured subscription",
            "pageOlderIfActive(subscription" in loadOlder && "subscription.paginateBackwards" !in loadOlder,
        )
        assertTrue(
            "paginate guard must serialize paginate against close and re-check the active timeline subscription identity",
            "timelineSubscriptionActiveCallMutex.withLock" in pagingSource &&
                "retainsSubscription(handle)" in guard &&
                "paginateBackwards(ConversationTimelinePageLimit)" in guard &&
                "timelineSubscription === handle" in retains,
        )
    }

    @Test
    fun closeTimelineSubscriptionWaitsNonCancellablyForActiveCallMutex() {
        val session = controllersSource().resolveSibling("ConversationTimelineSession.kt").motionOwnerSource()
        val close = functionBodyIn(session, "closeTimeline")
        val paging = pagingSource().motionOwnerSource()
        val controllers = conversationControllerSource()
        val olderPage = functionBodyIn(paging, "pageOlderIfActive", includeDeclaration = true)
        val newerPage = functionBodyIn(paging, "pageNewerIfActive", includeDeclaration = true)
        val mutexProxy =
            "internal val timelineSubscriptionActiveCallMutex: Mutex " +
                "get() = timelineSession.activeCallMutex"
        assertTrue(
            "both paging directions must acquire the session's actual retirement mutex",
            "timelineSubscriptionActiveCallMutex.withLock" in olderPage &&
                "timelineSubscriptionActiveCallMutex.withLock" in newerPage &&
                mutexProxy in controllers,
        )

        assertTrue(
            "timeline subscription close must wrap mutex acquisition and close in NonCancellable",
            "withContext(NonCancellable)" in close &&
                close.indexOf("withContext(NonCancellable)") < close.indexOf("activeCallMutex.withLock") &&
                close.indexOf("activeCallMutex.withLock") < close.indexOf("stream.close()"),
        )
    }

    @Test
    fun readAnchorsArePrunedWithTimelineWindow() {
        val applyTimelinePage = controllerFunctionBody("applyTimelinePage")
        val applyTimelineChanges = controllerFunctionBody("applyTimelineChanges")
        val trimLiveTimelineWindow = controllerFunctionBody("trimLiveTimelineWindow")
        val removeProjectedRecord = controllerFunctionBody("removeProjectedRecord")
        val retainTimelineWindow =
            Regex("""fun MutableMap<String, \*>\.retainTimelineWindow\(\)\s*=\s*([^\n]+)""")
                .find(conversationControllerSource())
                ?.value
                ?: error("Missing retainTimelineWindow")

        assertTrue(
            "window replacements must prune read anchors after the authoritative page is applied",
            "readAnchoredAtSeconds.retainTimelineWindow()" in applyTimelinePage,
        )
        assertTrue(
            "live timeline changes must trim the window even after loadOlder (#1163)",
            "trimLiveTimelineWindow(LIVE_TIMELINE_WINDOW_CAP)" in applyTimelineChanges &&
                "!hasLoadedOlderPages" !in applyTimelineChanges,
        )
        assertTrue(
            "live timeline trims must prune read anchors for removed messages",
            "readAnchoredAtSeconds.retainTimelineWindow()" in trimLiveTimelineWindow,
        )
        assertTrue(
            "single-record removals must drop the removed message read anchor",
            "readAnchoredAtSeconds.remove(messageIdHex)" in removeProjectedRecord,
        )
        assertTrue(
            "read-anchor pruning must retain current timeline records and optimistic records only",
            "retainWindowMessageKeys(this, timelineRecords.keys, optimisticMessages.values)" in retainTimelineWindow,
        )
    }

    @Test
    fun messageOverlaysArePrunedWithTimelineWindowAndDirectRemovals() {
        val applyTimelinePage = controllerFunctionBody("applyTimelinePage")
        val applyTimelineChanges = controllerFunctionBody("applyTimelineChanges")
        val removeProjectedRecord = controllerFunctionBody("removeProjectedRecord")
        val pruneMessageOverlaysToWindow = controllerFunctionBody("pruneMessageOverlaysToWindow")

        assertTrue("window replacement must prune stale overlays", "pruneMessageOverlaysToWindow()" in applyTimelinePage)
        assertTrue("live timeline updates must prune stale overlays", "pruneMessageOverlaysToWindow()" in applyTimelineChanges)
        assertTrue(
            "direct record removal must clear delete and reaction overlays",
            "deletedMessageIds = deletedMessageIds - messageIdHex" in removeProjectedRecord &&
                "optimisticReactionChanges.entries.removeAll" in removeProjectedRecord &&
                "reactionsState.remove(messageIdHex)" in removeProjectedRecord,
        )
        assertTrue(
            "window pruning must retain timeline and optimistic targets only",
            "retainedMessageOverlayTargets(" in pruneMessageOverlaysToWindow &&
                "optimisticReactionChanges.entries.removeAll" in pruneMessageOverlaysToWindow,
        )
    }

    @Test
    fun mediaKindResolveFanoutSwallowsUnexpectedProjectionFailures() {
        val body =
            controllerFunctionBody(
                "ChatsController.schedulePendingMediaKindResolves",
                "ChatsControllerRowEnrichment.kt",
            )

        assertTrue(
            "media-kind resolve fanout must rethrow cancellation but swallow unexpected projection failures",
            Regex(
                """catch\s*\(\s*e:\s*CancellationException\s*\)\s*\{\s*throw\s+e\s*\}.*""" +
                    """catch\s*\(\s*_:\s*Throwable\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    @Test
    fun initializeReadStateUsesCancellationSafeWrapperBeforeLogging() {
        val body = controllerFunctionBody("initializeReadState")
        val wrapperIndex = body.indexOf("runCatchingCancellable {")
        val logIndex = body.indexOf("Log.w(")

        assertTrue(
            "initializeReadState must structurally rethrow cancellation before logging other failures",
            wrapperIndex >= 0 && wrapperIndex < logIndex,
        )
    }

    private fun appStateFunctionBody(
        functionName: String,
        sourceFile: File = appStateSource(),
    ): String = sourceFile.motionOwnerSource().functionBody(functionName)

    private fun notificationDeliveryFunctionBody(functionName: String): String =
        controllerFunctionBody(functionName, controllerFileName = "NotificationDeliveryController.kt")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")

    /** The notification quick-reply extensions, which send under the per-group commit lock. */
    private fun notificationReplySource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppStateNotificationReply.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppStateNotificationReply.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppStateNotificationReply.kt source file")

    /** The retention sweep extensions, which prune expired messages through the engine account worker. */
    private fun retentionSweepSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppStateRetentionSweep.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppStateRetentionSweep.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppStateRetentionSweep.kt source file")

    /** The forwarding extensions, which fan one message out under each target's commit lock. */
    private fun forwardingSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppStateForwarding.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppStateForwarding.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppStateForwarding.kt source file")

    /** The conversation paging extensions, which own the active-call guard around window pages. */
    private fun pagingSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ConversationTimelinePaging.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ConversationTimelinePaging.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ConversationTimelinePaging.kt source file")

    /** Extracts a function body, optionally retaining its declaration to check expression-body guards. */
    private fun functionBodyIn(
        source: String,
        functionName: String,
        includeDeclaration: Boolean = false,
    ): String {
        val start =
            // Extensions declare a receiver between `fun` and the name.
            Regex("""\bfun\s+(?:\w+\.)?${Regex.escape(functionName)}\s*\(""")
                .find(source)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        val body = source.kotlinBlockFrom(braceStart, "function $functionName")
        return if (includeDeclaration) source.substring(start, braceStart) + body else body
    }

    internal fun controllerFunctionBody(
        functionName: String,
        controllerFileName: String = "ConversationController.kt",
    ): String {
        val source =
            when (controllerFileName) {
                "ConversationController.kt" -> conversationControllerSource()
                "NotificationDeliveryController.kt" -> notificationDeliveryControllerSource()
                else -> controllersSource().resolveSibling(controllerFileName).motionOwnerSource()
            }
        val start =
            // Extensions declare a receiver between `fun` and the name.
            Regex("""\bfun\s+(?:\w+\.)?${Regex.escape(functionName)}\s*\(""")
                .find(source)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        return source.kotlinBlockFrom(braceStart, "function $functionName")
    }

    internal fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ConversationController.kt source file")

    internal fun conversationScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ConversationScreen.kt source file")

    private fun String.kotlinBlockFrom(
        openBrace: Int,
        description: String,
    ): String {
        require(getOrNull(openBrace) == '{') { "Missing opening brace for $description" }

        var depth = 0
        var index = openBrace
        var inLineComment = false
        var blockCommentDepth = 0
        var inString = false
        var inTripleString = false
        var inChar = false

        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            when {
                inLineComment -> {
                    if (current == '\n' || current == '\r') inLineComment = false
                    index += 1
                }
                blockCommentDepth > 0 -> {
                    when {
                        current == '/' && next == '*' -> {
                            blockCommentDepth += 1
                            index += 2
                        }
                        current == '*' && next == '/' -> {
                            blockCommentDepth -= 1
                            index += 2
                        }
                        else -> index += 1
                    }
                }
                inTripleString -> {
                    if (startsWith("\"\"\"", index)) {
                        inTripleString = false
                        index += 3
                    } else {
                        index += 1
                    }
                }
                inString -> {
                    when (current) {
                        '\\' -> index += 2
                        '"' -> {
                            inString = false
                            index += 1
                        }
                        else -> index += 1
                    }
                }
                inChar -> {
                    when (current) {
                        '\\' -> index += 2
                        '\'' -> {
                            inChar = false
                            index += 1
                        }
                        else -> index += 1
                    }
                }
                else -> {
                    when {
                        current == '/' && next == '/' -> {
                            inLineComment = true
                            index += 2
                        }
                        current == '/' && next == '*' -> {
                            blockCommentDepth = 1
                            index += 2
                        }
                        startsWith("\"\"\"", index) -> {
                            inTripleString = true
                            index += 3
                        }
                        current == '"' -> {
                            inString = true
                            index += 1
                        }
                        current == '\'' -> {
                            inChar = true
                            index += 1
                        }
                        current == '{' -> {
                            depth += 1
                            index += 1
                        }
                        current == '}' -> {
                            depth -= 1
                            index += 1
                            if (depth == 0) return substring(openBrace, index)
                        }
                        else -> index += 1
                    }
                }
            }
        }

        error("Unterminated block for $description")
    }
}
