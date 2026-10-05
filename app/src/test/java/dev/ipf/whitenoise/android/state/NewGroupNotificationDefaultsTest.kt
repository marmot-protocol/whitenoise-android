package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises production mute commands and the delivery adapter over a native boundary with no runtime or network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewGroupNotificationDefaultsTest {
    /** The production adapter reads independent device choices for groups, DMs and reactions. */
    @Test
    fun independentDeviceChoicesReachTheProductionDeliveryAdapter() {
        val state = freshState()
        try {
            val preferences = state.conversationAlertPreferences
            assertTrue(preferences.setEnabled("alice", "group", NotificationChannelSpec.GROUP_MESSAGES, false))
            assertFalse(state.shouldPostNotification(update(), false))
            assertTrue(state.shouldPostNotification(update().copy(isMention = true), false))
            assertTrue(preferences.setEnabled("alice", "group", NotificationChannelSpec.MENTIONS, false))
            assertFalse(state.shouldPostNotification(update().copy(isMention = true), false))
            assertFalse(state.shouldPostNotification(update().copy(isMention = true), true))
            assertTrue(state.shouldPostNotification(update().copy(isDm = true), false))
            assertTrue(preferences.setEnabled("alice", "group", NotificationChannelSpec.REACTIONS, false))
            assertFalse(state.shouldPostNotification(update().copy(reactionEmoji = "👍"), false))
            assertTrue(preferences.clearAccount("alice"))
            assertTrue(state.shouldPostNotification(update(), false))
        } finally {
            state.mutationsScope.cancel()
        }
    }

    /** New groups and DMs deliver ordinary messages; explicit mentions-only remains scoped to one account/group. */
    @Test
    fun allMessagesDefaultAndExplicitMentionsReachDeliveryPolicy() {
        val state = freshState()
        try {
            val ordinary = update()
            assertTrue(state.shouldPostNotification(ordinary, false))
            assertTrue(state.shouldPostNotification(ordinary.copy(isDm = true), false))
            state.setConversationNotifyForMode("group", ChatNotifyMode.MENTIONS_ONLY)
            assertFalse(state.shouldPostNotification(ordinary, false))
            assertTrue(state.shouldPostNotification(ordinary.copy(isMention = true), false))
            assertTrue(state.shouldPostNotification(ordinary.copy(accountRef = "bob"), false))
            assertTrue(state.shouldPostNotification(ordinary.copy(groupIdHex = "other"), false))
            state.setMemberMutedInGroup("alice", "group", "sender", true)
            assertFalse(state.shouldPostNotification(ordinary.copy(isMention = true), false))
        } finally {
            state.mutationsScope.cancel()
        }
    }

    /** Normal mute keeps supported mentions; unmute restores either saved mode without changing preferences. */
    @Test
    fun muteAndUnmuteRestoreEachSelectedMode() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val state = freshState()
            val gateway = MuteGateway()
            WhiteNoiseAppState::class.java.getDeclaredField("chatMuteRepository").apply {
                isAccessible = true
                set(state, ChatMuteRepository(gateway, dispatcher))
            }
            try {
                for (mode in listOf(ChatNotifyMode.ALL, ChatNotifyMode.MENTIONS_ONLY)) {
                    state.setConversationNotifyForMode("group", mode)
                    state.setConversationMuted("group", true)
                    assertTrue(gateway.current.muted)
                    assertEquals(mode, state.conversationRestoreNotifyMode("group"))
                    assertFalse(state.shouldPostNotification(update(), gateway.current.muted))
                    assertTrue(state.shouldPostNotification(update().copy(isMention = true), gateway.current.muted))
                    assertFalse(
                        state.shouldPostNotification(
                            update().copy(isMention = true, isFromSelf = true),
                            gateway.current.muted,
                        ),
                    )
                    state.setConversationMuted("group", false)
                    assertFalse(gateway.current.muted)
                    assertEquals(mode, state.conversationNotifyMode("group"))
                    assertEquals(
                        mode == ChatNotifyMode.ALL,
                        state.shouldPostNotification(update(), gateway.current.muted),
                    )
                    assertTrue(state.shouldPostNotification(update().copy(isMention = true), gateway.current.muted))
                }
                assertEquals(listOf(true, false, true, false), gateway.commands)
            } finally {
                state.mutationsScope.cancel()
                Dispatchers.resetMain()
            }
        }

    /** Starts with no preference override, matching first discovery and keeping tests isolated. */
    private fun freshState(): WhiteNoiseAppState {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "alice",
        )
    }

    /** Returns authoritative native settings without manufacturing an Android-owned mute store. */
    private class MuteGateway : ChatMuteGateway {
        var current = ChatNotificationSettingsFfi("alice", "alice", "group", false, null, 1)
        val commands = mutableListOf<Boolean>()

        /** Reads only the synthetic account/group addressed by this test. */
        override fun read(
            accountRef: String,
            groupIdHex: String,
        ): ChatNotificationSettingsFfi {
            check(accountRef == "alice" && groupIdHex == "group")
            return current
        }

        /** Models a confirmed native mute command while leaving host delivery preferences untouched. */
        override fun mute(
            accountRef: String,
            groupIdHex: String,
            mutedUntilMs: Long?,
        ): ChatNotificationSettingsFfi {
            current = read(accountRef, groupIdHex).copy(muted = true, mutedUntilMs = mutedUntilMs)
            commands += true
            return current
        }

        /** Models a confirmed native unmute command, including clearing its deadline. */
        override fun unmute(
            accountRef: String,
            groupIdHex: String,
        ): ChatNotificationSettingsFfi {
            current = read(accountRef, groupIdHex).copy(muted = false, mutedUntilMs = null)
            commands += false
            return current
        }
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
