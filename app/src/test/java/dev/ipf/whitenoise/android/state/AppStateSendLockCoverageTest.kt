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
    fun forwardTextLocksEachTargetSend() {
        val body = appStateFunctionBody("forwardText")

        assertTrue(
            "forwardText must serialize each forwarded send through the per-group commit lock",
            Regex(
                """for\s*\(\s*groupIdHex\s+in\s+targets\s*\).*""" +
                    """withGroupCommitLock\s*\(\s*account\s*,\s*groupIdHex\s*\).*""" +
                    """marmotIo\s*\{\s*sendText\s*\(\s*account\s*,\s*groupIdHex\s*,\s*trimmed\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
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
    fun backgroundDisappearingSweepLocksSecureDelete() {
        val body = appStateFunctionBody("sweepExpiredForGroup")

        assertTrue(
            "background disappearing sweeps must serialize secureDeleteExpired through the per-group commit lock",
            Regex(
                """withGroupCommitLock\s*\(\s*accountRef\s*,\s*groupIdHex\s*\).*""" +
                    """marmotIo\s*\{\s*secureDeleteExpired\s*\(\s*accountRef\s*,\s*groupIdHex\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    @Test
    fun foregroundDisappearingSweepLocksSecureDelete() {
        val body = controllerFunctionBody("runForegroundDisappearingMessageSweep")

        assertTrue(
            "foreground disappearing sweeps must serialize secureDeleteExpired through the per-group commit lock",
            Regex(
                """appState\.withGroupCommitLock\s*\(\s*account\s*,\s*group\.groupIdHex\s*\).*""" +
                    """appState\.marmotIo\s*\{\s*secureDeleteExpired\s*\(\s*account\s*,\s*group\.groupIdHex\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
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
    fun markLatestVisibleReadDeadPathStaysDeleted() {
        val source = controllersSource().readText()

        assertFalse(
            "markLatestVisibleRead zeroes unread state from a stale open-chat path and should stay deleted",
            Regex("""\bfun\s+markLatestVisibleRead\s*\(""").containsMatchIn(source),
        )
    }

    @Test
    fun appStateClientReferenceIsVolatile() {
        val source = appStateSource().readText()

        assertTrue(
            "Marmot client is written on IO and read off-mutex, so visibility must be explicit",
            Regex("""@Volatile\s+private\s+var\s+client:\s*MarmotClient\?""").containsMatchIn(source),
        )
    }

    @Test
    fun conversationReadAnchorUsesHoistedRenderedTimeline() {
        val source = conversationScreenSource().readText()
        val renderedTimelineIndex = source.indexOf("val renderedTimeline =")
        val readAnchorEffectIndex = source.indexOf("LaunchedEffect(currentHighestVisibleTimelineIndex)")
        val readAnchorEffect = source.substring(readAnchorEffectIndex, source.indexOf("DisposableEffect(chat.id)", readAnchorEffectIndex))

        assertTrue(
            "read-anchor effect must reuse the hoisted edit-filtered renderedTimeline instead of allocating a fresh filtered list on scroll",
            renderedTimelineIndex >= 0 &&
                renderedTimelineIndex < readAnchorEffectIndex &&
                "readAnchorMessageId = nextReadAnchor(renderedTimeline, readAnchorMessageId, idx)" in readAnchorEffect &&
                "filterNot { MessageProjector.isEdit(it.record) }" !in readAnchorEffect,
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
        val trimLiveTimelineWindow = controllerFunctionBody("trimLiveTimelineWindow")
        val removeProjectedRecord = controllerFunctionBody("removeProjectedRecord")
        val pruneReadAnchorsToWindow = controllerFunctionBody("pruneReadAnchorsToWindow")

        assertTrue(
            "window replacements must prune read anchors after the authoritative page is applied",
            "pruneReadAnchorsToWindow()" in applyTimelinePage,
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
