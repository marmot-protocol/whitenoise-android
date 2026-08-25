package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
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
    fun notificationUsesAllSixCustomizedQuickReactions() {
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
        assertNull(notificationReactionChoice(intent, allowedChoices = listOf("🔥")))
    }

    @Test
    fun reactionChoiceAcceptsOnlyAnAllowlistedRemoteInputResult() {
        val remoteInput = RemoteInput.Builder(NotificationActions.KEY_REACTION_CHOICE).build()

        fun delivered(value: String) =
            Intent().also { intent ->
                RemoteInput.addResultsToIntent(
                    arrayOf(remoteInput),
                    intent,
                    Bundle().apply { putCharSequence(NotificationActions.KEY_REACTION_CHOICE, value) },
                )
            }

        assertEquals("🔥", notificationReactionChoice(delivered(" 🔥 "), listOf("🥳", "🔥")))
        assertNull(notificationReactionChoice(delivered("👾"), listOf("🥳", "🔥")))
        assertNull(notificationReactionChoice(delivered("😀".repeat(33)), listOf("🥳", "🔥")))
    }

    @Test
    fun replyActionChoiceRoutesToReactionWhileFreeFormStillRoutesToReply() {
        val remoteInput = RemoteInput.Builder(NotificationActions.KEY_TEXT_REPLY).build()

        fun delivered(
            value: String,
            source: Int,
        ) = Intent().also { intent ->
            NotificationActions.applyToIntent(
                intent,
                NotificationActionKind.REPLY,
                actionTarget(),
                acceptsInlineReactionChoices = true,
            )
            RemoteInput.addResultsToIntent(
                arrayOf(remoteInput),
                intent,
                Bundle().apply { putCharSequence(NotificationActions.KEY_TEXT_REPLY, value) },
            )
            RemoteInput.setResultsSource(intent, source)
        }

        val choice = delivered("🔥", RemoteInput.SOURCE_CHOICE)
        val freeForm = delivered("🔥", RemoteInput.SOURCE_FREE_FORM_INPUT)

        assertEquals(NotificationActionKind.REACT, NotificationActions.parse(choice)?.kind)
        assertEquals("🔥", notificationReactionChoice(choice, listOf("🥳", "🔥")))
        assertEquals(NotificationActionKind.REPLY, NotificationActions.parse(freeForm)?.kind)
        assertNull(notificationReactionChoice(freeForm, listOf("🥳", "🔥")))
    }

    @Test
    fun directReactionFromANotificationPostedBeforeUpgradeStillUsesTheCurrentAllowlist() {
        val legacy =
            Intent().putExtra(
                "dev.ipf.whitenoise.android.extra.REACTION",
                "🔥",
            )

        assertEquals("🔥", notificationReactionChoice(legacy, listOf("🥳", "🔥")))
        assertNull(notificationReactionChoice(legacy, listOf("🥳")))
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
    fun notificationIdentifiersUseStableLowercaseAsciiHex() {
        assertEquals("000f10ff", byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte()).toLowercaseHexString())
    }

    @Test
    fun lockedChipTapCannotEnqueueOrDismissAReaction() =
        runTest {
            var enqueueCount = 0
            var dismissCount = 0

            val outcome =
                submitNotificationReaction(
                    action = reactAction(),
                    reaction = "🔥",
                    notificationActionsAllowed = { false },
                    enqueueReaction = { _, _, _ ->
                        enqueueCount += 1
                        true
                    },
                    dismissNotification = { _, _, _ -> dismissCount += 1 },
                )

            assertEquals(NotificationReactionSubmissionOutcome.BlockedByAppLock, outcome)
            assertEquals(0, enqueueCount)
            assertEquals(0, dismissCount)
        }

    @Test
    fun chipTapPersistsOneReactionBeforeDismissing() =
        runTest {
            val events = mutableListOf<String>()

            val outcome =
                submitNotificationReaction(
                    action = reactAction(),
                    reaction = " 🔥 ",
                    actionStartedAtMs = 123L,
                    notificationActionsAllowed = { true },
                    enqueueReaction = { _, reaction, startedAtMs ->
                        assertEquals("🔥", reaction)
                        assertEquals(123L, startedAtMs)
                        events += "persisted"
                        true
                    },
                    dismissNotification = { _, _, _ -> events += "dismissed" },
                )

            assertEquals(NotificationReactionSubmissionOutcome.Submitted, outcome)
            assertEquals(listOf("persisted", "dismissed"), events)
        }

    @Test
    fun failedPersistenceLeavesTheNotificationAvailable() =
        runTest {
            var dismissed = false

            val outcome =
                submitNotificationReaction(
                    action = reactAction(),
                    reaction = "🔥",
                    notificationActionsAllowed = { true },
                    enqueueReaction = { _, _, _ -> false },
                    dismissNotification = { _, _, _ -> dismissed = true },
                )

            assertEquals(NotificationReactionSubmissionOutcome.PersistenceFailed, outcome)
            assertFalse(dismissed)
        }

    @Test
    fun cancellationWhilePersistingPropagatesAndLeavesTheNotificationAvailable() =
        runTest {
            var dismissed = false

            var propagated = false
            try {
                submitNotificationReaction(
                    action = reactAction(),
                    reaction = "🔥",
                    notificationActionsAllowed = { true },
                    enqueueReaction = { _, _, _ -> throw CancellationException("receiver timed out") },
                    dismissNotification = { _, _, _ -> dismissed = true },
                )
            } catch (_: CancellationException) {
                propagated = true
            }

            assertTrue(propagated)
            assertFalse(dismissed)
        }

    @Test
    fun workerRechecksAppLockBeforeSending() =
        runTest {
            var sendCount = 0

            val attempt =
                attemptNotificationReactionSend(
                    notificationActionsAllowed = { false },
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

        val actionStartedAtMs = 123_456L
        val request =
            NotificationReactionWorker.notificationReactionRequest(
                routingAction,
                requestId,
                encrypted,
                actionStartedAtMs,
            )
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
        assertEquals(actionStartedAtMs, NotificationReactionWorker.reactionActionStartedAtMs(input))
        val workName = NotificationReactionWorker.notificationReactionWorkName(routingAction)
        assertFalse(workName.contains(reaction))
        assertEquals(workName, NotificationReactionWorker.notificationReactionWorkName(routingAction))
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
            notificationTag = actionTarget().notificationTag,
            notificationId = actionTarget().notificationId,
        )

    private fun testCipher() = NotificationReplyCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
}
