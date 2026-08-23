package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.app.RemoteInput
import androidx.work.WorkInfo
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.work.WorkerHarnessFixtures
import dev.ipf.whitenoise.android.work.WorkerTestHarness
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = WhiteNoiseApplication::class, sdk = [36])
class NotificationActionReceiverHarnessTest {
    private lateinit var harness: WorkerTestHarness

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        harness = WorkerTestHarness(application)
        harness.appState.workerTestHooks?.ensureNotificationRuntimeStarted = {}
        harness.appState.workerTestHooks?.sendNotificationReply = { _, _, _, _, _, _, _ ->
            NotificationReplySendOutcome.Sent
        }
        harness.appState.workerTestHooks?.markNotificationMessageRead = { _, _, _ -> true }
    }

    @After
    fun tearDown() {
        harness.tearDown()
    }

    @Test
    fun onReceive_replyEnqueuesWorkAndFinishesWithinGoAsyncBudget() =
        runBlocking {
            val actionTarget =
                NotificationActionTarget(
                    target =
                        NotificationTarget(
                            WorkerHarnessFixtures.ACCOUNT_REF,
                            WorkerHarnessFixtures.GROUP_ID_HEX,
                            WorkerHarnessFixtures.MESSAGE_ID_HEX,
                            NotificationTargetKind.MESSAGE,
                        ),
                    notificationTag = "${WorkerHarnessFixtures.ACCOUNT_REF}|group",
                    notificationId = 44,
                )
            val intent = Intent()
            NotificationActions.applyToIntent(intent, NotificationActionKind.REPLY, actionTarget)
            RemoteInput.addResultsToIntent(
                arrayOf(
                    RemoteInput
                        .Builder(NotificationActions.KEY_TEXT_REPLY)
                        .setLabel("Reply")
                        .build(),
                ),
                intent,
                Bundle().apply {
                    putCharSequence(NotificationActions.KEY_TEXT_REPLY, "queued reply")
                },
            )

            val (receiver, pendingResult) = receive(intent)
            assertTrue(shadowOf(receiver).wentAsync())
            awaitEnqueuedReplyWork()
            awaitPendingResultFinished(pendingResult)

            val replyJobs =
                harness.workManager
                    .getWorkInfosByTag(NotificationReplyWorker::class.java.name)
                    .get()
            val activeStates =
                setOf(
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.BLOCKED,
                )
            assertTrue(replyJobs.any { it.state in activeStates })
        }

    @Test
    fun onReceive_markReadEnqueuesUniqueWork() =
        runBlocking {
            val action =
                NotificationAction(
                    kind = NotificationActionKind.MARK_READ,
                    target =
                        NotificationTarget(
                            WorkerHarnessFixtures.ACCOUNT_REF,
                            WorkerHarnessFixtures.GROUP_ID_HEX,
                            WorkerHarnessFixtures.MESSAGE_ID_HEX,
                            NotificationTargetKind.MESSAGE,
                        ),
                    notificationTag = "${WorkerHarnessFixtures.ACCOUNT_REF}|group",
                    notificationId = 55,
                )
            val workName = NotificationMarkReadWorker.notificationMarkReadWorkName(action)
            val intent = Intent()
            NotificationActions.applyToIntent(
                intent,
                NotificationActionKind.MARK_READ,
                NotificationActionTarget(action.target, action.notificationTag, action.notificationId),
            )

            val (receiver, pendingResult) = receive(intent)
            assertTrue(shadowOf(receiver).wentAsync())
            awaitEnqueuedMarkReadWork(workName)
            awaitPendingResultFinished(pendingResult)

            val infos = harness.workManager.getWorkInfosForUniqueWork(workName).get()
            assertTrue(infos.isNotEmpty())
            assertTrue(infos.any { it.state != WorkInfo.State.CANCELLED })
        }

    private suspend fun awaitEnqueuedReplyWork() {
        withTimeout(GO_ASYNC_BUDGET_MS + 1_000L) {
            while (
                harness.workManager
                    .getWorkInfosByTag(NotificationReplyWorker::class.java.name)
                    .get()
                    .isEmpty()
            ) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25L))
                delay(25L)
            }
        }
    }

    private suspend fun awaitEnqueuedMarkReadWork(workName: String) {
        withTimeout(GO_ASYNC_BUDGET_MS + 1_000L) {
            while (harness.workManager
                    .getWorkInfosForUniqueWork(workName)
                    .get()
                    .isEmpty()
            ) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25L))
                delay(25L)
            }
        }
    }

    private suspend fun awaitPendingResultFinished(pendingResult: BroadcastReceiver.PendingResult) {
        val finished = shadowOf(pendingResult).future
        withTimeout(GO_ASYNC_BUDGET_MS) {
            while (!finished.isDone) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25L))
                delay(25L)
            }
        }
        assertTrue(finished.isDone)
    }

    private fun receive(intent: Intent): Pair<NotificationActionReceiver, BroadcastReceiver.PendingResult> {
        val receiver = NotificationActionReceiver()
        val pendingResult = pendingResult()
        BroadcastReceiver::class.java
            .getDeclaredMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, pendingResult)
        receiver.onReceive(harness.context, intent)
        return receiver to pendingResult
    }

    private fun pendingResult(): BroadcastReceiver.PendingResult =
        ShadowBroadcastPendingResult::class.java
            .getDeclaredMethod(
                "create",
                Int::class.javaPrimitiveType,
                String::class.java,
                Bundle::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            .invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult

    private companion object {
        private const val GO_ASYNC_BUDGET_MS = 8_000L
    }
}
