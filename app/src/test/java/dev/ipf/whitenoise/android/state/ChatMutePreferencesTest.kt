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
    fun defaultsToUnmuted() {
        val prefs = ChatMutePreferences(context)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptySet<String>(), prefs.mutedConversations.value)
    }

    @Test
    fun mutePersistsPerAccountGroup() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)

        assertTrue(prefs.isMuted("account-a", "group-a"))
        assertFalse(prefs.isMuted("account-a", "group-b"))
        assertFalse(prefs.isMuted("account-b", "group-a"))
        assertEquals(setOf("account-a|group-a"), prefs.mutedConversations.value)

        val reloaded = ChatMutePreferences(context)
        assertTrue(reloaded.isMuted("account-a", "group-a"))
    }

    @Test
    fun unmuteRemovesCompositeKey() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)
        prefs.setMuted("account-a", "group-a", muted = false)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptySet<String>(), prefs.mutedConversations.value)
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

        prefs.setMuted("", "group-a", muted = true)
        prefs.setMuted("account-a", "   ", muted = true)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptySet<String>(), prefs.mutedConversations.value)
    }
}
