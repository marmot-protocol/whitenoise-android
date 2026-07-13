package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatMutePreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsToAllMessages() {
        val prefs = ChatMutePreferences(context)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(ChatNotifyMode.ALL, prefs.mode("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.notificationModes.value)
    }

    @Test
    fun legacyMutedConversationLoadsAsNothing() {
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("mutedConversations", setOf("account-a|group-a"))
            .commit()

        val prefs = ChatMutePreferences(context)

        assertEquals(ChatNotifyMode.NONE, prefs.mode("account-a", "group-a"))
        assertTrue(prefs.isMuted("account-a", "group-a"))
    }

    @Test
    fun mutePersistsPerAccountGroup() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)

        assertTrue(prefs.isMuted("account-a", "group-a"))
        assertFalse(prefs.isMuted("account-a", "group-b"))
        assertFalse(prefs.isMuted("account-b", "group-a"))
        assertEquals(mapOf("account-a|group-a" to ChatNotifyMode.NONE), prefs.notificationModes.value)

        val reloaded = ChatMutePreferences(context)
        assertTrue(reloaded.isMuted("account-a", "group-a"))
    }

    @Test
    fun unmuteRemovesCompositeKey() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)
        prefs.setMuted("account-a", "group-a", muted = false)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.notificationModes.value)
    }

    @Test
    fun mentionOnlyModePersistsPerAccountGroup() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("account-a", "group-a", ChatNotifyMode.MENTIONS_ONLY)

        assertEquals(ChatNotifyMode.MENTIONS_ONLY, prefs.mode("account-a", "group-a"))
        assertEquals(ChatNotifyMode.ALL, prefs.mode("account-a", "group-b"))
        assertFalse(prefs.isMuted("account-a", "group-a"))

        val reloaded = ChatMutePreferences(context)
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, reloaded.mode("account-a", "group-a"))
    }

    @Test
    fun changingModeReplacesThePreviousOverride() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("account-a", "group-a", ChatNotifyMode.MENTIONS_ONLY)
        prefs.setMode("account-a", "group-a", ChatNotifyMode.NONE)
        prefs.setMode("account-a", "group-a", ChatNotifyMode.ALL)

        assertEquals(ChatNotifyMode.ALL, prefs.mode("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.notificationModes.value)
    }

    @Test
    fun compositeKeyTrimsBlankIds() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted(" account-a ", " group-a ", muted = true)

        assertTrue(prefs.isMuted("account-a", "group-a"))
    }

    @Test
    fun blankAccountOrGroupIsIgnored() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("", "group-a", ChatNotifyMode.NONE)
        prefs.setMode("account-a", "   ", ChatNotifyMode.MENTIONS_ONLY)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.notificationModes.value)
    }
}
