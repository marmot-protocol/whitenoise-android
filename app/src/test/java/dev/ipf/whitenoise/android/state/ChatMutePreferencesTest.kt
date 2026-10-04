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

    /** A command-only mute does not replace the preference restored after durable unmute. */
    @Test
    fun muteCommandDoesNotReplaceSavedMentionsPreference() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val preferences = ChatMutePreferences(context)
        preferences.setNotifyForMode("account", "group", ChatNotifyMode.MENTIONS_ONLY)

        preferences.setMode("account", "group", ChatNotifyMode.NONE)

        assertEquals(ChatNotifyMode.MENTIONS_ONLY, ChatMutePreferences(context).mode("account", "group"))
        preferences.setNotifyForMode("account", "group", ChatNotifyMode.ALL)
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

    /** Upgrade records all old implicit defaults, including inactive-account and archived groups. */
    @Test
    fun upgradePreservesOldModesAndDefaultsOnlyNewGroupsToMentions() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val preferences = ChatMutePreferences(context)
        preferences.setNotifyForMode("alice", "mentions", ChatNotifyMode.MENTIONS_ONLY)
        preferences.preserveExistingModes(mapOf("alice" to listOf("old", "mentions"), "bob" to listOf("archived")))
        val restored = ChatMutePreferences(context)
        assertFalse(restored.needsDefaultsMigration)
        assertEquals(ChatNotifyMode.ALL, restored.mode("alice", "old", false))
        assertEquals(ChatNotifyMode.ALL, restored.mode("bob", "archived", false))
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("alice", "mentions", false))
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("alice", "new", false))
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("new-account", "old", false))
        assertEquals(ChatNotifyMode.ALL, restored.mode("alice", "new-dm", true))
        restored.preserveExistingModes(mapOf("alice" to listOf("new")))
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("alice", "new", false))
    }

    /** Explicit opt-in survives recreation/rejoin; another account retains the new group default. */
    @Test
    fun explicitAllSurvivesRestartAndSubsequentMentionsChoice() {
        val context = RuntimeEnvironment.getApplication()
        clear(context)
        val preferences = ChatMutePreferences(context)
        preferences.preserveExistingModes(emptyMap())
        preferences.setNotifyForMode("alice", "group", ChatNotifyMode.ALL)
        val restored = ChatMutePreferences(context)
        assertEquals(ChatNotifyMode.ALL, restored.mode("alice", "group", false))
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("bob", "group", false))
        restored.setNotifyForMode("alice", "group", ChatNotifyMode.MENTIONS_ONLY)
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, ChatMutePreferences(context).mode("alice", "group", false))
        restored.setNotifyForMode("alice", "group", ChatNotifyMode.ALL)
        restored.removeAccount("bob")
        assertEquals(ChatNotifyMode.ALL, ChatMutePreferences(context).mode("alice", "group", false))
        restored.removeAccount("alice")
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, ChatMutePreferences(context).mode("alice", "group", false))
    }

    private fun clear(context: Context) {
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
