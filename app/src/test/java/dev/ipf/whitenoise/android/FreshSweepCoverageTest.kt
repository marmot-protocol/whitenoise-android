package dev.ipf.whitenoise.android

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import dev.ipf.whitenoise.android.ui.common.relativeTimeRefreshDelayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class FreshSweepCoverageTest {
    @Test
    fun conversationSelectionDerivationsAreRememberedByTheirRealInputs() {
        val source = source("ui/conversation/ConversationScreen.kt")
        val block = source.substring(source.indexOf("val renderedTimeline ="), source.indexOf("LaunchedEffect(", source.indexOf("val renderedTimeline =")))

        assertTrue("selectable message projections must be memoized", "val selectableMessageProjections =\n        remember(" in block)
        val projectionsStart = block.indexOf("val selectableMessageProjections =")
        val selectableStart = block.indexOf("val selectableMessages =", projectionsStart)
        val projectionsBlock = block.substring(projectionsStart, selectableStart)
        val selectableBlock = block.substring(selectableStart)
        val rememberStart = projectionsBlock.indexOf("remember(")
        val rememberEnd = projectionsBlock.indexOf(") {", rememberStart)
        assertTrue(
            "selectable projections remember inputs must be present",
            rememberStart >= 0 && rememberEnd > rememberStart,
        )
        val rememberInputs = projectionsBlock.substring(rememberStart, rememberEnd)

        assertTrue("deletions must invalidate projections", "controller.deletedMessageIds" in projectionsBlock)
        assertTrue("edits must invalidate projections", "controller.editsByTarget" in projectionsBlock)
        assertTrue("retention expiry must invalidate projections", "eligibilityNowSeconds" in rememberInputs)
        assertTrue("copy text must be projected once per timeline change", "MessageProjector.copyableText" in projectionsBlock)
        assertTrue("forward text must be projected once per timeline change", "MessageProjector.forwardableText" in projectionsBlock)
        assertTrue(
            "convergence-invalidated text must remain available to bulk copy",
            "copyableText =" in projectionsBlock &&
                "if (persistedFailure)" in projectionsBlock &&
                "MessageProjector.copyableText(record, editedText)" in projectionsBlock,
        )
        assertTrue(
            "convergence-invalidated text must remain unavailable to bulk forward",
            (
                "forwardableText = if (invalidated) null else " +
                    "MessageProjector.forwardableText(record, editedText)"
            ) in projectionsBlock,
        )
        assertFalse("profile changes must not rebuild text projections", "appState.profileRevisionForCompose" in projectionsBlock)
        // The whole-timeline selectable map must never key on the profile
        // revision: that re-ran an O(n) rebuild on every profile resolution
        // anywhere. Names are resolved only for the selected few, downstream.
        assertFalse(
            "profile changes must not rebuild the selectable map",
            "appState.profileRevisionForCompose" in selectableBlock,
        )
        assertFalse("profile changes must not re-project copy text", "MessageProjector.copyableText" in selectableBlock)
        assertFalse("profile changes must not re-project forward text", "MessageProjector.forwardableText" in selectableBlock)
        assertTrue("invalid ids must be memoized", "remember(renderedTimeline, selectableMessages)" in block)

        val actionItemsStart = source.indexOf("private fun rememberConversationBatchSelectionUiState(")
        assertTrue("batch selection UI state helper must exist", actionItemsStart >= 0)
        val actionItemsEnd = source.indexOf("/**\n * Read anchor", actionItemsStart)
        val actionItemsBlock = source.substring(actionItemsStart, actionItemsEnd)
        assertTrue(
            "profile changes must refresh names for the selected set",
            "appState.profileRevisionForCompose" in actionItemsBlock,
        )
        assertTrue("names resolve lazily on the selected set", "appState.displayName(" in actionItemsBlock)
        assertFalse("selection must not re-project copy text", "MessageProjector.copyableText" in actionItemsBlock)
        assertTrue("copy text must derive from cached action items", "batchCopyText(actionItems)" in actionItemsBlock)
    }

    @Test
    fun conversationUnreadDerivationsRebindToTheVisibleController() {
        val source =
            source("ui/conversation/ConversationScreen.kt")
                .section("val unreadIncomingCount by", "// Reading the raw IME inset")
                .replace(Regex("\\s+"), " ")

        assertTrue(source.contains("val unreadIncomingCount by remember(controller, chat.id)"))
        assertTrue(
            source.contains(
                "val mentionDetectionCache = remember(controller, chat.id, selfAccountIdHex) { MentionDetectionCache() }",
            ),
        )
        assertTrue(
            source.contains(
                "val unreadMentionMessageIds by remember(controller, chat.id, selfAccountIdHex, mentionDetectionCache)",
            ),
        )
        assertTrue(source.contains("mentionDetectionCache.getOrCompute(msg.record.messageIdHex, msg.record.contentTokens)"))
    }

    @Test
    fun mediaCacheFileProbesRunOffTheComposeThread() {
        val video = source("ui/conversation/media/MediaVideo.kt")
        val voice = source("ui/conversation/media/MediaVoice.kt")

        assertFalse(Regex("remember\\([^)]*\\) \\{\\s*cachedVideoAttachmentFile").containsMatchIn(video))
        assertFalse(Regex("remember\\([^)]*\\) \\{\\s*cachedVoiceAttachmentFile").containsMatchIn(voice))
        assertTrue(video.helperBody("rememberCachedVideoAttachmentFileState").contains("withContext(Dispatchers.IO)"))
        assertTrue(voice.helperBody("rememberCachedVoiceAttachmentFileState").contains("withContext(Dispatchers.IO)"))
        assertEquals(3, Regex("rememberCachedVideoAttachmentFileState\\(").findAll(video).count() - 1)
        assertEquals(1, Regex("rememberCachedVoiceAttachmentFileState\\(").findAll(voice).count() - 1)
    }

    @Test
    fun twoStepLeavesAndInviteFailuresStayInsideTheirSafetyBoundaries() {
        val source = source("state/Controllers.kt")
        val chatListLeave = source.section("suspend fun leaveGroup(groupIdHex", "suspend fun deleteGroupLocal")
        val conversationLeave = source.section("suspend fun leaveGroup(displayName", "fun dismissConversationNotifications")
        val accept = source.section("suspend fun acceptInvite", "suspend fun declineInvite")
        val invite = source.section("suspend fun inviteMembers", "suspend fun removeMember")

        assertTrue(chatListLeave.contains("withContext(NonCancellable)"))
        assertTrue(conversationLeave.contains("withContext(NonCancellable)"))
        assertTrue(accept.indexOf("acceptGroupInvite") < accept.indexOf("refreshMembers()"))
        assertTrue(invite.indexOf("try {") < invite.indexOf("val adminTargets"))
    }

    /** Successful acceptance still settles UI state before best-effort warmup, with account fencing. */
    @Test
    fun acceptInviteAcknowledgesSuccessBeforeLifecycleScopedWarmup() {
        val body = source("state/Controllers.kt").kotlinFunctionBody("acceptInvite")
        val resolution = source("state/Controllers.kt").kotlinFunctionBody("resolveInviteAcceptance")
        val optimisticProjection = body.indexOf("group = optimisticGroup")
        val optimisticLocalUpdate = body.indexOf("applyLocalGroupUpdate(optimisticGroup")
        val accept = body.indexOf("resolveInviteAcceptance(attempt)")
        val nativeAccept = resolution.indexOf("inviteAcceptor(")
        val confirmedProjection = body.indexOf("group = acceptedGroup")
        val confirmedLocalUpdate = body.indexOf("applyLocalGroupUpdate(group", confirmedProjection)
        val dismiss = body.indexOf("dismissConversationNotifications")
        val clearSelfLeft = body.indexOf("clearSelfLeft()")
        val toast = body.indexOf("toast_invite_accepted")
        val warmupLaunch = body.indexOf("inviteStreamScope.launch")
        val postCommit = body.indexOf("runBestEffortPostCommitSteps(")
        val members = body.indexOf("\"members\" to { refreshMembers() }")
        val timeline = body.indexOf("refreshCurrentTimeline(account)")
        val watcher = body.indexOf("watchAgentTextStream(account, streamId)")
        val readState = body.indexOf("\"read-state\" to { initializeReadState(account) }")

        assertTrue(
            "optimistic projection must precede its local snapshot",
            optimisticProjection in 0 until optimisticLocalUpdate,
        )
        assertTrue("native resolution must retain the fenced invite acceptor", nativeAccept >= 0)
        assertTrue("optimistic snapshot must precede the engine accept", optimisticLocalUpdate < accept)
        assertTrue("engine accept must precede the confirmed projection", accept < confirmedProjection)
        assertTrue("confirmed projection must precede its local snapshot", confirmedProjection < confirmedLocalUpdate)
        assertTrue("confirmed snapshot must precede notification dismissal", confirmedLocalUpdate < dismiss)
        assertTrue("notification dismissal must precede clearing the self-left latch", dismiss < clearSelfLeft)
        assertTrue("self-left latch must clear before durable success is shown", clearSelfLeft < toast)
        assertTrue("success must precede the post-accept warm-up launch", toast < warmupLaunch)
        assertTrue("warm-up must run from the controller lifecycle scope", warmupLaunch < postCommit)
        assertTrue("member refresh must remain background work", postCommit < members)
        assertTrue("timeline refresh must remain background work", postCommit < timeline)
        assertTrue("stream watchers must start from background work", postCommit < watcher)
        assertTrue("read-state initialization must remain background work", postCommit < readState)
    }

    @Test
    fun notificationReplyEncryptionLeavesReceiverMainThreadAndReportsFailure() {
        val receiver = source("notifications/NotificationActionReceiver.kt")
        val onReceive = receiver.section("override fun onReceive(", "private suspend fun enqueueReplyAction")
        val enqueueReply = receiver.section("private suspend fun enqueueReplyAction", "private suspend fun enqueueMarkReadAction")

        assertTrue("reply handling must be protected by goAsync", "val pending = goAsync()" in onReceive)
        assertFalse(
            "reply handling must not return before goAsync",
            "if (action.kind == NotificationActionKind.REPLY)" in onReceive,
        )
        assertTrue("keystore work must run on the IO dispatcher", "withContext(Dispatchers.IO)" in enqueueReply)
        assertTrue("enqueue failures must be visible to the user", "R.string.toast_send_failed" in enqueueReply)
        val worker = source("notifications/NotificationReplyWorker.kt")
        val workerEnqueue = worker.section("suspend fun enqueue(", "internal fun shouldRetryAfterFailure")
        assertTrue("WorkManager enqueue must be awaited without blocking the receiver thread", ".await()" in workerEnqueue)
        assertFalse("receiver timeout must remain cancellation-aware", ".get()" in workerEnqueue)
    }

    @Test
    fun notificationActionsAreDurableAndMessagingUpdatesDoNotPreCancel() {
        val receiver = source("notifications/NotificationActionReceiver.kt")
        val markReadEnqueue = receiver.section("private suspend fun enqueueMarkReadAction", "\n}")
        val markReadWorker = source("notifications/NotificationMarkReadWorker.kt")
        val replyWorker = source("notifications/NotificationReplyWorker.kt")
        val presenter = source("notifications/LocalNotificationPresenter.kt")
        val mainConfinedMutations =
            Regex(
                """withContext\(Dispatchers\.Main\.immediate\) \{\s*""" +
                    """application\.appState\.ensureNotificationRuntimeStarted\(\)\s*""" +
                    """application\.appState\.markNotificationMessageRead\(""",
            )

        assertTrue("mark-read broadcasts must only enqueue durable work", "NotificationMarkReadWorker.enqueue" in markReadEnqueue)
        assertTrue("mark-read mutations must remain main-confined", mainConfinedMutations.containsMatchIn(markReadWorker))
        // Locked-deferral and KEEP-dedupe behavior is executed directly by
        // NotificationMarkReadWorkerTest, replacing the former source-text pins.
        // Encrypted-defer versus legacy-plaintext-failure behind the app lock
        // is executed directly by NotificationReplyWorkerTest, replacing the
        // former source-text pin of the lock branch.
        assertFalse(
            "a lock race must not terminally record a dropped reply as success",
            "markAbandoned(completionKey, NotificationReplyAbandonedOutcome.Success)" in replyWorker,
        )
        assertTrue(
            "mark-read cleanup must preserve a newer conversation-card generation",
            "cancelConversationCardIfSameGeneration(notificationTag, notificationId, actedMessageIdHex)" in presenter,
        )
        val postPath = presenter.section("if (messaging != null)", "notificationDebug {")
        val firstPostAttempt = postPath.indexOf("postNotificationSafely(")
        val firstFailureCleanup = postPath.indexOf("notificationManager.cancel(")
        assertTrue("messaging updates must attempt notify before any failure cleanup", firstPostAttempt >= 0)
        assertTrue(
            "routine notification updates must not cancel before notify",
            firstFailureCleanup < 0 || firstFailureCleanup > firstPostAttempt,
        )
    }

    @Test
    fun relativeTimeRefreshesOnResumeAndMinuteBoundaries() {
        val source = source("ui/common/CopyBundles.kt")

        assertTrue(source.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(source.contains("Lifecycle.Event.ON_PAUSE"))
        assertTrue(source.contains("delay(relativeTimeRefreshDelayMillis(Instant.now()))"))
        assertEquals(60_000L, relativeTimeRefreshDelayMillis(Instant.ofEpochMilli(120_000L)))
        assertEquals(1L, relativeTimeRefreshDelayMillis(Instant.ofEpochMilli(179_999L)))
    }

    @Test
    fun retainedMediaFilesAreRevalidatedBeforePlayback() {
        val voice = source("ui/conversation/media/MediaVoice.kt")
        val video = source("ui/conversation/media/MediaVideo.kt")
        val voiceBubble = voice.section("internal fun MediaVoiceBubble(", "private fun VoiceSpeedPill(")
        val videoBubble = video.section("internal fun MediaVideoBubble(", "internal fun cachedVideoAttachmentFile(")
        val videoGridTile = video.section("internal fun MediaVideoGridTile(", "internal fun MediaVideoBubble(")

        assertTrue(
            "voice playback must rematerialize a retained file that LRU eviction removed",
            "validatedAttachmentCacheFile(file)" in voiceBubble &&
                "localFile = null" in voiceBubble &&
                "controller.requestAttachmentOpen(messageIdHex, attachmentIndex)" in voiceBubble &&
                "persistedAttachmentOpenEffect(" in voiceBubble,
        )
        assertTrue(
            "video playback must rematerialize a retained file that LRU eviction removed",
            "validatedAttachmentCacheFile(localFile)" in videoBubble &&
                "localFile = null" in videoBubble &&
                "controller.requestAttachmentOpen(messageIdHex, attachmentIndex)" in videoBubble &&
                "persistedAttachmentOpenEffect(" in videoBubble,
        )
        assertTrue(
            "album video tiles must rematerialize an evicted retained file before opening",
            "validatedAttachmentCacheFile(localFile)" in videoGridTile &&
                "localFile = null" in videoGridTile &&
                "controller.requestAttachmentOpen(messageIdHex, attachmentIndex)" in videoGridTile &&
                "persistedAttachmentOpenEffect(" in videoGridTile &&
                "onTap(playableFile)" in videoGridTile,
        )
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)?.readText()
            ?: error("Missing source file: $relativePath")

    private fun String.section(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        check(startIndex >= 0 && endIndex > startIndex) { "Missing section $start .. $end" }
        return substring(startIndex, endIndex)
    }

    private fun String.helperBody(name: String): String {
        val start = indexOf("private fun $name")
        val end = indexOf("\n}", start)
        check(start >= 0 && end > start) { "Missing helper $name" }
        return substring(start, end + 2)
    }
}
