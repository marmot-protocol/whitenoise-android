package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationQuickReactionTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @After
    fun resetQuickReactions() {
        RecentEmojiPreferences.resetQuickReactions(context)
    }

    @Test
    fun theChipRowOffersAllSixCustomizedQuickReactions() {
        RecentEmojiPreferences.saveQuickReactions(context, listOf("🥳", "🔥", "😂", "👍"))

        assertEquals(listOf("🥳", "🔥", "😂", "👍", "❤️", "👎"), notificationQuickReactionChoices(context))
    }

    @Test
    fun quickReactionChoicesDropBlankOversizedAndDuplicateFavourites() {
        val stored = listOf("🥳", "   ", "😀".repeat(33), "🔥", "🥳")

        assertEquals(listOf("🥳", "🔥"), notificationQuickReactionChoices(stored))
    }

    @Test
    fun reactIntentRoundTripsTheTargetAndNeverCarriesAnEmoji() {
        val actionTarget = actionTarget()
        val intent = Intent()

        NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, actionTarget)

        assertEquals(
            NotificationAction(
                kind = NotificationActionKind.REACT,
                target = actionTarget.target,
                notificationTag = actionTarget.notificationTag,
                notificationId = actionTarget.notificationId,
            ),
            NotificationActions.parse(intent),
        )
        assertEquals(NotificationActions.ACTION_REACT, intent.action)
        assertNull(notificationReactionChoice(intent, allowedChoices = listOf("🔥")))
    }

    @Test
    fun reactKeepsAPendingIntentIdentityOfItsOwn() {
        val tag = "account-a|group-a"

        assertNotEquals(
            NotificationActions.requestCode(NotificationActionKind.REACT, tag),
            NotificationActions.requestCode(NotificationActionKind.REPLY, tag),
        )
        assertNotEquals(
            NotificationActions.actionUriString(NotificationActionKind.REACT, tag),
            NotificationActions.actionUriString(NotificationActionKind.REPLY, tag),
        )
    }

    @Test
    fun chipTapAtomicallyQueuesReactionAndMarkReadBeforeDismissing() =
        runTest {
            val events = mutableListOf<String>()
            val action = reactAction()

            val queued =
                submitNotificationReaction(
                    action = action,
                    reaction = " 🔥 ",
                    enqueueReactionAndMarkRead = { queuedAction, reaction ->
                        assertEquals(action, queuedAction)
                        assertEquals("🔥", reaction)
                        events += "reaction-and-mark-read-enqueued"
                        true
                    },
                    dismissNotification = { dismissedAction, reaction ->
                        assertEquals(action, dismissedAction)
                        assertEquals("🔥", reaction)
                        events += "notification-dismissed"
                    },
                )

            assertTrue(queued)
            assertEquals(
                listOf("reaction-and-mark-read-enqueued", "notification-dismissed"),
                events,
            )
        }

    @Test
    fun chipTapLeavesTheCardPostedWhenTheActionBatchCannotBeQueued() =
        runTest {
            val events = mutableListOf<String>()

            val queued =
                submitNotificationReaction(
                    action = reactAction(),
                    reaction = "🔥",
                    enqueueReactionAndMarkRead = { _, _ -> false },
                    dismissNotification = { _, _ -> events += "notification-dismissed" },
                )

            assertFalse(queued)
            assertTrue(events.isEmpty())
        }

    @Test
    fun cancellationLeavesTheCardPostedAndPropagates() =
        runTest {
            var dismissed = false
            var cancellationPropagated = false

            try {
                submitNotificationReaction(
                    action = reactAction(),
                    reaction = "🔥",
                    enqueueReactionAndMarkRead = { _, _ ->
                        throw CancellationException("receiver budget expired")
                    },
                    dismissNotification = { _, _ -> dismissed = true },
                )
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }

            assertTrue(cancellationPropagated)
            assertFalse(dismissed)
        }

    @Test
    fun blankOrOversizedChipValuesAreRejectedBeforeAnythingIsQueued() =
        runTest {
            val events = mutableListOf<String>()

            listOf("", "   ", "😀".repeat(33)).forEach { chip ->
                val queued =
                    submitNotificationReaction(
                        action = reactAction(),
                        reaction = chip,
                        enqueueReactionAndMarkRead = { _, _ ->
                            events += "reaction-and-mark-read-enqueued"
                            true
                        },
                        dismissNotification = { _, _ -> events += "notification-dismissed" },
                    )
                assertFalse(queued)
            }

            assertTrue(events.isEmpty())
        }

    @Test
    fun notificationIdentifiersUseStableLowercaseAsciiHex() {
        assertEquals("000f10ff", byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte()).toLowercaseHexString())
    }

    @Test
    fun appLockTransitionAfterPreflightBlocksReactionSend() =
        runTest {
            var actionsAllowed = true
            var sendCount = 0

            assertTrue(actionsAllowed)
            actionsAllowed = false
            val attempt =
                attemptNotificationReactionSend(
                    notificationActionsAllowed = { actionsAllowed },
                    sendReaction = {
                        sendCount += 1
                        NotificationReactionSendOutcome.Sent
                    },
                )

            assertEquals(NotificationReactionSendAttempt.Locked, attempt)
            assertEquals(0, sendCount)
        }

    @Test
    fun reactionWorkerEncryptsPayloadAndUsesBoundedNetworkBackoff() {
        val reaction = "🔥 plaintext sentinel"
        val routingAction =
            NotificationAction(
                kind = NotificationActionKind.REACT,
                target = actionTarget().target,
                notificationTag = "account-a|group-a",
                notificationId = 17,
            )
        val requestId = UUID.randomUUID()
        val cipher = testCipher()
        val encrypted = cipher.encrypt(reaction, requestId, routingAction)

        val request = NotificationReactionWorker.notificationReactionRequest(routingAction, requestId, encrypted)
        val actionRequests =
            NotificationReactionWorker.notificationReactionActionRequests(routingAction, requestId, encrypted)
        val input = request.workSpec.input
        val restored = NotificationReactionWorker.reactionFromInput(input)!!

        assertEquals(requestId, request.id)
        assertEquals(routingAction, NotificationActionWorkData.decode(input))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertFalse(input.keyValueMap.values.any { it == reaction })
        val serializedInput = input.toByteArray().asList()
        val plaintextBytes = reaction.toByteArray(Charsets.UTF_8).asList()
        assertFalse(serializedInput.windowed(plaintextBytes.size).any { it == plaintextBytes })
        assertEquals(reaction, cipher.decrypt(restored, requestId, routingAction))
        assertEquals(2, actionRequests.size)
        assertEquals(
            setOf(NotificationActionKind.REACT, NotificationActionKind.MARK_READ),
            actionRequests.mapNotNull { NotificationActionWorkData.decode(it.workSpec.input)?.kind }.toSet(),
        )
        val actionWorkName = NotificationReactionWorker.notificationReactionActionWorkName(routingAction)
        assertFalse(actionWorkName.contains(routingAction.target.messageIdHex.orEmpty()))
        assertEquals(actionWorkName, NotificationReactionWorker.notificationReactionActionWorkName(routingAction))
        assertTrue(NotificationReactionWorker.shouldRetryAfterFailure(0))
        assertTrue(NotificationReactionWorker.shouldRetryAfterFailure(1))
        assertFalse(NotificationReactionWorker.shouldRetryAfterFailure(2))
    }

    private fun actionTarget() =
        NotificationActionTarget(
            target = NotificationTarget("account-a", "group-a", "message-a", NotificationTargetKind.MESSAGE),
            notificationTag = "account-a|group-a",
            notificationId = 17,
        )

    private fun reactAction() =
        NotificationAction(
            kind = NotificationActionKind.REACT,
            target = actionTarget().target,
            notificationTag = "account-a|group-a",
            notificationId = 17,
        )

    private fun testCipher() = NotificationReplyCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
}
