package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
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
        assertEquals(mapOf("account-a|group-a" to ChatNotifyMode.NONE), prefs.state.value.notificationModes)

        val reloaded = ChatMutePreferences(context)
        assertTrue(reloaded.isMuted("account-a", "group-a"))
    }

    @Test
    fun unmuteRemovesCompositeKey() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)
        prefs.setMuted("account-a", "group-a", muted = false)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
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
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
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
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
    }

    @Test
    fun notificationModesAndMutedKeysPublishAsOneState() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("account-a", "group-a", ChatNotifyMode.MENTIONS_ONLY)
        prefs.setMode("account-a", "group-b", ChatNotifyMode.NONE)

        val state = prefs.state.value
        assertEquals(
            mapOf(
                "account-a|group-a" to ChatNotifyMode.MENTIONS_ONLY,
                "account-a|group-b" to ChatNotifyMode.NONE,
            ),
            state.notificationModes,
        )
        assertEquals(setOf("account-a|group-b"), state.mutedConversations)
    }

    @Test
    fun mutationAndPersistenceStayInsideOneSerializedStateUpdate() {
        val source = chatMutePreferencesSource().readText()
        val setMode = source.functionBody("setMode")

        assertTrue("mode updates must be serialized", "synchronized(mutationLock)" in setMode)
        assertTrue("mode and muted projections must publish together", "_state.value = ChatNotificationState(immutableModes)" in setMode)
        assertFalse("there must not be a second mutable muted flow", "_mutedConversations" in source)
    }

    @Test
    fun concurrentModeUpdatesRetainEveryStateAndPersistedKey() {
        val prefs = ChatMutePreferences(context)
        val writerCount = 32
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val expectedModes =
            (0 until writerCount).associate { index ->
                "account-a|group-$index" to
                    if (index % 2 == 0) ChatNotifyMode.NONE else ChatNotifyMode.MENTIONS_ONLY
            }
        val futures =
            (0 until writerCount).map { index ->
                executor.submit {
                    start.await()
                    prefs.setMode(
                        accountRef = "account-a",
                        groupIdHex = "group-$index",
                        mode = expectedModes.getValue("account-a|group-$index"),
                    )
                }
            }

        try {
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val expectedMuted = expectedModes.filterValues { it == ChatNotifyMode.NONE }.keys
        assertEquals(expectedModes, prefs.state.value.notificationModes)
        assertEquals(expectedMuted, prefs.state.value.mutedConversations)

        val reloaded = ChatMutePreferences(context)
        assertEquals(expectedModes, reloaded.state.value.notificationModes)
        assertEquals(expectedMuted, reloaded.state.value.mutedConversations)
    }

    private fun chatMutePreferencesSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ChatMutePreferences.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ChatMutePreferences.kt"),
        ).firstOrNull(File::exists) ?: error("Missing ChatMutePreferences.kt")
}
