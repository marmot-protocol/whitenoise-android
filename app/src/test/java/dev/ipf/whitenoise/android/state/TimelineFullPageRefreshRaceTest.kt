package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * Regression for #1849: a full-page [ConversationController.refreshCurrentTimeline]
 * must not replace in-memory state when a newer live subscription projection
 * landed while the FFI page was in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class TimelineFullPageRefreshRaceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    /** Discarding a stale preparation must leave both live rows and commit callback untouched. */
    fun newerLiveProjectionWinsWhileOlderWindowPreparationIsSuspended() =
        runTest {
            val preparationDispatcher = PausedDispatcher()
            val controller = conversationController(preparationDispatcher)
            try {
                var committed = false
                val staleApply =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.applyTimelinePage(
                            staleOnlyRefreshPage(),
                            replaceWindow = true,
                            updatePagination = true,
                            onCommitted = { committed = true },
                        )
                    }

                assertFalse("window preparation must suspend off the caller", staleApply.isCompleted)
                applyLiveProjectionDuringRefresh(controller)
                preparationDispatcher.runPending()
                advanceUntilIdle()

                assertEquals(emptyList<String>(), staleApply.await())
                assertFalse("stale preparations must not report a committed page", committed)
                val timelineIds = controller.timeline.map { it.record.messageIdHex }.toSet()
                assertTrue(NEW_MESSAGE_ID in timelineIds)
                assertFalse(OLD_MESSAGE_ID in timelineIds)
            } finally {
                controller.onCleared()
            }
        }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending += block
        }

        /** Resumes the suspended preparation after the live projection has landed. */
        fun runPending() {
            while (true) {
                pending.poll()?.run() ?: return
            }
        }
    }

    @Test
    /** A stale FFI refresh must preserve a newer stream watcher and its live row. */
    fun staleFullPageRefreshDoesNotDropNewerLiveProjectionOrStreamWatcher() =
        runBlocking {
            val controller = conversationController()
            val staleOnlyPage = staleOnlyRefreshPage()
            val refreshAwaitingLiveGate = CompletableDeferred<Unit>()
            val releaseStalePage = CompletableDeferred<Unit>()

            val refreshJob =
                async {
                    controller.testRefreshCurrentTimeline(ACCOUNT_REF) {
                        refreshAwaitingLiveGate.complete(Unit)
                        releaseStalePage.await()
                        staleOnlyPage
                    }
                }

            refreshAwaitingLiveGate.await()
            val liveStreamId = applyLiveProjectionDuringRefresh(controller)
            releaseStalePage.complete(Unit)
            val streamIdsFromStaleRefresh = refreshJob.await()
            shadowOf(Looper.getMainLooper()).idle()

            assertLiveTimelineSurvivesStaleRefresh(controller, liveStreamId, streamIdsFromStaleRefresh)
        }

    private fun applyLiveProjectionDuringRefresh(controller: ConversationController): String {
        val liveStreamId = "stream-live-1849"
        controller.testApplyLiveTimelineChangesAndRegisterStreams(
            listOf(
                TimelineMessageChangeFfi.Upsert(
                    trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                    message =
                        timelineRecord(
                            messageId = STREAM_START_ID,
                            timelineAt = 2uL,
                            kind = 1200uL,
                            tags = listOf(MessageProjector.streamTag(liveStreamId)),
                        ),
                ),
                TimelineMessageChangeFfi.Upsert(
                    trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                    message =
                        timelineRecord(
                            messageId = NEW_MESSAGE_ID,
                            timelineAt = 3uL,
                        ),
                ),
            ),
        )
        return liveStreamId
    }

    private fun assertLiveTimelineSurvivesStaleRefresh(
        controller: ConversationController,
        liveStreamId: String,
        streamIdsFromStaleRefresh: List<String>,
    ) {
        val timelineIds = controller.timeline.map { it.record.messageIdHex }.toSet()
        assertTrue(
            "live plain message must remain after stale refresh",
            NEW_MESSAGE_ID in timelineIds,
        )
        assertTrue(
            "live stream-start row must remain after stale refresh",
            STREAM_START_ID in timelineIds,
        )
        assertTrue(
            "stream watcher registration must survive stale refresh",
            liveStreamId in controller.testActiveStreamIds(),
        )
        assertEquals(
            "stale full-page refresh must not return stream watcher ids",
            emptyList<String>(),
            streamIdsFromStaleRefresh,
        )
    }

    private fun staleOnlyRefreshPage(): TimelinePageFfi {
        val staleStreamId = "stream-stale-1849"
        return timelinePage(
            timelineRecord(
                messageId = OLD_MESSAGE_ID,
                timelineAt = 1uL,
            ),
            timelineRecord(
                messageId = STALE_STREAM_START_ID,
                timelineAt = 0uL,
                kind = 1200uL,
                tags = listOf(MessageProjector.streamTag(staleStreamId)),
            ),
        )
    }

    /** Builds a controller whose preparation dispatcher can be paused at the race boundary. */
    private fun conversationController(dispatcher: CoroutineDispatcher? = null): ConversationController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { ACCOUNT_ID },
                profileReader = { null },
                accounts =
                    listOf(
                        AccountSummaryFfi(
                            label = ACCOUNT_REF,
                            accountIdHex = ACCOUNT_ID,
                            localSigning = true,
                            externalSigning = false,
                            signedOut = false,
                            running = true,
                        ),
                    ),
                activeAccountRef = ACCOUNT_REF,
            )
        return if (dispatcher == null) {
            ConversationController(appState = appState, initialGroup = group())
        } else {
            ConversationController(
                appState = appState,
                initialGroup = group(),
                windowPreparationDispatcher = dispatcher,
            )
        }
    }

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Race group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "04".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private fun timelinePage(vararg messages: TimelineMessageRecordFfi): TimelinePageFfi =
        TimelinePageFfi(
            messages = messages.toList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
        )

    private fun timelineRecord(
        messageId: String,
        timelineAt: ULong,
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            clientToken = null,
            messageIdHex = messageId,
            sourceMessageIdHex = messageId,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = "peer",
            plaintext = "body-$messageId",
            contentTokens = emptyMarkdown(),
            kind = kind,
            tags = tags,
            timelineAt = timelineAt,
            receivedAt = timelineAt,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            hasReports = false,
            edit = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )

    private fun emptyMarkdown(): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = ByteArray(0),
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    companion object {
        private const val ACCOUNT_REF = "alice"
        private val ACCOUNT_ID = "aa".repeat(32)
        private val GROUP_ID = "bb".repeat(32)
        private const val OLD_MESSAGE_ID = "msg-old"
        private const val NEW_MESSAGE_ID = "msg-new-live"
        private const val STREAM_START_ID = "msg-stream-start-live"
        private const val STALE_STREAM_START_ID = "msg-stream-start-stale"
    }
}
