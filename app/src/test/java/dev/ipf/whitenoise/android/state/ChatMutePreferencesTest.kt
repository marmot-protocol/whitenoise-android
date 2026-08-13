package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatMutePreferencesTest {
    @Test
    fun persistsOnlyHostOwnedMentionsMode() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val preferences = ChatMutePreferences(context)

        preferences.setNotifyForMode("account", "group", ChatNotifyMode.MENTIONS_ONLY)
        val restored = ChatMutePreferences(context)

        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("account", "group"))
        assertFalse(
            restored.state.value.notificationModes
                .containsValue(ChatNotifyMode.NONE),
        )
    }

    @Test
    fun noneIsNeverPersistedAsAnAndroidMute() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val preferences = ChatMutePreferences(context)

        preferences.setMode("account", "group", ChatNotifyMode.NONE)

        assertEquals(ChatNotifyMode.ALL, ChatMutePreferences(context).mode("account", "group"))
    }

    @Test
    fun legacyMuteRemainsUntilMdkConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val shared = context.getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
        shared
            .edit()
            .putStringSet("mutedConversations", setOf("account|group"))
            .putStringSet("muteExpiries", setOf("5000\u0000MENTIONS_ONLY\u0000account|group"))
            .commit()
        val preferences = ChatMutePreferences(context, shared)

        val legacy = preferences.legacyMuteEntries().single()

        assertEquals("account", legacy.accountRef)
        assertEquals("group", legacy.groupIdHex)
        assertEquals(5_000L, legacy.expiryMillis)
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, legacy.restoreMode)
        assertTrue("account|group" in ChatMutePreferences.readMutedSet(shared))

        preferences.confirmLegacyMuteMigrated(legacy.key)
        assertTrue(ChatMutePreferences.readMutedSet(shared).isEmpty())
        assertTrue(ChatMutePreferences.readMuteExpiries(shared).isEmpty())
    }

    @Test
    fun corruptLegacyExpiryIsIgnoredWithoutInventingState() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val shared = context.getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
        shared
            .edit()
            .putStringSet("mutedConversations", setOf("account|group"))
            .putStringSet("muteExpiries", setOf("broken\u0000NONE\u0000account|group"))
            .commit()

        val legacy = ChatMutePreferences(context, shared).legacyMuteEntries().single()

        assertEquals(null, legacy.expiryMillis)
        assertEquals(ChatNotifyMode.ALL, legacy.restoreMode)
    }

    private fun clear(context: Context) {
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
