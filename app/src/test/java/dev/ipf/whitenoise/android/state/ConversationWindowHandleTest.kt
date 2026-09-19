package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ConversationAnchorKindFfi
import dev.ipf.marmotkit.ConversationAnchorOutcomeFfi
import dev.ipf.marmotkit.ConversationCapabilitiesFfi
import dev.ipf.marmotkit.ConversationHeaderFfi
import dev.ipf.marmotkit.ConversationMessageFfi
import dev.ipf.marmotkit.ConversationMessageReferencesFfi
import dev.ipf.marmotkit.ConversationOpenReadStateFfi
import dev.ipf.marmotkit.ConversationPageDirectionFfi
import dev.ipf.marmotkit.ConversationParticipationFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.ConversationReactionFfi
import dev.ipf.marmotkit.ConversationReactionsFfi
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.marmotkit.ConversationWindowSnapshotFfi
import dev.ipf.marmotkit.ConversationWindowSubscriptionInterface
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowHandleTest {
    /** The initial replacement becomes the seam's page and its sidecar carries references and header. */
    @Test
    fun snapshotBecomesPageAndFrame() {
        val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1", "m2")))
        val handle = FfiConversationWindowHandle(fake, release = fake::release)

        val page = handle.snapshot()
        assertEquals(listOf("m1", "m2"), page?.messages?.map { it.messageIdHex })
        assertTrue(page?.hasMoreBefore == true)
        val frame = handle.latestWindowFrame()
        assertEquals(setOf("m1", "m2"), frame?.references?.keys)
        assertTrue(frame?.header?.capabilities?.canSend == true)
    }

    /** Older or duplicate replacements are skipped by the receive loop; a newer one is delivered. */
    @Test
    fun nextWindowSkipsStaleReplacements() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 5uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()
            fake.emit(snapshot(sequence = 5uL, messageIds = listOf("dup")))
            fake.emit(snapshot(sequence = 4uL, messageIds = listOf("old")))
            fake.emit(snapshot(sequence = 6uL, messageIds = listOf("m1", "m2")))

            val next = handle.nextWindow()
            assertEquals(listOf("m1", "m2"), next?.messages?.map { it.messageIdHex })
            assertEquals(6uL, handle.latestWindowFrame()?.revision?.sequence)
        }

    /** Paging quotes the installed revision, and a stale command falls back to the newest installed page. */
    @Test
    fun pagingQuotesRevisionAndStaleFallsBack() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            val initial = handle.snapshot()

            val paged = handle.paginateBackwards(50u).pageOrCurrent()
            assertEquals(listOf(1uL to ConversationPageDirectionFfi.OLDER), fake.pageCalls)
            assertEquals(listOf("m1", "m1-older"), paged.messages.map { it.messageIdHex })

            fake.failNextCommandWith = MarmotKitException.ConversationWindowStale()
            val fallback = handle.paginateForwards(50u).pageOrCurrent()
            assertSame(paged, fallback)
            assertEquals(2uL, handle.latestWindowFrame()?.revision?.sequence)
            assertFalse(initial === fallback)
        }

    /** A missing jump target is surfaced to the caller; other window errors are absorbed. */
    @Test
    fun jumpSurfacesMissingTargetOnly() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()

            fake.failNextCommandWith = MarmotKitException.ConversationWindowNotReady()
            assertNull(handle.setVisibleAnchor("m1"))

            fake.failNextCommandWith = MarmotKitException.ConversationWindowMessageNotRetained()
            var missing = false
            try {
                handle.jumpToMessage("gone")
            } catch (expected: MarmotKitException.ConversationWindowMessageNotRetained) {
                missing = true
            }
            assertTrue(missing)
        }

    /** A malformed anchor id, such as an optimistic row's local id, is absorbed instead of escaping to the caller. */
    @Test
    fun invalidAnchorIdKeepsTheWindow() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()

            fake.failNextCommandWith = MarmotKitException.InvalidHex("Invalid character '-' at position 8")
            assertNull(handle.setVisibleAnchor("0d2b6c1e-4f6a-4b1e-9c1d-optimistic"))
            assertEquals(1uL, handle.latestWindowFrame()?.revision?.sequence)
        }

    /** The page and sidecar of one installed replacement are handed out together, never from mixed revisions. */
    @Test
    fun latestInstalledWindowPairsPageAndFrame() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()

            val paged = handle.paginateBackwards(50u).pageOrCurrent()
            val installed = handle.latestInstalledWindow()
            assertSame(paged, installed?.page)
            assertEquals(2uL, installed?.frame?.revision?.sequence)
            assertEquals(setOf("m1", "m1-older"), installed?.frame?.references?.keys)
        }

    /** A not-ready stream is retryable: the loop keeps receiving and delivers the next real replacement. */
    @Test
    fun notReadyStreamKeepsReceiving() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()
            fake.failNextReceiveWith = MarmotKitException.ConversationWindowNotReady()
            fake.emit(snapshot(sequence = 2uL, messageIds = listOf("m1", "m2")))

            val next = handle.nextWindow()
            assertEquals(listOf("m1", "m2"), next?.messages?.map { it.messageIdHex })
            assertEquals(1, fake.receiveFailures)
        }

    /** A terminal stream error ends the stream instead of throwing, so the controller reopens the window. */
    @Test
    fun terminalStreamErrorEndsTheStream() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()
            fake.failNextReceiveWith = MarmotKitException.ConversationWindowQuery("closed")

            assertNull(handle.nextWindow())
        }

    /** An initial snapshot the runtime cannot answer yields no page rather than an error. */
    @Test
    fun unavailableSnapshotYieldsNoPage() {
        val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
        fake.failNextSnapshotWith = MarmotKitException.ConversationWindowNotReady()
        val handle = FfiConversationWindowHandle(fake, release = fake::release)

        assertNull(handle.snapshot())
        assertNull(handle.latestWindowFrame())
    }

    /** Teardown never fails the caller, even when the runtime already released the window. */
    @Test
    fun teardownAbsorbsRuntimeFailures() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = emptyList()))
            val release: () -> Unit = { throw MarmotKitException.ConversationWindowQuery("gone") }
            val handle = FfiConversationWindowHandle(fake, release = release)
            fake.failCancelWith = MarmotKitException.ConversationWindowQuery("gone")

            handle.cancel()
            handle.close()
        }

    /** Cancel reaches the native window and close releases it. */
    @Test
    fun cancelAndCloseReachTheWindow() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = emptyList()))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.cancel()
            handle.close()
            assertTrue(fake.cancelled)
            assertTrue(fake.released)
        }

    /** A page that misses the window deadline reports TIMED_OUT and keeps the installed page. */
    @Test
    fun pageTimeoutReportsTimedOutAndKeepsInstalledPage() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            val initial = handle.snapshot()
            fake.failNextCommandWith = MarmotKitException.ConversationWindowTimedOut()

            val outcome = handle.paginateBackwards(50u)

            val unchanged = outcome as TimelinePageOutcome.Unchanged
            assertEquals(ConversationWindowUnchangedReason.TIMED_OUT, unchanged.reason)
            assertSame(initial, unchanged.current)
        }

    /** A not-ready page reports NOT_READY; this seam does not retry on the caller's behalf. */
    @Test
    fun pageNotReadyReportsNotReadyWithoutRetryingHere() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()
            fake.failNextCommandWith = MarmotKitException.ConversationWindowNotReady()

            val outcome = handle.paginateBackwards(50u)

            assertEquals(
                ConversationWindowUnchangedReason.NOT_READY,
                (outcome as TimelinePageOutcome.Unchanged).reason,
            )
            assertTrue(fake.pageCalls.isEmpty())
        }

    /** A superseded page reports SUPERSEDED and hands back the same installed page instance. */
    @Test
    fun pageStaleReportsSupersededWithTheSamePageInstance() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()
            val paged = handle.paginateBackwards(50u).pageOrCurrent()
            fake.failNextCommandWith = MarmotKitException.ConversationWindowStale()

            val outcome = handle.paginateForwards(50u)

            val unchanged = outcome as TimelinePageOutcome.Unchanged
            assertEquals(ConversationWindowUnchangedReason.SUPERSEDED, unchanged.reason)
            assertSame(paged, unchanged.current)
        }

    /** The page-shaped call keeps returning the installed page for every unchanged outcome. */
    @Test
    fun legacyPaginateBackwardsStillFallsBackToCurrentPage() =
        runBlocking {
            val fake = FakeConversationWindow(snapshot(sequence = 1uL, messageIds = listOf("m1")))
            val handle = FfiConversationWindowHandle(fake, release = fake::release)
            handle.snapshot()
            val paged = handle.paginateBackwards(50u).pageOrCurrent()
            fake.failNextCommandWith = MarmotKitException.ConversationWindowTimedOut()

            assertSame(paged, handle.paginateBackwards(50u).pageOrCurrent())
        }
}

/** Scripted native window: the test controls replacements and command outcomes. */
private class FakeConversationWindow(
    private var current: ConversationWindowSnapshotFfi,
) : ConversationWindowSubscriptionInterface {
    private val updates = Channel<ConversationWindowSnapshotFfi>(Channel.UNLIMITED)
    val pageCalls = mutableListOf<Pair<ULong, ConversationPageDirectionFfi>>()
    var failNextCommandWith: Throwable? = null
    var failNextReceiveWith: Throwable? = null
    var failNextSnapshotWith: Throwable? = null
    var failCancelWith: Throwable? = null
    var receiveFailures = 0
    var cancelled = false
    var released = false

    override fun snapshot(): ConversationWindowSnapshotFfi {
        failNextSnapshotWith?.let { failure ->
            failNextSnapshotWith = null
            throw failure
        }
        return current
    }

    override suspend fun next(): ConversationWindowSnapshotFfi? {
        failNextReceiveWith?.let { failure ->
            failNextReceiveWith = null
            receiveFailures += 1
            throw failure
        }
        return updates.receiveCatching().getOrNull()
    }

    override suspend fun page(
        revision: ConversationWindowRevisionFfi,
        direction: ConversationPageDirectionFfi,
        count: UInt,
        timeoutMs: UInt,
    ): ConversationWindowSnapshotFfi {
        throwScriptedFailure()
        pageCalls += revision.sequence to direction
        val existing = current.messages.map { it.timeline.messageIdHex }
        val ids = existing + "${existing.first()}-older"
        current = snapshot(sequence = revision.sequence + 1uL, messageIds = ids)
        return current
    }

    override suspend fun setVisibleAnchor(
        revision: ConversationWindowRevisionFfi,
        messageIdHex: String,
        timeoutMs: UInt,
    ): ConversationWindowSnapshotFfi {
        throwScriptedFailure()
        val existing = current.messages.map { it.timeline.messageIdHex }
        current = snapshot(sequence = revision.sequence + 1uL, messageIds = existing)
        return current
    }

    override suspend fun jumpToMessage(
        revision: ConversationWindowRevisionFfi,
        messageIdHex: String,
        timeoutMs: UInt,
    ): ConversationWindowSnapshotFfi = setVisibleAnchor(revision, messageIdHex, timeoutMs)

    override suspend fun returnToLatest(
        revision: ConversationWindowRevisionFfi,
        timeoutMs: UInt,
    ): ConversationWindowSnapshotFfi = setVisibleAnchor(revision, "", timeoutMs)

    override suspend fun cancel() {
        cancelled = true
        failCancelWith?.let { failure ->
            failCancelWith = null
            throw failure
        }
    }

    fun release() {
        released = true
        updates.close()
    }

    fun emit(snapshot: ConversationWindowSnapshotFfi) {
        check(updates.trySend(snapshot).isSuccess)
    }

    private fun throwScriptedFailure() {
        val failure = failNextCommandWith ?: return
        failNextCommandWith = null
        throw failure
    }
}

private fun snapshot(
    sequence: ULong,
    messageIds: List<String>,
    generation: String = "gen",
) = ConversationWindowSnapshotFfi(
    revision = ConversationWindowRevisionFfi(generation, sequence),
    header =
        ConversationHeaderFfi(
            selected =
                ConversationPresentationFfi(
                    title = PresentationTextFfi.Literal("Chat"),
                    avatar = SelectedAvatarFfi.Placeholder("group", PresentationSourceFfi.GROUP_FALLBACK),
                    titleSource = PresentationSourceFfi.GROUP_FALLBACK,
                    avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                    peerId = null,
                    resolution = PresentationResolutionFfi.FALLBACK,
                ),
            memberCount = 2uL,
            archived = false,
            epoch = 1uL,
            lifecycle = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            unrecoverable = false,
            avatarAsset = null,
            capabilities =
                ConversationCapabilitiesFfi(
                    participation = ConversationParticipationFfi.ACTIVE,
                    isSelfAdmin = false,
                    isLastAdmin = false,
                    canSend = true,
                    canInvite = false,
                    canEditGroup = false,
                    canLeave = true,
                    requiresSelfDemoteBeforeLeave = false,
                    canEnableDisbanding = false,
                    canDisband = false,
                ),
        ),
    messages = messageIds.map(::conversationMessage),
    identities = emptyList(),
    readState =
        ConversationOpenReadStateFfi(
            initialized = true,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            manuallyMarkedUnread = false,
            unreadCount = 0uL,
            unreadMentionCount = 0uL,
            firstUnreadMessageIdHex = null,
        ),
    draft = SelectedMessageDraftFfi(nativeStub(MessageDraftRevisionFfi::class.java), null),
    pendingConfirmation = false,
    anchor = ConversationAnchorOutcomeFfi(ConversationAnchorKindFfi.LATEST, 0u),
    hasMoreBefore = true,
    hasMoreAfter = false,
)

private fun conversationMessage(messageIdHex: String) =
    ConversationMessageFfi(
        timeline = timelineRecord(messageId = messageIdHex, timelineAt = 1uL, plaintext = messageIdHex),
        references =
            ConversationMessageReferencesFfi(
                messageIdHex = messageIdHex,
                sender = "sender",
                replyAuthor = null,
                mentions = emptyList(),
                mentionsTruncated = false,
                replyMentions = emptyList(),
                replyMentionsTruncated = false,
                system = null,
                reactions = singleReaction(),
            ),
    )

/** One thumbs-up from another member, the reference every scripted message carries. */
private fun singleReaction(): ConversationReactionsFfi {
    val thumbsUp = ConversationReactionFfi("👍", 1uL, listOf("a"), false)
    return ConversationReactionsFfi(1uL, 1uL, listOf(thumbsUp), 0uL)
}

/** Allocates a native handle stub without its constructor, so records embedding one can be built in JVM tests. */
private fun <T> nativeStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    @Suppress("UNCHECKED_CAST")
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
}
