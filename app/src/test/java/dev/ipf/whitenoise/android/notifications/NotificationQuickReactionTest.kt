package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
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

    /** Eight configured quick reactions persist in order, while the notification keeps its six-choice bound (#3279). */
    @Test
    fun eightSavedQuickReactionsReloadInOrderAndNotificationsKeepSix() {
        val eight = listOf("🥳", "🔥", "😂", "👍", "❤️", "👎", "🚀", "✅")

        assertEquals(eight, RecentEmojiPreferences.saveQuickReactions(context, eight))
        assertEquals(eight, RecentEmojiPreferences.loadQuickReactions(context))
        assertEquals(eight.take(6), notificationQuickReactionChoices(context))

        RecentEmojiPreferences.resetQuickReactions(context)
        assertEquals(listOf("❤️", "👍", "👎", "😂", "😮", "😢"), RecentEmojiPreferences.loadQuickReactions(context))
    }

    /**
     * Built-in Marmot and White Noise slots survive saving and reloading, and the notification offers their exact
     * shortcodes, so the receiver's allowlist and the sent reaction keep the canonical value (#3292).
     */
    @Test
    fun builtInShortcodeSlotsPersistAndKeepTheirExactNotificationIdentity() {
        val configured = listOf(":marmot:", "👍", ":wn:", "❤️", "😂", "😮")

        assertEquals(configured, RecentEmojiPreferences.saveQuickReactions(context, configured))
        assertEquals(configured, RecentEmojiPreferences.loadQuickReactions(context))
        assertEquals(configured, notificationQuickReactionChoices(context))
    }

    /** A built-in shortcode chip reacts with its exact code, while the same text typed into Reply stays a reply. */
    @Test
    fun builtInShortcodeChipRoutesToItsExactReaction() {
        val remoteInput = RemoteInput.Builder(NotificationActions.KEY_TEXT_REPLY).build()
        val allowed = listOf(":marmot:", ":wn:", "👍")

        /** A Reply intent carrying [value] as if SystemUI returned it from [source]. */
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

        val marmot = delivered(":marmot:", RemoteInput.SOURCE_CHOICE)
        val typed = delivered(":marmot:", RemoteInput.SOURCE_FREE_FORM_INPUT)

        assertEquals(NotificationActionKind.REACT, NotificationActions.parse(marmot)?.kind)
        assertEquals(":marmot:", notificationReactionChoice(marmot, allowed))
        assertEquals(":wn:", notificationReactionChoice(delivered(":wn:", RemoteInput.SOURCE_CHOICE), allowed))
        assertEquals(NotificationActionKind.REPLY, NotificationActions.parse(typed)?.kind)
        assertNull(notificationReactionChoice(typed, allowed))
        assertNull(notificationReactionChoice(delivered(":party_parrot:", RemoteInput.SOURCE_CHOICE), allowed))
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

    /** A chosen chip routes to a reaction, while typed text on the same Reply stays a reply. */
    @Test
    fun replyActionChoiceRoutesToReactionWhileFreeFormStillRoutesToReply() {
        val remoteInput = RemoteInput.Builder(NotificationActions.KEY_TEXT_REPLY).build()

        /** A Reply intent carrying [value] as if SystemUI returned it from [source]. */
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

    /** An emoji action from a card posted before the upgrade still reacts, within the current allowlist. */
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

    /** A shortcode with no words keeps its exact value as its readable label, so TalkBack never reads a blank. */
    @Test
    fun wordlessShortcodeKeepsItsExactValue() {
        val resources = context.resources

        assertEquals(":__:", EmojiShortcodes.plainLabel(":__:", resources))
        assertEquals(":-_-:", EmojiShortcodes.plainLabel(":-_-:", resources))
        assertEquals("wave", EmojiShortcodes.plainLabel(":_wave_:", resources))
        assertEquals("party parrot", EmojiShortcodes.plainLabel(":party_parrot:", resources))
    }

    /** An image choice on a card posted by the earlier expanded-choice build still sends its exact reaction. */
    @Test
    fun imageChoiceFromAnEarlierExpandedCardStillReacts() {
        val earlier =
            Intent(context, NotificationActionReceiver::class.java).also { intent ->
                NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, actionTarget())
                // The data URI the earlier build gave each image choice, so no two targets shared an identity.
                val uri = "whitenoise-notify://fixed-reaction/account-a/group-a/message-a/MESSAGE/17/marmot"
                intent.data = Uri.parse(uri)
                intent.putExtra("dev.ipf.whitenoise.android.extra.REACTION", ":marmot:")
            }

        assertEquals(NotificationActionKind.REACT, NotificationActions.parse(earlier)?.kind)
        assertEquals(actionTarget().target, NotificationActions.parse(earlier)?.target)
        assertEquals(":marmot:", notificationReactionChoice(earlier, listOf(":marmot:", ":wn:")))
        assertNull(notificationReactionChoice(earlier, listOf(":wn:")))
    }

    /** React and Reply never share a request code or action URI, so neither PendingIntent replaces the other. */
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
    fun pendingReactionRejectsALaterChoiceWithoutDismissing() =
        runTest {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val action = reactAction()
            val workManager = WorkManager.getInstance(context)
            val firstRequestId = UUID.randomUUID()
            val secondRequestId = UUID.randomUUID()
            val firstCipher = testCipher()

            assertTrue(
                NotificationReactionWorker.enqueue(
                    context = context,
                    action = action,
                    reaction = "🔥",
                    actionStartedAtMs = 123L,
                    requestId = firstRequestId,
                    cipherFactory = { firstCipher },
                ),
            )
            var dismissed = false

            val outcome =
                submitNotificationReaction(
                    action = action,
                    reaction = "🥳",
                    actionStartedAtMs = 456L,
                    notificationActionsAllowed = { true },
                    enqueueReaction = { queuedAction, queuedReaction, startedAtMs ->
                        NotificationReactionWorker.enqueue(
                            context = context,
                            action = queuedAction,
                            reaction = queuedReaction,
                            actionStartedAtMs = startedAtMs,
                            requestId = secondRequestId,
                            cipherFactory = ::testCipher,
                        )
                    },
                    dismissNotification = { _, _, _ -> dismissed = true },
                )

            val retainedWork =
                workManager
                    .getWorkInfosForUniqueWork(NotificationReactionWorker.notificationReactionWorkName(action))
                    .get()
            assertEquals(NotificationReactionSubmissionOutcome.PersistenceFailed, outcome)
            assertFalse(dismissed)
            assertEquals(listOf(firstRequestId), retainedWork.map { it.id })
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
