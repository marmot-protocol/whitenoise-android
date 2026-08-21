package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.ConversationEntryUnreadSnapshot
import dev.ipf.whitenoise.android.ui.conversation.conversationReadAnchorCandidateIndex
import dev.ipf.whitenoise.android.ui.conversation.hasSentMessageAfterUnreadBoundary
import dev.ipf.whitenoise.android.ui.conversation.loadConversationTimelineToNewest
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationEntryUnreadSnapshot
import dev.ipf.whitenoise.android.ui.conversation.resolveConversationEntryUnreadMessageId
import dev.ipf.whitenoise.android.ui.conversation.shouldCommitConversationInitialAnchor
import dev.ipf.whitenoise.android.ui.conversation.shouldShowConversationEntryUnreadDivider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationEntryUnreadSnapshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun entryDividerRemainsVisibleAfterLiveUnreadCountReachesZero() {
        assertEquals(
            true,
            shouldShowConversationEntryUnreadDivider(
                entryUnreadCount = 20,
                dividerRetired = false,
                messageId = "first-unread",
                firstUnreadMessageId = "first-unread",
            ),
        )
    }

    @Test
    fun retiredEntryDividerDoesNotReappearForNewMessages() {
        assertEquals(
            false,
            shouldShowConversationEntryUnreadDivider(
                entryUnreadCount = 20,
                dividerRetired = true,
                messageId = "first-unread",
                firstUnreadMessageId = "first-unread",
            ),
        )
    }

    @Test
    fun controllerSwitchRecomputesUnreadSnapshotWhenBothTimelinesAreNonEmpty() {
        val controllerIdentity = mutableStateOf<Any>(Any())
        val timeline = mutableStateOf(listOf(received("a1"), received("a2")))
        val readAnchor = mutableStateOf<String?>("a2")
        val snapshot = arrayOfNulls<ConversationEntryUnreadSnapshot>(1)

        composeRule.setContent {
            snapshot[0] =
                rememberConversationEntryUnreadSnapshot(
                    controllerIdentity = controllerIdentity.value,
                    projectionUnread = 2,
                    projectionFirstUnreadMessageId = null,
                    timeline = timeline.value,
                    readAnchorMessageId = readAnchor.value,
                )
        }
        composeRule.waitForIdle()
        assertEquals(ConversationEntryUnreadSnapshot(count = 0, firstUnreadMessageId = null), snapshot[0])

        composeRule.runOnUiThread {
            controllerIdentity.value = Any()
            timeline.value = listOf(received("b1"), received("b2"))
            readAnchor.value = null
        }
        composeRule.waitForIdle()

        assertEquals(ConversationEntryUnreadSnapshot(count = 2, firstUnreadMessageId = "b1"), snapshot[0])
    }

    @Test
    fun authoritativeBoundaryIsFrozenBeforeItsPageLoads() {
        val timeline = mutableStateOf(emptyList<TimelineMessage>())
        val projectionUnread = mutableStateOf(75)
        val projectionFirstUnread = mutableStateOf<String?>("oldest-unread")
        val snapshot = arrayOfNulls<ConversationEntryUnreadSnapshot>(1)

        composeRule.setContent {
            snapshot[0] =
                rememberConversationEntryUnreadSnapshot(
                    controllerIdentity = this,
                    projectionUnread = projectionUnread.value,
                    projectionFirstUnreadMessageId = projectionFirstUnread.value,
                    timeline = timeline.value,
                    readAnchorMessageId = "last-read",
                )
        }
        composeRule.waitForIdle()
        assertEquals(
            ConversationEntryUnreadSnapshot(count = 75, firstUnreadMessageId = "oldest-unread"),
            snapshot[0],
        )

        composeRule.runOnUiThread {
            projectionUnread.value = 0
            projectionFirstUnread.value = null
            timeline.value = listOf(received("newer-unread"))
        }
        composeRule.waitForIdle()

        assertEquals(
            ConversationEntryUnreadSnapshot(count = 75, firstUnreadMessageId = "oldest-unread"),
            snapshot[0],
        )
    }

    @Test
    fun transientZeroBeforeProjectionHydrationDoesNotHideUnreadBoundary() {
        val timeline = mutableStateOf(listOf(received("first-unread"), received("newest")))
        val projectionUnread = mutableStateOf(0)
        val projectionFirstUnread = mutableStateOf<String?>(null)
        val projectionAvailable = mutableStateOf(false)
        val snapshot = arrayOfNulls<ConversationEntryUnreadSnapshot>(1)

        composeRule.setContent {
            snapshot[0] =
                rememberConversationEntryUnreadSnapshot(
                    controllerIdentity = this,
                    projectionUnread = projectionUnread.value,
                    projectionFirstUnreadMessageId = projectionFirstUnread.value,
                    projectionAvailable = projectionAvailable.value,
                    timeline = timeline.value,
                    readAnchorMessageId = null,
                )
        }
        composeRule.waitForIdle()
        assertEquals(
            ConversationEntryUnreadSnapshot(
                count = 0,
                firstUnreadMessageId = null,
                projectionCaptured = false,
            ),
            snapshot[0],
        )

        composeRule.runOnUiThread {
            projectionUnread.value = 2
            projectionFirstUnread.value = "first-unread"
            projectionAvailable.value = true
        }
        composeRule.waitForIdle()

        assertEquals(
            ConversationEntryUnreadSnapshot(count = 2, firstUnreadMessageId = "first-unread"),
            snapshot[0],
        )

        composeRule.runOnUiThread {
            // Simulate the durable mark-read row landing immediately after the
            // notification destination captured its entry projection.
            projectionUnread.value = 0
            projectionFirstUnread.value = null
        }
        composeRule.waitForIdle()

        assertEquals(
            ConversationEntryUnreadSnapshot(count = 2, firstUnreadMessageId = "first-unread"),
            snapshot[0],
        )
    }

    @Test
    fun provisionalOpenWaitsForProjectionBeforeInitialAnchor() {
        assertEquals(
            false,
            shouldCommitConversationInitialAnchor(
                hasRenderedTimeline = true,
                projectionAvailable = false,
                initialTimelineAnchored = false,
                hasScrollRestore = false,
            ),
        )
        assertEquals(
            true,
            shouldCommitConversationInitialAnchor(
                hasRenderedTimeline = true,
                projectionAvailable = true,
                initialTimelineAnchored = false,
                hasScrollRestore = false,
            ),
        )
    }

    @Test
    fun authoritativeBoundaryIsPagedInBeforeInitialAnchor() =
        runTest {
            var timeline = listOf(received("newest"))
            val requestedIds = mutableListOf<String>()

            val resolved =
                resolveConversationEntryUnreadMessageId(
                    snapshot = ConversationEntryUnreadSnapshot(51, "oldest-unread"),
                    timeline = { timeline },
                    loadUntilMessageAvailable = { id ->
                        requestedIds += id
                        timeline = listOf(received("oldest-unread"), received("newest"))
                        true
                    },
                )

            assertEquals("oldest-unread", resolved)
            assertEquals(listOf("oldest-unread"), requestedIds)
        }

    @Test
    fun failedAuthoritativeLoadFallsBackToLoadedUnreadBoundary() =
        runTest {
            val timeline = listOf(received("fallback"), received("newest"))

            val resolved =
                resolveConversationEntryUnreadMessageId(
                    snapshot = ConversationEntryUnreadSnapshot(2, "missing"),
                    timeline = { timeline },
                    loadUntilMessageAvailable = { false },
                )

            assertEquals("fallback", resolved)
        }

    @Test
    fun hiddenInitialLayoutCannotAdvanceReadAnchor() {
        assertEquals(-1, conversationReadAnchorCandidateIndex(false, 12))
        assertEquals(12, conversationReadAnchorCandidateIndex(true, 12))
    }

    @Test
    fun newerOutgoingMessageRetiresFrozenUnreadBoundary() {
        assertEquals(
            true,
            hasSentMessageAfterUnreadBoundary(
                timeline = listOf(received("u1"), received("u2"), sent("reply")),
                firstUnreadMessageId = "u1",
            ),
        )
        assertEquals(
            false,
            hasSentMessageAfterUnreadBoundary(
                timeline = listOf(sent("older-reply"), received("u1"), received("u2")),
                firstUnreadMessageId = "u1",
            ),
        )
    }

    @Test
    fun jumpToBottomLoadsThePhysicalNewestWindow() =
        runTest {
            var remainingPages = 3
            var loads = 0

            val reachedNewest =
                loadConversationTimelineToNewest(
                    hasMoreAfter = { remainingPages > 0 },
                    loadNewer = {
                        loads += 1
                        remainingPages -= 1
                        true
                    },
                )

            assertEquals(true, reachedNewest)
            assertEquals(3, loads)
        }

    @Test
    fun jumpToBottomHasNoArbitraryConversationPageLimit() =
        runTest {
            var remainingPages = 41
            var loads = 0

            val reachedNewest =
                loadConversationTimelineToNewest(
                    hasMoreAfter = { remainingPages > 0 },
                    loadNewer = {
                        loads += 1
                        remainingPages -= 1
                        true
                    },
                )

            assertEquals(true, reachedNewest)
            assertEquals(41, loads)
        }

    @Test
    fun jumpToBottomStopsWhenForwardPagingMakesNoProgress() =
        runTest {
            var loads = 0

            val reachedNewest =
                loadConversationTimelineToNewest(
                    hasMoreAfter = { true },
                    loadNewer = {
                        loads += 1
                        false
                    },
                )

            assertEquals(false, reachedNewest)
            assertEquals(1, loads)
        }

    private fun received(id: String): TimelineMessage =
        message(
            id = id,
            direction = "received",
            status = MessageStatus.Received,
        )

    private fun sent(id: String): TimelineMessage =
        message(
            id = id,
            direction = "sent",
            status = MessageStatus.Sent,
        )

    private fun message(
        id: String,
        direction: String,
        status: MessageStatus,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = direction,
                    groupIdHex = "group",
                    sender = "bob",
                    plaintext = "text-$id",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = status,
        )
}
