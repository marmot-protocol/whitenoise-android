package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.EXTRA_GENERATION
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    fun displayedGenerationDismissalPreservesPendingMessagesOnSameAndOtherKeys() =
        runBlocking {
            val shown = CompletableDeferred<ConversationCardShowToken>()
            val sameKey = CompletableDeferred<ConversationCardShowToken>()
            val unseen = CompletableDeferred<ConversationCardShowToken>()
            val release = CompletableDeferred<Unit>()
            val old =
                holdShow(
                    "account|group",
                    ConversationCardScope("account", "group"),
                    shown,
                    release,
                )
            val oldToken = withTimeout(5_000) { shown.await() }
            val sameKeyPost =
                holdShow(
                    "account|group",
                    ConversationCardScope("account", "group"),
                    sameKey,
                    release,
                )
            val unseenPost =
                holdShow(
                    "other-account|unseen",
                    ConversationCardScope("other-account", "unseen"),
                    unseen,
                    release,
                )
            try {
                val sameKeyToken = withTimeout(5_000) { sameKey.await() }
                val unseenToken = withTimeout(5_000) { unseen.await() }
                NotificationCardGenerations.dismiss(oldToken.notificationGeneration.id)
                assertFalse(ConversationCardPostSynchronizer.isShowNotDismissed(oldToken))
                assertFalse(
                    NotificationGroupReconciler.postChild(
                        context,
                        notification(oldToken),
                        request = {},
                    ) { error("dismissed rewrite reached the platform") },
                )
                listOf(sameKeyToken, unseenToken).forEach { pending ->
                    assertTrue(ConversationCardPostSynchronizer.isShowNotDismissed(pending))
                    assertTrue(NotificationGroupReconciler.postChild(context, notification(pending), request = {}) {})
                }
            } finally {
                release.complete(Unit)
                withTimeout(5_000) {
                    old.await()
                    sameKeyPost.await()
                    unseenPost.await()
                }
            }
        }

    @Test
    fun aPreviousProcessesDeleteGenerationCannotInvalidateCurrentWrites() {
        val generation = NotificationCardGenerations.register()
        try {
            NotificationCardGenerations.dismiss("previous-process-generation")
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

    private fun CoroutineScope.holdShow(
        tag: String,
        conversation: ConversationCardScope,
        ready: CompletableDeferred<ConversationCardShowToken>,
        release: CompletableDeferred<Unit>,
    ) = async {
        ConversationCardPostSynchronizer.withRegisteredShow(tag, 0, conversation) {
            ready.complete(it)
            release.await()
        }
    }

    private fun notification(token: ConversationCardShowToken): Notification =
        NotificationCompat
            .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            .addExtras(android.os.Bundle().apply { putString(EXTRA_GENERATION, token.notificationGeneration.id) })
            .build()
}
