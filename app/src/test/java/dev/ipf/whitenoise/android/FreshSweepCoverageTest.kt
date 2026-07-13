package dev.ipf.whitenoise.android

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

        assertTrue("deletions must invalidate projections", "controller.deletedMessageIds" in projectionsBlock)
        assertTrue("edits must invalidate projections", "controller.editsByTarget" in projectionsBlock)
        assertTrue("copy text must be projected once per timeline change", "MessageProjector.copyableText" in projectionsBlock)
        assertTrue("forward text must be projected once per timeline change", "MessageProjector.forwardableText" in projectionsBlock)
        assertFalse("profile changes must not rebuild text projections", "appState.profileRevisionForCompose" in projectionsBlock)
        assertTrue("selectable messages must be memoized", "val selectableMessages =\n        remember(" in selectableBlock)
        assertTrue("profile changes must refresh sender names", "appState.profileRevisionForCompose" in selectableBlock)
        assertFalse("profile changes must not re-project copy text", "MessageProjector.copyableText" in selectableBlock)
        assertFalse("profile changes must not re-project forward text", "MessageProjector.forwardableText" in selectableBlock)
        assertTrue("invalid ids must be memoized", "remember(renderedTimeline, selectableMessages)" in block)
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
        assertTrue(accept.contains("runBestEffortPostCommitSteps("))
        assertTrue(invite.indexOf("try {") < invite.indexOf("val adminTargets"))
    }

    @Test
    fun readAndReplyActionsClearRepliedCardAndSpareNewerSiblings() {
        val replyWorker = source("notifications/NotificationReplyWorker.kt")
        val actionReceiver = source("notifications/NotificationActionReceiver.kt")

        // Both paths cancel the replied/read card outright BEFORE clearing its
        // reaction/mention/invite siblings, and the sibling clear is bounded by
        // the captured action-window cutoff so newer cards survive (#1311).
        listOf(replyWorker, actionReceiver).forEach { text ->
            val cancelAt = text.indexOf("cancel(action.notificationTag, action.notificationId)")
            val siblingAt = text.indexOf("dismissConversationSiblingCardsNotNewerThan(")
            assertTrue(cancelAt in 0 until siblingAt)
            assertTrue(text.contains("sinceMs = dismissBaselineMs"))
        }
    }

    @Test
    fun notificationMarkReadConfinesAppStateMutationsToMainThread() {
        val source = source("notifications/NotificationActionReceiver.kt")
        val markReadBranch = source.section("NotificationActionKind.MARK_READ -> {", "val presenter")
        val mainConfinedMutations =
            Regex(
                """withContext\(Dispatchers\.Main\.immediate\) \{\s*""" +
                    """appState\.ensureNotificationRuntimeStarted\(\)\s*""" +
                    """appState\.markNotificationMessageRead\(""",
            )

        assertTrue(mainConfinedMutations.containsMatchIn(markReadBranch))
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
