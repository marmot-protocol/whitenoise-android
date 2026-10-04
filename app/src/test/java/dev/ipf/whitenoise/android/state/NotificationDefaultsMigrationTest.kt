package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Exercises the native snapshot barrier independently of migrated delivery/startup fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationDefaultsMigrationTest {
    /** Inactive and archived groups retain their old default, and a completed barrier performs no native reads. */
    @Test
    fun snapshotsEveryAccountIncludingArchivedBeforeCommittingOnce() =
        runBlocking {
            val preferences = freshPreferences()
            preferences.setNotifyForMode("alice", "old", ChatNotifyMode.MENTIONS_ONLY)
            val reads = mutableListOf<Pair<String, Boolean>>()
            val engine =
                engine { label, includeArchived ->
                    reads += label to includeArchived
                    listOf(notificationChatListRow().copy(groupIdHex = "old", archived = label == "bob"))
                }
            engine.preserveExistingNotificationModes(preferences)
            assertEquals(listOf("alice" to true, "bob" to true), reads)
            assertEquals(ChatNotifyMode.MENTIONS_ONLY, preferences.mode("alice", "old", false))
            assertEquals(ChatNotifyMode.ALL, preferences.mode("bob", "old", false))
            assertEquals(ChatNotifyMode.MENTIONS_ONLY, preferences.mode("bob", "new", false))
            assertFalse(preferences.needsDefaultsMigration)
            engine.preserveExistingNotificationModes(preferences)
            assertEquals(2, reads.size)
        }

    /** A late account read failure writes neither partial choices nor the marker, so retry remains complete. */
    @Test
    fun failedAccountReadLeavesTheEntireBarrierRetryable() =
        runBlocking {
            val preferences = freshPreferences()
            var fail = true
            val engine =
                engine { label, _ ->
                    if (label == "bob" && fail) error("read failed")
                    listOf(notificationChatListRow().copy(groupIdHex = "old"))
                }
            assertTrue(runCatching { engine.preserveExistingNotificationModes(preferences) }.isFailure)
            assertTrue(preferences.needsDefaultsMigration)
            assertTrue(
                preferences.state.value.notificationModes
                    .isEmpty(),
            )
            fail = false
            engine.preserveExistingNotificationModes(preferences)
            assertEquals(ChatNotifyMode.ALL, preferences.mode("alice", "old", false))
            assertEquals(ChatNotifyMode.ALL, preferences.mode("bob", "old", false))
            assertFalse(preferences.needsDefaultsMigration)
        }

    /** Starts with a pre-migration preference file belonging only to this Robolectric test application. */
    private fun freshPreferences(): ChatMutePreferences {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return ChatMutePreferences(context)
    }

    /** Exposes only the two native reads used by the barrier, preserving their account and archive arguments. */
    private fun engine(read: (String, Boolean) -> List<ChatListRowFfi>): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "listAccounts" -> listOf("alice", "bob").map { AccountSummaryFfi(it, it, true, false, false, true) }
                "chatList" -> read(args!![0] as String, args[1] as Boolean)
                else -> error("Unexpected migration call: ${method.name}")
            }
        } as MarmotInterface
}
