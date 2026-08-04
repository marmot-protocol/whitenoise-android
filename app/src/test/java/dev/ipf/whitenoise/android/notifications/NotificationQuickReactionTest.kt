package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
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
    fun notificationUsesTheFirstTwoCustomizedQuickReactions() {
        RecentEmojiPreferences.saveQuickReactions(context, listOf("🥳", "🔥", "😂", "👍"))

        assertEquals(listOf("🥳", "🔥"), notificationQuickReactionChoices(context))
    }

    @Test
    fun reactionIntentRoundTripsTheExactMessageAndReaction() {
        val actionTarget = actionTarget()
        val intent = Intent()

        NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, actionTarget, " 🔥 ")

        assertEquals(
            NotificationAction(
                kind = NotificationActionKind.REACT,
                target = actionTarget.target,
                notificationTag = actionTarget.notificationTag,
                notificationId = actionTarget.notificationId,
                reaction = "🔥",
            ),
            NotificationActions.parse(intent),
        )
    }

    @Test
    fun reactionIntentRejectsMissingOrOversizedReactions() {
        val missing =
            Intent().also {
                NotificationActions.applyToIntent(it, NotificationActionKind.REACT, actionTarget())
            }
        val oversized = "😀".repeat(33)
        val tooLong =
            Intent().also {
                NotificationActions.applyToIntent(it, NotificationActionKind.REACT, actionTarget(), oversized)
            }

        assertNull(NotificationActions.parse(missing))
        assertNull(NotificationActions.parse(tooLong))
    }

    @Test
    fun eachQuickReactionHasADistinctPendingIntentIdentity() {
        val tag = "account-a|group-a"

        assertNotEquals(
            NotificationActions.requestCode(NotificationActionKind.REACT, tag, "❤️"),
            NotificationActions.requestCode(NotificationActionKind.REACT, tag, "👍"),
        )
        assertNotEquals(
            NotificationActions.actionUriString(NotificationActionKind.REACT, tag, "❤️"),
            NotificationActions.actionUriString(NotificationActionKind.REACT, tag, "👍"),
        )
        assertFalse(NotificationActions.actionUriString(NotificationActionKind.REACT, tag, "❤️").contains("❤️"))
    }

    @Test
    fun notificationIdentifiersUseStableLowercaseAsciiHex() {
        assertEquals("000f10ff", byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte()).toLowercaseHexString())
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
        val workName = NotificationReactionWorker.notificationReactionWorkName(routingAction, reaction)
        assertFalse(workName.contains(reaction))
        assertEquals(workName, NotificationReactionWorker.notificationReactionWorkName(routingAction, reaction))
        assertNotEquals(workName, NotificationReactionWorker.notificationReactionWorkName(routingAction, "👍"))
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

    private fun testCipher() = NotificationReplyCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
}
