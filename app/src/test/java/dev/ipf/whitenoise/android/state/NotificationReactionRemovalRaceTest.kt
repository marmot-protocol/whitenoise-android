package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Reactions cannot restore a removed target's excerpt through a queued initial or correction write (#3323). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationReactionRemovalRaceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val reactionKey = LocalNotificationFormatter.reactionDismissalKey(ACCOUNT, GROUP)

    /** Grants posting and starts each production-path race with an empty tray. */
    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    /** Releases the shared test hook and removes fixture notifications. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager.cancelAll()
    }

    /** A local hide wins after an initial reaction's content is resolved but before its final platform fence. */
    @Test
    fun localHideFencesAQueuedReactionFirstWrite() = assertRace(localHide = true, lateCorrection = false)

    /** An accepted native deletion wins against the same queued first-write ordering. */
    @Test
    fun acceptedDeletionFencesAQueuedReactionFirstWrite() = assertRace(localHide = false, lateCorrection = false)

    /** A locally hidden excerpt cannot return through a delayed silent text correction. */
    @Test
    fun localHideFencesAQueuedReactionCorrection() = assertRace(localHide = true, lateCorrection = true)

    /** A native deletion also fences a correction that resolved its excerpt before the removal. */
    @Test
    fun acceptedDeletionFencesAQueuedReactionCorrection() = assertRace(localHide = false, lateCorrection = true)

    /** Evicting the accepted target removal cannot revive a reaction that already captured its quote. */
    @Test
    fun acceptedDeletionFencesAnEvictedQueuedReactionFirstWrite() {
        assertRace(localHide = false, lateCorrection = false, evictRemoval = true)
    }

    /** The captured removal version also survives eviction before a queued silent correction. */
    @Test
    fun acceptedDeletionFencesAnEvictedQueuedReactionCorrection() {
        assertRace(localHide = false, lateCorrection = true, evictRemoval = true)
    }

    /** A local hide also invalidates the already-captured quote after its bounded identity is evicted. */
    @Test
    fun localHideFencesAnEvictedQueuedReactionFirstWrite() {
        assertRace(localHide = true, lateCorrection = false, evictRemoval = true)
    }

    /** A queued correction cannot outlive a local hide by waiting for removal-identity eviction. */
    @Test
    fun localHideFencesAnEvictedQueuedReactionCorrection() {
        assertRace(localHide = true, lateCorrection = true, evictRemoval = true)
    }

    /** A matching queued reaction without a removal reaches the platform and retains its valid quote. */
    @Test
    fun queuedReactionWithoutRemovalStillPostsItsQuote() =
        runBlocking {
            val fixture = fixture(CountDownLatch(0))
            val probe = ReactionWriteProbe(lateCorrection = false)
            try {
                fixture.bootstrap()
                ConversationCardPostSynchronizer.testHook = probe
                fixture.emitUpdate(reaction(fixture.update))
                awaitLatch(probe.queued)
                probe.release.countDown()
                awaitReactionText(SECRET)
                awaitLatch(probe.written)
                assertTrue(probe.writes.get() > 0)
            } finally {
                probe.release.countDown()
                fixture.close()
            }
        }

    /** An unrelated chat's deletion strips only the opaque quote from a reaction already being prepared. */
    @Test
    fun deletionInAnotherChatStillPostsTheQueuedReactionWithoutItsQuote() =
        runBlocking {
            val fixture = fixture(CountDownLatch(0))
            val probe = ReactionWriteProbe(lateCorrection = false)
            try {
                fixture.bootstrap()
                ConversationCardPostSynchronizer.testHook = probe
                fixture.emitUpdate(reaction(fixture.update))
                awaitLatch(probe.queued)
                fixture.appState.recordNotificationContentRemoval(
                    RemovedNotificationMessage(ACCOUNT, "other-chat", TARGET),
                )
                probe.removalObserved.set(true)
                probe.release.countDown()
                awaitLatch(probe.written)
                assertTrue("the reaction itself remains posted", activeReactionText().isNotEmpty())
                assertFalse(activeReactionText().any { SECRET in it })
                assertEquals(0, probe.quotedWritesAfterRemoval.get())
            } finally {
                probe.release.countDown()
                fixture.close()
            }
        }

    /** A removal's quote-free retry cannot obtain a fresh dismissal lifetime and resurrect a dismissed card. */
    @Test
    fun quoteFreeRetryDoesNotOutliveItsOriginalDismissalLifetime() =
        runBlocking {
            val fixture = fixture(CountDownLatch(0))
            val probe = ReactionWriteProbe(lateCorrection = false)
            try {
                fixture.bootstrap()
                ConversationCardPostSynchronizer.testHook = probe
                fixture.emitUpdate(reaction(fixture.update))
                awaitLatch(probe.queued)
                fixture.appState.recordNotificationContentRemoval(
                    RemovedNotificationMessage(ACCOUNT, "other-chat", TARGET),
                )
                ConversationCardPostSynchronizer.markDismissed(reactionKey.tag, reactionKey.id)
                probe.release.countDown()
                fixture.awaitUpdateConsumed()
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                while (System.nanoTime() < until) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
                assertTrue(activeReactionText().isEmpty())
                assertEquals(0, probe.writes.get())
            } finally {
                probe.release.countDown()
                fixture.close()
            }
        }

    /** Picture-only enrichment may not clone a live quote after its original removal fence becomes stale. */
    @Test
    fun aRemovalAtTheAvatarWriteNeverRepublishesTheLiveQuote() =
        runBlocking {
            val release = CountDownLatch(1)
            val fetchStarted = CountDownLatch(1)
            val reached = CountDownLatch(1)
            val removed = AtomicBoolean(false)
            val copiedQuoteWrites = AtomicInteger()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    groupIdHex = GROUP,
                    messageIdHex = TARGET,
                    isDm = true,
                    emitStartupNotification = false,
                    senderPictureUrl = "https://example.com/quoted-avatar.png",
                    localDisplayName = "Alice",
                    profileImageDownload = { _, _ ->
                        fetchStarted.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.MAGENTA)
                        ByteArrayOutputStream().use { out ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                            out.toByteArray()
                        }
                    },
                )
            try {
                AvatarImageLoader.clear()
                fixture.bootstrap()
                fixture.emitUpdate(reaction(fixture.update))
                awaitReactionText(SECRET)
                awaitLatch(fetchStarted)
                ConversationCardPostSynchronizer.testHook =
                    avatarRemovalProbe(fixture, removed, reached, copiedQuoteWrites)
                release.countDown()
                awaitLatch(reached)
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                while (System.nanoTime() < until) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
                assertEquals("inspect writes while the old live card still exists", 0, copiedQuoteWrites.get())
                assertTrue("no cancellation hides a stale rewrite", activeReactionText().any { SECRET in it })
            } finally {
                release.countDown()
                fixture.close()
                AvatarImageLoader.clear()
                AvatarImageLoader.resetProfileImageFetcherForTests()
            }
        }

    /** Commits only the removal mark at the actual final platform boundary, then audits every later write. */
    private fun avatarRemovalProbe(
        fixture: NotificationBootstrapTestFixture,
        removed: AtomicBoolean,
        reached: CountDownLatch,
        quotedWrites: AtomicInteger,
    ): ConversationCardTestHook =
        object : ConversationCardTestHook {
            /** Marks before the last permission callback and counts any actual stale payload written afterwards. */
            override fun onBarrier(
                op: ConversationCardOp,
                barrier: ConversationCardBarrier,
                notificationTag: String,
                notificationId: Int,
            ) {
                if (notificationTag != reactionKey.tag || notificationId != reactionKey.id) return
                if (barrier == ConversationCardBarrier.BEFORE_PLATFORM_WRITE && removed.compareAndSet(false, true)) {
                    fixture.appState.recordNotificationContentRemoval(
                        RemovedNotificationMessage(ACCOUNT, GROUP, TARGET),
                    )
                    reached.countDown()
                }
                if (barrier == ConversationCardBarrier.AFTER_WRITE && removed.get()) {
                    if (activeReactionText().any { SECRET in it }) quotedWrites.incrementAndGet()
                }
            }
        }

    /** Holds the exact final write, commits the removal, then proves no post-removal payload carries the quote. */
    private fun assertRace(
        localHide: Boolean,
        lateCorrection: Boolean,
        evictRemoval: Boolean = false,
    ) = runBlocking {
        val identityReady = CountDownLatch(if (lateCorrection) 1 else 0)
        val fixture = fixture(identityReady, lateCorrection)
        val probe = ReactionWriteProbe(lateCorrection)
        try {
            fixture.bootstrap()
            fixture.awaitRuntimeEventSubscription()
            ConversationCardPostSynchronizer.testHook = probe
            fixture.emitUpdate(reaction(fixture.update))
            if (lateCorrection) {
                awaitReactionText(SECRET)
                identityReady.countDown()
            }
            awaitLatch(probe.queued)
            probe.removalRequested.set(true)
            val hide =
                if (localHide) {
                    async { hideTarget(fixture) }
                } else {
                    fixture.emitRuntimeEvent(deletion())
                    null
                }
            // Retraction marks the removal before it tries to acquire a card lock held by the queued write.
            awaitLatch(probe.removalWaiting)
            probe.removalObserved.set(true)
            if (evictRemoval) evictTargetRemoval(fixture)
            probe.release.countDown()
            hide?.let { assertTrue(it.await()) }
            awaitLatch(probe.removalCompleted)
            if (evictRemoval) assertOtherScopeControls(fixture) else assertScopeControls(fixture)
            assertEquals("no queued reaction quote may land after the removal", 0, probe.quotedWritesAfterRemoval.get())
            val payloadText = manager.activeNotifications.flatMap { allText(it.notification.extras) }
            assertFalse(payloadText.any { SECRET in it })
        } finally {
            identityReady.countDown()
            probe.release.countDown()
            fixture.close()
        }
    }

    /** Commits the local hide through the main-owned production action while the test pumps its looper. */
    private suspend fun hideTarget(fixture: NotificationBootstrapTestFixture): Boolean =
        fixture.runOnMainLooperPumping { fixture.appState.hideMessageForMe(ACCOUNT, GROUP, TARGET) }

    /** Admits the receipt loop's removals without waiting on platform locks held by the queued quote. */
    private fun evictTargetRemoval(fixture: NotificationBootstrapTestFixture) {
        repeat(513) { index ->
            fixture.appState.recordNotificationContentRemoval(
                RemovedNotificationMessage(ACCOUNT, "ee00${index.toString(16).padStart(4, '0')}", TARGET),
            )
        }
    }

    /** A later reaction remains usable without the unverifiable quote, and other scopes keep their valid quotes. */
    private suspend fun assertScopeControls(fixture: NotificationBootstrapTestFixture) {
        fixture.emitUpdate(
            reaction(fixture.update).copy(
                messageIdHex = FRESH_REACTION,
                notificationKey = "reaction:$FRESH_REACTION",
            ),
        )
        awaitReactionText("👍", expectedMessageId = FRESH_REACTION)
        assertFalse(activeReactionText().any { SECRET in it })
        assertOtherScopeControls(fixture)
    }

    /** Fresh notifications in other conversations and accounts keep valid quotes after the removal burst. */
    private suspend fun assertOtherScopeControls(fixture: NotificationBootstrapTestFixture) {
        val otherGroup =
            reaction(fixture.update).copy(
                groupIdHex = OTHER_GROUP,
                notificationKey = "reaction:$OTHER_GROUP",
                reactedToPreview = "other group quote",
            )
        fixture.runOnMainLooperPumping { fixture.appState.processNotificationUpdateForTest(otherGroup) }
        val otherAccount =
            reaction(fixture.update).copy(
                accountRef = OTHER_ACCOUNT,
                accountIdHex = OTHER_ACCOUNT,
                notificationKey = "reaction:$OTHER_ACCOUNT",
                reactedToPreview = "other account quote",
            )
        fixture.runOnMainLooperPumping { fixture.appState.processNotificationUpdateForTest(otherAccount) }
        val payloadText = manager.activeNotifications.flatMap { allText(it.notification.extras) }
        assertTrue(payloadText.any { "other group quote" in it })
        assertTrue(payloadText.any { "other account quote" in it })
    }

    /** Unknown first-draw sender identity forces a real late correction once the controlled lookup completes. */
    private fun fixture(
        identityReady: CountDownLatch,
        lateCorrection: Boolean = false,
    ) = NotificationBootstrapTestFixture(
        context = context,
        groupIdHex = GROUP,
        messageIdHex = TARGET,
        emitStartupNotification = false,
        notificationUsersHaveDisplayNames = false,
        localDisplayName = if (lateCorrection) null else "Alice",
        onDisplayName = { _, _ ->
            check(identityReady.await(5, TimeUnit.SECONDS))
            "Alice"
        },
    )

    /** A reaction has its own event identity, distinct from the removed quoted target. */
    private fun reaction(update: NotificationUpdateFfi) =
        update.copy(
            messageIdHex = REACTION,
            notificationKey = "reaction:$REACTION",
            reactionEmoji = "👍",
            reactedToPreview = SECRET,
        )

    /** Accepted deletion of the quoted target, delivered through the production runtime-event listener. */
    private fun deletion(): MarmotEventFfi {
        val record =
            requireNotNull(projectedTimelineMessage(timelineAppMessage(TARGET, 12uL)).projected).copy(deleted = true)
        return MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = ACCOUNT,
                accountLabel = ACCOUNT,
                update =
                    TimelineProjectionUpdateFfi(
                        groupIdHex = GROUP,
                        messages = emptyList(),
                        changes =
                            listOf(TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.MESSAGE_DELETED, record)),
                        chatListRow = null,
                        chatListTrigger = ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
                    ),
            ),
        )
    }

    /** Waits for a card's actual payload while advancing the paused main looper. */
    private suspend fun awaitReactionText(
        text: String,
        expectedMessageId: String? = null,
    ) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (true) {
                val card = activeReactionCard()
                val messageId =
                    card?.extras?.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX)
                val sameMessage = expectedMessageId == null || messageId == expectedMessageId
                val containsText =
                    card
                        ?.extras
                        ?.let(::allText)
                        .orEmpty()
                        .any { text in it }
                if (sameMessage && containsText) return@withTimeout
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Every text payload field of the target reaction card, not just its collapsed preview. */
    private fun activeReactionText(): List<String> =
        activeReactionCard()
            ?.extras
            ?.let(::allText)
            .orEmpty()

    /** The actual reaction card for this test's account and conversation. */
    private fun activeReactionCard(): Notification? =
        manager.activeNotifications.firstOrNull { it.tag == reactionKey.tag && it.id == reactionKey.id }?.notification

    /** Inspects strings recursively, including expanded payload and nested extras. */
    private fun allText(bundle: Bundle): List<String> =
        bundle.keySet().flatMap { key ->
            @Suppress("DEPRECATION")
            when (val value = bundle.get(key)) {
                is CharSequence -> listOf(value.toString())
                is Bundle -> allText(value)
                is Array<*> ->
                    value.flatMap {
                        when (it) {
                            is Bundle -> allText(it)
                            is CharSequence -> listOf(it.toString())
                            else -> emptyList()
                        }
                    }
                else -> emptyList()
            }
        }

    /** Advances main while waiting for a deterministic background checkpoint. */
    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (latch.count > 0L) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Holds one initial/correction write and observes the removal mark before its blocked retraction lock. */
    private inner class ReactionWriteProbe(
        private val lateCorrection: Boolean,
    ) : ConversationCardTestHook {
        val queued = CountDownLatch(1)
        val release = CountDownLatch(1)
        val removalWaiting = CountDownLatch(1)
        val removalCompleted = CountDownLatch(1)
        val written = CountDownLatch(1)
        val removalRequested = AtomicBoolean(false)
        val removalObserved = AtomicBoolean(false)
        val writes = AtomicInteger(0)
        val quotedWritesAfterRemoval = AtomicInteger(0)
        private val attempts = AtomicInteger(0)

        /** A removal is already marked before any of its card locks are requested. */
        override fun onAwaitingLock(
            op: ConversationCardOp,
            notificationTag: String,
            notificationId: Int,
        ) {
            val scope = LocalNotificationFormatter.conversationScopeOfCard(notificationTag, notificationId)
            if (removalRequested.get() && op == ConversationCardOp.DISMISS_CANCEL && scope == (ACCOUNT to GROUP)) {
                removalWaiting.countDown()
            }
        }

        /** The old reaction card has been retracted before a later quote-free control is submitted. */
        override fun onLockReleased(
            op: ConversationCardOp,
            notificationTag: String,
            notificationId: Int,
        ) {
            val sameCard = notificationTag == reactionKey.tag && notificationId == reactionKey.id
            if (removalRequested.get() && op == ConversationCardOp.DISMISS_CANCEL && sameCard) {
                removalCompleted.countDown()
            }
        }

        /** The final permission callback runs after this checkpoint and immediately before the platform post. */
        override fun onBarrier(
            op: ConversationCardOp,
            barrier: ConversationCardBarrier,
            notificationTag: String,
            notificationId: Int,
        ) {
            val sameCard = notificationTag == reactionKey.tag && notificationId == reactionKey.id
            if (op != ConversationCardOp.SHOW_NOTIFY || !sameCard) return
            val heldAttempt = if (lateCorrection) 2 else 1
            if (barrier == ConversationCardBarrier.BEFORE_PLATFORM_WRITE && attempts.incrementAndGet() == heldAttempt) {
                queued.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            if (barrier == ConversationCardBarrier.AFTER_WRITE) {
                writes.incrementAndGet()
                if (removalObserved.get() && activeReactionText().any { SECRET in it }) {
                    quotedWritesAfterRemoval.incrementAndGet()
                }
                written.countDown()
            }
        }
    }

    private companion object {
        const val ACCOUNT = "account-a"
        const val OTHER_ACCOUNT = "account-b"
        const val GROUP = "ab12cd34"
        const val OTHER_GROUP = "ab12cd3456"
        val TARGET = "ab".repeat(32)
        val REACTION = "cd".repeat(32)
        val FRESH_REACTION = "ef".repeat(32)
        const val SECRET = "removed quoted secret"
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
