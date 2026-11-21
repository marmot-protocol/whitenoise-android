package dev.ipf.whitenoise.android.notifications

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Held-back senders are remembered briefly, by hash only, and survive a process restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationProfileRepeatsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("notification-profile-repeats-test", Context.MODE_PRIVATE)

    @Test
    fun aHeldSenderIsRememberedForThreeMinutesAcrossARestart() {
        val now = System.currentTimeMillis()
        val update = profileTestUpdate(sender = "ABCD")
        val first = NotificationProfileRepeats(context, preferences)
        assertFalse(first.admit("sleep", update, now))
        assertFalse(first.admit("sleep", update, now + 1))
        val reply = profileTestUpdate(groupIdHex = "elsewhere", sender = "abcd", messageId = "second")
        val reopened = NotificationProfileRepeats(context, preferences)
        assertTrue(reopened.admit("sleep", reply, now + 2))
        assertTrue(reopened.admit("sleep", reply, now + 3))
        assertFalse(reopened.admit("focus", reply, now + 4))
        assertFalse(reopened.admit("sleep", reply.copy(accountRef = "account-b"), now + 4))
        assertFalse(reopened.admit("sleep", update, now + 5))
        assertFalse(reopened.admit("sleep", reply.copy(messageIdHex = "third"), now + 6))
        assertTrue(preferences.all.keys.none { it.contains("abcd") || it.contains("account-a") })
    }

    @Test
    fun oldAndForgottenAccountsArePrunedAndTheRecordStaysBounded() {
        val now = System.currentTimeMillis()
        val repeats = NotificationProfileRepeats(context, preferences)
        val old = profileTestUpdate(sender = "old")
        assertFalse(repeats.admit("sleep", old, now - NOTIFICATION_PROFILE_REPEAT_WINDOW_MS))
        assertFalse(repeats.admit("sleep", old.copy(messageIdHex = "late"), now))
        repeat(80) { repeats.admit("sleep", profileTestUpdate(sender = "s$it"), now + it) }
        assertFalse(repeats.admit("sleep", profileTestUpdate(sender = "s0", messageId = "next"), now + 80))
        assertTrue(repeats.admit("sleep", profileTestUpdate(sender = "s79", messageId = "next"), now + 80))
        assertEquals(64, preferences.all.size)

        repeats.retainAccounts(listOf("account-b"))
        assertTrue(preferences.all.isEmpty())
    }
}
