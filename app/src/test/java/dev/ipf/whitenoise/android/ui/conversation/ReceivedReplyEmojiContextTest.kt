package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberReplyReceivedEmoji
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Exercises actual composition against native-window updates, never a duplicate availability predicate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ReceivedReplyEmojiContextTest : PollMessageTestFixtures() {
    @get:Rule val rule = createComposeRule()
    private val result = AtomicReference(ReceivedEmoji.None)
    private var otherController: ConversationController? = null

    @After
    fun close() {
        closeProjectionStream()
        pollController.onCleared()
        otherController?.onCleared()
    }

    @Test
    fun recomposingAfterTheOriginalDeadlineDropsReplyArtwork() {
        pollState.stopAutomaticAttachmentDownloads()
        val source = emojiSource()
        val projected =
            requireNotNull(source.projected).copy(
                retentionExpiresAt = (pollClockMillis / 1_000L + 1L).toULong(),
            )
        applyPage(projected)
        val revision = mutableStateOf(0)
        rule.setContent {
            key(revision.value) {
                val emoji = rememberReplyReceivedEmoji(source.record.messageIdHex, pollController, pollState)
                SideEffect { result.set(emoji) }
                Text(if (emoji === ReceivedEmoji.None) "No source artwork" else "Source available")
            }
        }
        rule.waitUntil(5_000) { result.get().attachmentIndexes.isNotEmpty() }
        // Advance the controller's actual clock without requiring an engine-removal event.
        rule.runOnIdle {
            pollClockMillis += 2_000L
            assertTrue(pollController.isRetainedRowGone(source.record.messageIdHex))
            revision.value++
        }
        rule.waitForIdle()
        rule.onNodeWithText("No source artwork").assertExists()
        assertTrue(result.get() === ReceivedEmoji.None)
    }

    @Test
    fun deletedOriginalCannotKeepReplyArtwork() {
        pollState.stopAutomaticAttachmentDownloads()
        val source = emojiSource()
        val projected = requireNotNull(source.projected)
        applyPage(projected)
        rule.setContent {
            result.set(rememberReplyReceivedEmoji(source.record.messageIdHex, pollController, pollState))
        }
        rule.waitUntil(5_000) { result.get().attachmentIndexes.isNotEmpty() }
        applyPage(projected.copy(deleted = true))
        rule.waitForIdle()
        rule.waitUntil(5_000) { result.get() === ReceivedEmoji.None }
    }

    @Test
    fun aTargetOutsideTheVisibleWindowFallsBackWithoutBorrowingAnotherMessage() {
        pollState.stopAutomaticAttachmentDownloads()
        val source = emojiSource()
        applyPage(requireNotNull(source.projected))
        rule.setContent {
            result.set(rememberReplyReceivedEmoji(source.record.messageIdHex, pollController, pollState))
        }
        rule.waitUntil(5_000) { result.get().attachmentIndexes.isNotEmpty() }
        applyPage()
        rule.waitForIdle()
        assertTrue(pollController.timeline.isEmpty())
        rule.waitUntil(5_000) { result.get() === ReceivedEmoji.None }
    }

    @Test
    fun reusedMessageIdsInANewControllerNeverExposeTheOldDefinition() {
        pollState.stopAutomaticAttachmentDownloads()
        val source = emojiSource()
        applyPage(requireNotNull(source.projected))
        val owner = mutableStateOf(pollController)
        val frames = mutableListOf<Set<Int>>()
        rule.setContent {
            val current = owner.value
            val emoji = rememberReplyReceivedEmoji(source.record.messageIdHex, current, pollState)
            result.set(emoji)
            if (current !== pollController) frames += emoji.attachmentIndexes
        }
        rule.waitUntil(5_000) { result.get().attachmentIndexes.isNotEmpty() }
        val replacement =
            ConversationController(
                appState = pollState,
                initialGroup = group(),
                initialMemberSnapshot = memberSnapshot(),
                groupRosterReader = { _, _ -> authoritativeRoster() },
                clockMillis = { pollClockMillis },
            )
        otherController = replacement
        val noDefinition = requireNotNull(source.projected).copy(tags = emptyList(), media = emptyList())
        runBlocking {
            replacement.applyTimelinePage(
                TimelinePageFfi(listOf(noDefinition), hasMoreBefore = false, hasMoreAfter = false),
                replaceWindow = true,
                updatePagination = true,
            )
        }
        rule.runOnIdle { owner.value = replacement }
        rule.waitForIdle()
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all(Set<Int>::isEmpty))
    }

    private fun emojiSource(): TimelineMessage {
        val source =
            fileTimelineMessage(
                index = 16,
                fileName = "emoji.png",
                mine = true,
                caption = ":remote:",
                mediaType = "image/png",
            )
        val tags = source.record.tags + MessageTagFfi(listOf("emoji", "remote", "https://media.example/emoji.png"))
        return source.copy(
            record = source.record.copy(tags = tags),
            projected = requireNotNull(source.projected).copy(tags = tags),
        )
    }
}
