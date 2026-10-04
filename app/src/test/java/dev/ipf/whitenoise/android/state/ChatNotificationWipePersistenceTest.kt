package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises transient and persistent disk failure after native identity erasure has completed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatNotificationWipePersistenceTest {
    /** A later successful transaction removes the erased identity without touching another account. */
    @Test
    fun retriesTransientRemovalFailuresBeforeReturning() {
        val fixture = removalFixture(failures = 2)

        fixture.preferences.removeAccount("alice")

        assertEquals(3, fixture.disk.attempts)
        val restored = ChatMutePreferences(RuntimeEnvironment.getApplication(), fixture.disk.underlying)
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, restored.mode("alice", "group", false))
        assertEquals(ChatNotifyMode.ALL, restored.mode("bob", "group", false))
    }

    /** Permanent disk failure is bounded; in-memory cleanup still permits remaining account-wipe work. */
    @Test
    fun boundsPersistentFailureWithoutAbortingWipeCleanup() {
        val fixture = removalFixture(failures = Int.MAX_VALUE)

        fixture.preferences.removeAccount("alice")

        assertEquals(3, fixture.disk.attempts)
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, fixture.preferences.mode("alice", "group", false))
        assertEquals(ChatNotifyMode.ALL, fixture.preferences.mode("bob", "group", false))
    }

    /** Seeds existing choices before installing a failure injector that delays actual persistence. */
    private fun removalFixture(failures: Int): RemovalFixture {
        val context = RuntimeEnvironment.getApplication()
        val shared = context.getSharedPreferences("wipe-removal-test", Context.MODE_PRIVATE)
        shared
            .edit()
            .clear()
            .putStringSet("allConversations", setOf("alice|group", "bob|group"))
            .commit()
        val disk = FailingRemovalPreferences(shared, failures)
        return RemovalFixture(ChatMutePreferences(context, disk), disk)
    }

    private data class RemovalFixture(
        val preferences: ChatMutePreferences,
        val disk: FailingRemovalPreferences,
    )

    /** Keeps the previous durable map until the configured number of failed transactions elapses. */
    private class FailingRemovalPreferences(
        val underlying: SharedPreferences,
        private val failures: Int,
    ) : SharedPreferences by underlying {
        var attempts = 0
            private set

        /** A fresh editor per attempt ensures a retry actually carries both sets to storage. */
        override fun edit(): SharedPreferences.Editor {
            val editor = underlying.edit()
            return object : SharedPreferences.Editor by editor {
                /** Preserve interception when the production writer chains the two preference sets. */
                override fun putStringSet(
                    key: String?,
                    values: Set<String>?,
                ): SharedPreferences.Editor {
                    editor.putStringSet(key, values)
                    return this
                }

                /** Only the successful attempt changes the durable stand-in read by a recreated instance. */
                override fun commit(): Boolean {
                    attempts += 1
                    return attempts > failures && editor.commit()
                }
            }
        }
    }
}
