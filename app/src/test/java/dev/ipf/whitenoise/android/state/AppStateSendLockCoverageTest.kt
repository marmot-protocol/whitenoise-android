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
    fun acceptedPendingNotificationReplyPersistsItsCanonicalIdBeforeReturning() {
        val body = appStateFunctionBody("sendNotificationReply")
        val sendIndex = body.indexOf("val summary = marmotIo { sendText(account, group, body) }")
        val persistIndex = body.indexOf("completionStore.markCommittedMessage", startIndex = sendIndex)
        val acceptedPendingIndex = body.indexOf("NotificationReplySendOutcome.AcceptedPending", startIndex = sendIndex)

        assertTrue(
            "an accepted-pending reply must persist MDK's canonical id before reporting " +
                "success, so crash recovery cannot resend it",
            sendIndex >= 0 && persistIndex > sendIndex && acceptedPendingIndex > persistIndex,
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
    fun failedDestructiveWipeBranchesExitBeforeProcessGlobalProfileCachesAreCleared() {
        val body = appStateFunctionBody("signOutAndWipeActiveAccount")
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

    /** Guards the engine-owned projected deadline contract used by loaded-row expiry checks. */
    @Test
    fun localExpiryRowsFeedEngineAuthoritativeExpiry() {
        // localExpiryRow is an expression-body function, so window the source
        // instead of brace-matching a block body.
        val source = controllersSource().readText()
        val start = source.indexOf("fun localExpiryRow(")
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
    fun failedSendDiscardRetainsCallbackOnlyWhileRetryStillExists() {
        val body = controllerFunctionBody("discardFailedSend")
        val pendingRetryBranch =
            Regex(
                """MessageStatus\.Pending\s*->\s*if \(current != null\) \{\s*""" +
                    """discardedDuringRetry\.add\(key\)\s*\}\s*else \{\s*""" +
                    """durableAcceptanceCallbacks\.remove\(key\)""",
            )

        assertTrue(
            "discardFailedSend must retain callback cleanup only while a pending retry can still accept",
            "val current = optimisticMessages[key]" in body &&
                pendingRetryBranch.containsMatchIn(body),
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

    /** Keeps read-watermark math tied to the edit-filtered timeline and every structural prefix row. */
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
                "remember(listState, renderedSize, hasOlderHeader, hasInlineTopError)" in readAnchorHelper &&
                "currentHighestVisibleMessageId," in readAnchorHelper &&
                "advanceConversationReadAnchor(" in readAnchorHelper &&
                "durableAnchorId = controller.lastReadMessageId" in readAnchorHelper &&
                "filterNot { MessageProjector.isEdit(it.record) }" !in readAnchorHelper,
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
                "scrollCoordinator.commitInitialTailAnchor(" in source &&
                "while (!commitInitialPosition())" in source &&
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

    internal fun controllerFunctionBody(functionName: String): String {
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

    internal fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")

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
