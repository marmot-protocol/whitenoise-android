package dev.ipf.whitenoise.android.notifications

import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class NotificationDraftTapMutabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun onlyOrdinaryMessageCardsAcceptMutableDraftFillIn() {
        val cases =
            listOf(
                update(NotificationTriggerFfi.NEW_MESSAGE, null) to true,
                update(NotificationTriggerFfi.NEW_MESSAGE, "👍") to false,
                update(NotificationTriggerFfi.GROUP_INVITE, null) to false,
                update(NotificationTriggerFfi.REMOVED_FROM_GROUP, null) to false,
                update(NotificationTriggerFfi.MADE_ADMIN, null) to false,
            )
        val interactions = NotificationInteractionFactory(context, NotificationTapTokens.create(context))
        cases.forEachIndexed { index, (update, mutable) ->
            val pending = interactions.conversationPendingIntent(update, "card-$index")
            val flags = shadowOf(pending).flags
            assertEquals(mutable, flags and PendingIntent.FLAG_MUTABLE != 0)
            assertEquals(!mutable, flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
    }

    @Test
    fun messageTapUsesTheSharedTokenOwnerAndRevocationInvalidatesIt() {
        val tokens = NotificationTapTokens.create(context)
        val interactions = NotificationInteractionFactory(context, tokens)
        val update = update(NotificationTriggerFfi.NEW_MESSAGE, null)
        val tag = "shared-owner-card"
        val intent = shadowOf(interactions.conversationPendingIntent(update, tag)).savedIntent
        val target = NotificationNavigation.fromUpdate(update)
        assertEquals(target, NotificationNavigation.parse(intent, isTrustedTargetSignature = tokens::isValidTarget))

        tokens.remove(tag)
        assertNull(NotificationNavigation.parse(intent, isTrustedTargetSignature = tokens::isValidTarget))
    }

    private fun update(
        trigger: NotificationTriggerFfi,
        reaction: String?,
    ): NotificationUpdateFfi {
        val user = NotificationUserFfi("1".repeat(64), "Alice", null)
        return NotificationUpdateFfi(
            notificationKey = "test-event",
            conversationKey = "test-conversation",
            trigger = trigger,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "test-account",
            accountIdHex = "2".repeat(64),
            groupIdHex = "test-group",
            groupName = "Test",
            isDm = false,
            messageIdHex = "3".repeat(64),
            sender = user,
            receiver = user.copy(accountIdHex = "2".repeat(64), displayName = "Me"),
            previewText = "test message",
            timestampMs = 0L,
            isFromSelf = false,
            isMention = false,
            reactionEmoji = reaction,
            reactedToPreview = null,
        )
    }
}
