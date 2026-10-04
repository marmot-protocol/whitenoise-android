package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the production adapter, so background notification defaults cannot diverge from the UI store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewGroupNotificationDefaultsTest {
    /** Typed mentions and DMs still pass, while new-group ordinary messages need explicit opt-in. */
    @Test
    fun typedConversationDefaultsReachDeliveryPolicy() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "alice",
            )
        val ordinary = update()
        assertFalse(state.shouldPostNotification(ordinary, false))
        assertTrue(state.shouldPostNotification(ordinary.copy(isMention = true), false))
        assertTrue(state.shouldPostNotification(ordinary.copy(isDm = true), false))
        state.chatMutePreferences.setNotifyForMode("alice", "group", ChatNotifyMode.ALL)
        assertTrue(state.shouldPostNotification(ordinary, false))
        assertFalse(state.shouldPostNotification(ordinary.copy(accountRef = "bob"), false))
        assertFalse(state.shouldPostNotification(ordinary, true))
        assertTrue(state.shouldPostNotification(ordinary.copy(isMention = true), true))
        assertFalse(state.shouldPostNotification(ordinary.copy(isMention = true, isFromSelf = true), true))
        state.setMemberMutedInGroup("alice", "group", "sender", true)
        assertFalse(state.shouldPostNotification(ordinary.copy(isMention = true), false))
    }

    /** Synthetic native update uses the typed mention/classification fields, never preview parsing. */
    private fun update() =
        NotificationUpdateFfi(
            isMention = false,
            notificationKey = "message:alice:message",
            conversationKey = "conversation:alice:group",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "alice",
            accountIdHex = "alice",
            groupIdHex = "group",
            groupName = "General",
            isDm = false,
            messageIdHex = "message",
            sender = NotificationUserFfi("sender", "Sender", null),
            receiver = NotificationUserFfi("alice", "Alice", null),
            previewText = "Hello",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1234,
            isFromSelf = false,
        )
}
