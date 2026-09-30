package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Real registered-show leases and final write admission across asynchronous group dismissal. */
@RunWith(RobolectricTestRunner::class)
class NotificationGroupGenerationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun summaryFenceRejectsRepresentedPendingPostsButPreservesLaterArrivals() =
        runBlocking {
            val before = CompletableDeferred<ConversationCardShowToken>()
            val unseen = CompletableDeferred<ConversationCardShowToken>()
            val after = CompletableDeferred<ConversationCardShowToken>()
            val release = CompletableDeferred<Unit>()
            val old = async {
                ConversationCardPostSynchronizer.withRegisteredShow("account|old", 0, ConversationCardScope("account", "old")) {
                    before.complete(it)
                    release.await()
                }
            }
            val oldToken = withTimeout(5_000) { before.await() }
            val unseenPost = async {
                ConversationCardPostSynchronizer.withRegisteredShow("other-account|unseen", 0, ConversationCardScope("other-account", "unseen")) {
                    unseen.complete(it)
                    release.await()
                }
            }
            val unseenToken = withTimeout(5_000) { unseen.await() }
            val fence = NotificationCardGenerations.captureFence()
            val later = async {
                ConversationCardPostSynchronizer.withRegisteredShow("account|new", 0, ConversationCardScope("account", "new")) {
                    after.complete(it)
                    release.await()
                }
            }
            try {
                val laterToken = withTimeout(5_000) { after.await() }
                NotificationCardGenerations.dismissThrough(fence, listOf(NotificationGroupChild("account|old", 0, "visible-generation")))
                assertFalse(ConversationCardPostSynchronizer.isShowNotDismissed(oldToken))
                assertTrue(ConversationCardPostSynchronizer.isShowNotDismissed(unseenToken))
                assertTrue(NotificationGroupReconciler.postChild(context, notification(unseenToken), request = {}) {})
                assertFalse(NotificationGroupReconciler.postChild(context, notification(oldToken), request = {}) { error("dismissed write reached the platform") })
                assertTrue(ConversationCardPostSynchronizer.isShowNotDismissed(laterToken))
                var posted = false
                assertTrue(NotificationGroupReconciler.postChild(context, notification(laterToken), request = {}) { posted = true })
                assertTrue(posted)
            } finally {
                release.complete(Unit)
                withTimeout(5_000) { old.await(); unseenPost.await(); later.await() }
            }
        }

    @Test
    fun aPreviousProcessFenceCannotInvalidateTheCurrentProcessesWrites() {
        val generation = NotificationCardGenerations.register("account|group", 0)
        try {
            NotificationCardGenerations.dismissThrough(NotificationGroupDismissalFence("another-process", Long.MAX_VALUE), listOf(NotificationGroupChild("account|group", 0, generation.id)))
            assertFalse(generation.dismissed.get())
        } finally {
            NotificationCardGenerations.release(generation)
        }
    }

    @Test
    fun alreadyRemovedLargeGroupsSpendNoWriteTokens() =
        runTest {
            val pacer = NotificationPostPacer(burstCapacity = 1, sleep = { error("absent cards consumed write slots") })
            val targets = List(50) { NotificationGroupChild("account|group-$it", 0, "generation-$it") }
            dismissNotificationGroupGenerations(context, targets, pacer = pacer, read = { emptyArray() }, request = {})
            // The sole initial token remains available for an actual platform write.
            assertTrue(pacer.awaitSlot() == 0L)
        }

    private fun notification(token: ConversationCardShowToken): Notification =
        NotificationCompat.Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            .addExtras(android.os.Bundle().apply { putString(UserEventNotificationGroup.EXTRA_GENERATION, token.notificationGeneration.id) })
            .build()
}
