package dev.ipf.whitenoise.android.state

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
class AppStateSendLockCoverageTest {
    @Test
    fun forwardTextsLocksEachTargetsOrderedMessageBatch() {
        val body = appStateFunctionBody("forwardTexts")

        assertTrue(
            "forwardTexts must serialize each target's ordered message batch through the per-group commit lock",
            Regex(
                """for\s*\(\s*groupIdHex\s+in\s+targets\s*\).*""" +
                    """withGroupCommitLock\s*\(\s*account\s*,\s*groupIdHex\s*\).*""" +
                    """for\s*\(\s*body\s+in\s+bodies\s*\).*""" +
                    """marmotIo\s*\{\s*sendText\s*\(\s*account\s*,\s*groupIdHex\s*,\s*body\s*\)\s*\}""",
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
        val body = appStateFunctionBody("forwardText")

        assertTrue(
            "forwardText must keep its single-message target breakdown instead of using batch result copy",
            "R.string.toast_forwarded_partial" in body &&
                "R.string.toast_forward_failed" in body &&
                "toast_forwarded_batch_partial" !in body,
        )
    }

    @Test
    fun notificationReplyLocksSend() {
        val body = appStateFunctionBody("sendNotificationReply")

        assertTrue(
            "notification quick replies must serialize sendText through the per-group commit lock",
            Regex(
                """withGroupCommitLock\s*\(\s*account\s*,\s*group\s*\).*""" +
                    """marmotIo\s*\{\s*sendText\s*\(\s*account\s*,\s*group\s*,\s*body\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    @Test
    fun notificationActionsAreBlockedWhileAppLockScreenIsVisible() {
        assertTrue(notificationActionsAllowed(appLockScreenVisible = false))
        assertFalse(notificationActionsAllowed(appLockScreenVisible = true))
    }

    @Test
    fun visibleConversationDismissalStaysWiredToResumeAndUnlock() {
        val foregroundBody = appStateFunctionBody("setAppInForeground")
        val foregroundLockGate = foregroundBody.indexOf("maybeShowAppLockForForeground()")
        val foregroundDismissal = foregroundBody.indexOf("dismissVisibleConversationNotifications()")
        assertTrue(
            "foreground resume must dismiss only after deciding whether app lock covers the conversation",
            foregroundLockGate >= 0 && foregroundDismissal > foregroundLockGate,
        )

        val unlockBody = appStateFunctionBody("markAppUnlockSucceeded")
        val hideLock = unlockBody.indexOf("appLockScreenVisible = false")
        val unlockedDismissal = unlockBody.indexOf("dismissVisibleConversationNotifications()")
        assertTrue(
            "successful unlock must dismiss after revealing the retained conversation",
            hideLock >= 0 && unlockedDismissal > hideLock,
        )
    }

    @Test
    fun destructiveWipeDropsSyncedPushFingerprintUnderNativePushMutex() {
        val body = appStateFunctionBody("signOutAndWipeActiveAccount")
        val serializedWipeIndex = body.indexOf("nativePushSyncMutex.withSerializedNativePushWipe")
        val removalIndex = body.indexOf("perAccountSyncedFingerprints.remove(wipedRef)")

        assertTrue(
            "destructive wipes must serialize push cache mutation with native push sync",
            serializedWipeIndex >= 0,
        )
        assertTrue(
            "destructive wipes must drop the wiped account's cached push fingerprint under the native push mutex",
            removalIndex > serializedWipeIndex,
        )
        assertFalse(
            "withSerializedNativePushWipe already holds the mutex; nesting withLock would deadlock",
            Regex("""nativePushSyncMutex\.withLock\s*\{\s*perAccountSyncedFingerprints\.remove\(wipedRef\)\s*}""")
                .containsMatchIn(body),
        )
    }

    @Test
    fun retentionSweepRoutesThroughEngineAccountWorker() {
        val body = appStateFunctionBody("runRetentionSweep")

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
        val body = appStateFunctionBody("processRetentionSweepOutcome")

        assertTrue(
            "pruned groups must dismiss tray cards only when rows were actually pruned",
            Regex("""prunedMessages\s*>\s*0uL""").containsMatchIn(body),
        )
        assertTrue(
            "disk eviction must stay ciphertext-tag scoped; only the in-memory tier may drop the whole group slice",
            "removeByCiphertextTags(expiredCiphertextSha256)" in body &&
                "diskMediaCache.remove(" !in body,
        )
    }

    @Test
    fun localExpiryRowsFeedEngineAuthoritativeExpiry() {
        // localExpiryRow is an expression-body function, so window the source
        // instead of brace-matching a block body.
        val source = controllersSource().readText()
        val start = source.indexOf("fun localExpiryRow(")
        require(start >= 0) { "Missing localExpiryRow" }

        assertTrue(
            "the loaded-row hide filter must prefer the engine's projected per-message expiry",
            "expiresAtLocalSeconds = record.retentionExpiresAt" in
                source.substring(start, (start + 800).coerceAtMost(source.length)),
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

    @Test
    fun conversationClassificationPrefersProjectedKind() {
        val source = controllersSource().readText()
        val projectedIsDm =
            Regex(
                """val\s+isDm:\s*Boolean\s*\n\s*get\(\)\s*=\s*""" +
                    """GroupProjector\.isDm\(latestChatListRow\?\.conversationKind,\s*memberCount,\s*group\.name\)""",
            )
        val projectedIsDirect =
            Regex(
                """val\s+isDirectConversation:\s*Boolean\s*\n\s*get\(\)\s*=\s*""" +
                    """GroupProjector\.isDm\(latestChatListRow\?\.conversationKind,\s*memberCount,\s*group\.name\)""",
            )

        assertTrue(
            "conversation-screen DM classification must prefer the engine's projected kind, heuristic only as fallback",
            projectedIsDm.containsMatchIn(source) && projectedIsDirect.containsMatchIn(source),
        )
    }

    @Test
    fun deleteMessageAuthorizationGuardPrecedesOptimisticAndFfiMutation() {
        val body = controllerFunctionBody("deleteMessageResult")
        val authorizationGate = body.indexOf("!deleteCapabilityFor(message).canDeleteForEveryone")
        val authorizationFailure = body.indexOf("Result.failure", startIndex = authorizationGate)
        val optimisticMutation = body.indexOf("deletedMessageIds = deletedMessageIds + target")
        val ffiDelete = body.indexOf("appState.marmotIo { deleteMessage(account, group.groupIdHex, target) }")

        assertTrue(
            "unauthorized deletes must produce a failure before mutating deletedMessageIds",
            authorizationGate >= 0 && authorizationFailure > authorizationGate && optimisticMutation > authorizationFailure,
        )
        assertTrue(
            "unauthorized deletes must produce a failure before reaching the FFI delete call",
            ffiDelete > authorizationFailure,
        )
    }

    @Test
    fun deleteMessageReturnsCommitResultAndBatchUsesIt() {
        val controllers = controllersSource().readText()
        val body = controllerFunctionBody("deleteMessageResult")
        val conversation = conversationScreenSource().readText()

        assertTrue(
            "deleteMessage must expose a Boolean commit result",
            Regex("""suspend\s+fun\s+deleteMessage\s*\([^)]*\)\s*:\s*Boolean""").containsMatchIn(controllers),
        )
        assertTrue(
            "deleteMessageResult guards must report why no commit occurred",
            "conversationAccountRef" in body &&
                "!deleteCapabilityFor(message).canDeleteForEveryone" in body &&
                "Result.failure" in body,
        )
        assertTrue(
            "deleteMessageResult must report success after the locked commit and failure after rollback",
            body.indexOf("appState.withGroupCommitLock") < body.indexOf("Result.success(Unit)") &&
                body.indexOf("deletedMessageIds = deletedMessageIds - target") < body.lastIndexOf("Result.failure"),
        )
        assertTrue(
            "deleteMessage must roll back optimistic deletion before propagating cancellation",
            body.indexOf("deletedMessageIds = deletedMessageIds - target") < body.indexOf("throwable.rethrowIfCancellation()"),
        )
        assertTrue(
            "batch deletion must retain structured commit failures without emitting one snackbar per item",
            "controller.deleteMessageResult(record, presentFailure = false)" in conversation &&
                "hideLocally = controller::hideMessageForMeResult" in conversation &&
                "BatchDeleteRetryState.from(result)" in conversation &&
                "if (presentFailure)" in body &&
                "record.messageIdHex !in controller.deletedMessageIds" !in conversation,
        )
    }

    @Test
    fun batchLocalHideRejectsMissingConversationOwner() {
        val body = controllerFunctionBody("hideMessageForMeResult")

        assertTrue(
            "batch local hide must bind to a concrete account before touching preferences or timeline state",
            body.indexOf("conversationAccountRef") < body.indexOf("appState.hideMessageForMe(account") &&
                "Result.failure" in body &&
                "Result.success(Unit)" in body,
        )
    }

    @Test
    fun batchDeleteRetryStateIsScopedToConversationOwnerAndGuardsRecomposition() {
        val conversation = conversationScreenSource().readText().replace(Regex("\\s+"), " ")
        val ownerKeys = "controller, chat.id, appState.activeAccountRef, appState.runtimeGeneration"
        val cancellationStart = conversation.indexOf("catch (cancellation: CancellationException)")
        val cancellationEnd = conversation.indexOf("finally", cancellationStart)

        assertTrue(
            "failed selections, retry state, and the submission guard must reset together when their owner changes",
            "val selectedMessages = remember($ownerKeys)" in conversation &&
                "var batchDeleteRetryState by remember($ownerKeys)" in conversation &&
                "val batchDeleteSubmissionGuard = remember($ownerKeys)" in conversation,
        )
        assertTrue(
            "recomposition or repeated taps must not start a second delete while the scoped attempt is active",
            "if (attempts.isEmpty() || !batchDeleteSubmissionGuard.tryStart()) return" in conversation &&
                "batchDeleteSubmissionGuard.finish()" in conversation,
        )
        assertTrue(
            "cancellation must reconcile completed work and propagate without publishing an error",
            cancellationStart >= 0 &&
                cancellationEnd > cancellationStart &&
                "completedOutcomes" in conversation.substring(cancellationStart, cancellationEnd) &&
                "throw cancellation" in conversation.substring(cancellationStart, cancellationEnd) &&
                "appState.present" !in conversation.substring(cancellationStart, cancellationEnd),
        )
    }

    @Test
    fun failedSendDiscardTracksPendingOnlyWhenRetryStillExists() {
        val body = controllerFunctionBody("discardFailedSend")

        assertTrue(
            "discardFailedSend must not retain a discardedDuringRetry key after the retry entry has already completed",
            "val current = optimisticMessages[key]" in body &&
                "MessageStatus.Pending -> if (current != null) discardedDuringRetry.add(key)" in body,
        )
    }

    @Test
    fun manualFailedSendRetryUsesTheSharedBoundedConnectivityPolicy() {
        val body = controllerFunctionBody("retryFailedSend")
        val lock = body.indexOf("appState.withGroupCommitLock(account, group.groupIdHex)")
        val sharedRetry = body.indexOf("publishTextWithRetry(replyTarget, account, text", startIndex = lock)

        assertTrue(
            "manual text/reply retry must reuse the bounded connect-phase policy inside the group commit lock",
            lock >= 0 && sharedRetry > lock,
        )
        assertFalse(
            "manual text/reply retry must not bypass the shared policy with direct FFI publish calls",
            "replyToMessage(account, group.groupIdHex, replyTarget, text)" in body ||
                "sendText(account, group.groupIdHex, text)" in body,
        )
    }

    @Test
    fun markLatestVisibleReadDeadPathStaysDeleted() {
        val source = controllersSource().readText()

        assertFalse(
            "markLatestVisibleRead zeroes unread state from a stale open-chat path and should stay deleted",
            Regex("""\bfun\s+markLatestVisibleRead\s*\(""").containsMatchIn(source),
        )
    }

    @Test
    fun appStateRuntimeReferenceIsVolatile() {
        val source = appStateSource().readText()

        assertTrue(
            "Marmot runtime is written on IO and read off-mutex, so visibility must be explicit",
            Regex("""@Volatile\s+private\s+var\s+marmotRuntime:\s*AppMarmotRuntime\?""").containsMatchIn(source),
        )
    }

    @Test
    fun conversationReadAnchorUsesHoistedRenderedTimelineAndCandidateIdentity() {
        val source = conversationScreenSource().readText()
        val renderedTimelineIndex = source.indexOf("val renderedTimeline =")
        val readAnchorHelperIndex = source.indexOf("private fun rememberConversationReadAnchor(")
        val readAnchorHelper =
            source.substring(
                readAnchorHelperIndex,
                source.indexOf("@OptIn(ExperimentalMaterial3Api::class)", readAnchorHelperIndex),
            )
        val readAnchorCallIndex = source.indexOf("rememberConversationReadAnchor(", renderedTimelineIndex)
        val readAnchorCall = source.substring(readAnchorCallIndex, source.indexOf(")", readAnchorCallIndex) + 1)

        assertTrue(
            "read-anchor effect must preserve the durable watermark and reuse the hoisted edit-filtered timeline",
            renderedTimelineIndex >= 0 &&
                renderedTimelineIndex < readAnchorCallIndex &&
                "renderedTimeline = renderedTimeline" in readAnchorCall &&
                "remember(listState, renderedSize, hasOlderHeader)" in readAnchorHelper &&
                "currentHighestVisibleMessageId," in readAnchorHelper &&
                "advanceConversationReadAnchor(" in readAnchorHelper &&
                "durableAnchorId = controller.lastReadMessageId" in readAnchorHelper &&
                "filterNot { MessageProjector.isEdit(it.record) }" !in readAnchorHelper,
        )
    }

    @Test
    fun conversationAnchoringLifecycleFollowsController() {
        val source = conversationScreenSource().readText()
        val entrySnapshotIndex = source.indexOf("val entryUnreadSnapshot =")
        val scrollRestoreIndex = source.indexOf("val scrollRestore =")
        val unreadJumpOwner =
            source.substring(
                source.indexOf("var unreadJumpState by"),
                source.indexOf("val scrollCoordinator ="),
            )

        assertTrue(
            "same-group account switches must reset anchoring state and cancel effects that capture the old controller",
            "remember(controller, chat.id, appState.activeAccountRef, appState.runtimeGeneration)" in unreadJumpOwner &&
                "mutableStateOf(ConversationUnreadJumpState())" in unreadJumpOwner &&
                "remember(controller, notificationOpenRequestId) { mutableStateOf(false) }" in source &&
                "val state = remember(controller) { ConversationNavigationState() }" in source &&
                "onDispose(state::cancelJobs)" in source &&
                "var lastFollowedLatestId by mutableStateOf<String?>(null)" in source &&
                "LaunchedEffect(controller, latestTimelineItemId, notificationOpenRequestId)" in source &&
                "LaunchedEffect(listState, controller)" in source,
        )
        assertTrue(
            "scroll restore must use the reconciled entry unread count rather than the raw projection",
            entrySnapshotIndex >= 0 &&
                entrySnapshotIndex < scrollRestoreIndex &&
                "entryUnreadCount = entryUnreadCount" in
                source.substring(scrollRestoreIndex, source.indexOf("val positionalScrollRestore", scrollRestoreIndex)),
        )
    }

    @Test
    fun conversationHistoryReanchorIgnoresSameRowHydration() {
        val source = conversationScreenSource().readText().replace(Regex("\\s+"), " ")

        assertTrue(
            "startup materialization and same-row media hydration must not restart durable history anchoring",
            "val renderedTimelineAnchorKeys = remember(renderedTimeline)" in source &&
                "renderedTimeline.map { it.id to it.record.messageIdHex }" in source &&
                "scrollCoordinator.commitInitialAnchor(" in source &&
                Regex("while \\(\\s*!scrollCoordinator\\.commitInitialAnchor\\(").findAll(source).count() == 2 &&
                "postInitialReanchorGate.commit(" in source &&
                "postInitialReanchorGate.onStructure(" in source &&
                "initialTimelineAnchored && structureChanged" in source &&
                "LaunchedEffect(controller, renderedTimeline, olderHeaderCount" !in source,
        )
    }

    @Test
    fun voiceAutoChainSkipsDerivedEditRows() {
        val source = conversationScreenSource().readText()
        val autoChain =
            source.substring(
                source.indexOf("Auto-chain voice playback"),
                source.indexOf("val nextMsg", source.indexOf("Auto-chain voice playback")),
            )

        assertTrue(
            "auto-chain should skip invisible edit and group-system rows before checking the immediate visible voice neighbor",
            "MessageProjector.isEdit(controller.timeline[nextIdx].record)" in autoChain &&
                "MessageProjector.isGroupSystem(controller.timeline[nextIdx].record)" in autoChain,
        )
    }

    @Test
    fun loadOlderPageRoutesPaginateThroughActiveSubscriptionGuard() {
        val loadOlder = controllerFunctionBody("loadOlderPage")
        val guard = controllerFunctionBody("paginateOlderIfSubscriptionActive")
        val controllersSource = controllersSource().readText()

        assertTrue(
            "loadOlderPage must not call paginateBackwards directly on a captured subscription",
            "paginateOlderIfSubscriptionActive(subscription)" in loadOlder && "subscription.paginateBackwards" !in loadOlder,
        )
        assertTrue(
            "paginate guard must serialize paginate against close and re-check the active timeline subscription identity",
            "timelineSubscriptionActiveCallMutex.withLock" in controllersSource &&
                "timelineSubscription === subscription" in guard &&
                "subscription.paginateBackwards(ConversationTimelinePageLimit)" in guard,
        )
    }

    @Test
    fun closeTimelineSubscriptionWaitsNonCancellablyForActiveCallMutex() {
        val close = controllerFunctionBody("closeTimelineSubscriptionSafely")

        assertTrue(
            "timeline subscription close must wrap mutex acquisition and close in NonCancellable",
            "withContext(NonCancellable)" in close &&
                close.indexOf("withContext(NonCancellable)") < close.indexOf("timelineSubscriptionActiveCallMutex.withLock") &&
                close.indexOf("timelineSubscriptionActiveCallMutex.withLock") < close.indexOf("timelineStream.close()"),
        )
    }

    @Test
    fun readAnchorsArePrunedWithTimelineWindow() {
        val applyTimelinePage = controllerFunctionBody("applyTimelinePage")
        val applyTimelineChanges = controllerFunctionBody("applyTimelineChanges")
        val trimLiveTimelineWindow = controllerFunctionBody("trimLiveTimelineWindow")
        val removeProjectedRecord = controllerFunctionBody("removeProjectedRecord")
        val pruneReadAnchorsToWindow = controllerFunctionBody("pruneReadAnchorsToWindow")

        assertTrue(
            "window replacements must prune read anchors after the authoritative page is applied",
            "pruneReadAnchorsToWindow()" in applyTimelinePage,
        )
        assertTrue(
            "live timeline changes must trim the window even after loadOlder (#1163)",
            "trimLiveTimelineWindow(LIVE_TIMELINE_WINDOW_CAP)" in applyTimelineChanges &&
                "!hasLoadedOlderPages" !in applyTimelineChanges,
        )
        assertTrue(
            "live timeline trims must prune read anchors for removed messages",
            "pruneReadAnchorsToWindow()" in trimLiveTimelineWindow,
        )
        assertTrue(
            "single-record removals must drop the removed message read anchor",
            "readAnchoredAtSeconds.remove(messageIdHex)" in removeProjectedRecord,
        )
        assertTrue(
            "read-anchor pruning must retain current timeline records and optimistic records only",
            "HashSet(timelineRecords.keys)" in pruneReadAnchorsToWindow &&
                "optimisticMessages.values.forEach" in pruneReadAnchorsToWindow &&
                "readAnchoredAtSeconds.keys.retainAll(retained)" in pruneReadAnchorsToWindow,
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
        val body = controllerFunctionBody("schedulePendingMediaKindResolves")

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

    private fun appStateFunctionBody(functionName: String): String {
        val source = appStateSource().readText()
        val start =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(source)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        return source.kotlinBlockFrom(braceStart, "function $functionName")
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")

    private fun controllerFunctionBody(functionName: String): String {
        val source = controllersSource().readText()
        val start =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(source)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        return source.kotlinBlockFrom(braceStart, "function $functionName")
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")

    private fun conversationScreenSource(): File =
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
