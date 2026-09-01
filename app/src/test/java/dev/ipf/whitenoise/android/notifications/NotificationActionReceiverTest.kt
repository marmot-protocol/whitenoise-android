package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executes [NotificationActionReceiver.onReceive] through a real broadcast
 * dispatch: a valid action must land as durable WorkManager work and the
 * receiver must finish its async result inside the broadcast deadline, while
 * malformed or unauthorized input enqueues nothing.
 *
 * The receiver's `goAsync()` handoff requires a framework-provided pending
 * result, so every dispatch goes through `Context.sendBroadcast` rather than a
 * direct `onReceive` call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
class NotificationActionReceiverTest {
    private lateinit var application: WhiteNoiseApplication

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
        seedNotificationReplyCipherForTests()
        // Keep the synchronously-executed mark-read work observable (retrying,
        // not finished) without reaching the engine.
        application.appState.setAppLockScreenVisibleForTest(true)
    }

    /**
     * The framework PendingResult offers no completion observability under
     * Robolectric, so pending-result completion is proven through the
     * orchestration seam with an injected finish: exactly one finish on
     * success, on a throwing enqueue, and when the enqueue overruns the
     * receiver budget.
     */
    @Test
    fun orchestrationFinishesExactlyOnceOnSuccessFailureAndDeadline() {
        val receiver = NotificationActionReceiver()
        val action = testNotificationAction(NotificationActionKind.MARK_READ)
        val intent = markReadIntent(action)

        val successFinishes =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        receiver.launchActionOrchestration(
            appContext = application,
            action = action,
            intent = intent,
            finish = { successFinishes.incrementAndGet() },
            dispatchAction = {},
        )
        awaitFinishCount("a successful enqueue must finish the pending result", successFinishes)

        val failureFinishes =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        receiver.launchActionOrchestration(
            appContext = application,
            action = action,
            intent = intent,
            finish = { failureFinishes.incrementAndGet() },
            dispatchAction = { error("enqueue exploded") },
        )
        awaitFinishCount("a throwing enqueue must still finish the pending result", failureFinishes)

        val deadlineFinishes =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        receiver.launchActionOrchestration(
            appContext = application,
            action = action,
            intent = intent,
            finish = { deadlineFinishes.incrementAndGet() },
            budgetMs = 50L,
            dispatchAction = { kotlinx.coroutines.awaitCancellation() },
        )
        awaitFinishCount("an overrunning enqueue must finish at the receiver budget", deadlineFinishes)
        assertEquals(1, successFinishes.get())
        assertEquals(1, failureFinishes.get())
    }

    /**
     * Waits in real wall-clock time: the orchestration runs on real Default
     * threads, so a virtual-time `runTest` loop would burn its timeout budget
     * instantly on a slow runner before those threads ever get scheduled.
     */
    private fun awaitFinishCount(
        description: String,
        finishes: java.util.concurrent.atomic.AtomicInteger,
    ) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (finishes.get() != 1 && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            Thread.sleep(10L)
        }
        check(finishes.get() == 1) { description }
    }

    @Test
    fun aValidMarkReadBroadcastEnqueuesOneDurableUniqueWorkAndFinishes() =
        runTest {
            val action = testNotificationAction(NotificationActionKind.MARK_READ)

            dispatch(markReadIntent(action))

            awaitWorkerCondition("mark-read work must be enqueued within the broadcast deadline") {
                unfinishedUniqueWork(NotificationMarkReadWorker.notificationMarkReadWorkName(action)) == 1
            }
        }

    @Test
    fun aRepeatedMarkReadBroadcastKeepsTheExistingWork() =
        runTest {
            val action = testNotificationAction(NotificationActionKind.MARK_READ)

            dispatch(markReadIntent(action))
            dispatch(markReadIntent(action))

            awaitWorkerCondition("repeated broadcasts must coalesce into one durable work item") {
                unfinishedUniqueWork(NotificationMarkReadWorker.notificationMarkReadWorkName(action)) == 1
            }
        }

    @Test
    fun aBroadcastWithoutTargetExtrasEnqueuesNothing() =
        runTest {
            val bare =
                Intent(NotificationActions.ACTION_MARK_READ)
                    .setClass(application, NotificationActionReceiver::class.java)

            dispatch(bare)

            assertEquals(0, allReceiverWorkCount())
        }

    @Test
    fun anUnknownActionNameIsIgnored() =
        runTest {
            val action = testNotificationAction(NotificationActionKind.MARK_READ)
            val intent = markReadIntent(action).setAction("dev.ipf.whitenoise.android.action.UNRELATED")

            dispatch(intent)

            assertEquals(0, allReceiverWorkCount())
        }

    @Test
    fun aReplyBroadcastWithoutRemoteInputEnqueuesNothing() =
        runTest {
            // The app lock would defer a reply before RemoteInput is read, so
            // release it for the reply paths.
            application.appState.setAppLockScreenVisibleForTest(false)
            val action = testNotificationAction(NotificationActionKind.REPLY)
            val intent = Intent()
            NotificationActions.applyToIntent(intent, NotificationActionKind.REPLY, actionTarget(action))
            intent.setClass(application, NotificationActionReceiver::class.java)

            dispatch(intent)

            assertEquals(0, allReceiverWorkCount())
        }

    @Test
    fun aReplyBroadcastWithTextEnqueuesDurableReplyWork() =
        runTest {
            application.appState.setAppLockScreenVisibleForTest(false)
            val action = testNotificationAction(NotificationActionKind.REPLY)
            val intent = Intent()
            NotificationActions.applyToIntent(intent, NotificationActionKind.REPLY, actionTarget(action))
            intent.setClass(application, NotificationActionReceiver::class.java)
            addRemoteInputResults(intent, NotificationActions.KEY_TEXT_REPLY, "hello from the shade")

            dispatch(intent)

            awaitWorkerCondition("the reply must be persisted as WorkManager work") {
                replyWorkCount() == 1
            }
        }

    @Test
    fun aReactionChoiceOutsideTheConfiguredAllowlistIsRejected() =
        runTest {
            application.appState.setAppLockScreenVisibleForTest(false)
            val action = testNotificationAction(NotificationActionKind.REACT)
            val intent = Intent()
            NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, actionTarget(action))
            intent.setClass(application, NotificationActionReceiver::class.java)
            addRemoteInputResults(intent, NotificationActions.KEY_REACTION_CHOICE, "🚀")

            dispatch(intent)

            assertEquals(0, allReceiverWorkCount())
        }

    @Test
    fun anAllowedReactionChoiceEnqueuesOneUniqueReactionWork() =
        runTest {
            application.appState.setAppLockScreenVisibleForTest(false)
            val action = testNotificationAction(NotificationActionKind.REACT)
            val intent = Intent()
            NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, actionTarget(action))
            intent.setClass(application, NotificationActionReceiver::class.java)
            addRemoteInputResults(intent, NotificationActions.KEY_REACTION_CHOICE, "👍")

            dispatch(intent)

            awaitWorkerCondition("the reaction must be persisted as unique WorkManager work") {
                unfinishedUniqueWork(NotificationReactionWorker.notificationReactionWorkName(action)) == 1
            }
        }

    /**
     * Delivers [intent] as a real broadcast and pumps until the receiver's
     * `goAsync` scope has had a chance to run its enqueue work off-thread.
     */
    private suspend fun dispatch(intent: Intent) {
        application.sendBroadcast(intent)
        // Give the receiver's Default-dispatcher enqueue path time to run;
        // negative assertions re-check after this bounded settle.
        pumpingMainLooper { kotlinx.coroutines.delay(150L) }
    }

    private fun markReadIntent(action: NotificationAction): Intent {
        val intent = Intent()
        NotificationActions.applyToIntent(intent, NotificationActionKind.MARK_READ, actionTarget(action))
        return intent.setClass(application, NotificationActionReceiver::class.java)
    }

    private fun actionTarget(action: NotificationAction) =
        NotificationActionTarget(
            target = action.target,
            notificationTag = action.notificationTag,
            notificationId = action.notificationId,
        )

    private fun addRemoteInputResults(
        intent: Intent,
        key: String,
        value: String,
    ) {
        val results = Bundle().apply { putCharSequence(key, value) }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(key).build()),
            intent,
            results,
        )
    }

    private fun unfinishedUniqueWork(name: String): Int =
        WorkManager
            .getInstance(application)
            .getWorkInfosForUniqueWork(name)
            .get()
            .count { !it.state.isFinished }

    private fun replyWorkCount(): Int =
        WorkManager
            .getInstance(application)
            .getWorkInfosByTag(NotificationReplyWorker::class.java.name)
            .get()
            .size

    private fun allReceiverWorkCount(): Int =
        listOf(
            NotificationMarkReadWorker::class.java.name,
            NotificationReactionWorker::class.java.name,
            NotificationReplyWorker::class.java.name,
        ).sumOf { tag ->
            WorkManager
                .getInstance(application)
                .getWorkInfosByTag(tag)
                .get()
                .size
        }
}
